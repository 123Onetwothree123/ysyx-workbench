package ysyx_26030103.mmu

import chisel3._
import chisel3.util._

/** 多笔在途 PTW 簇。
  *
  * 每个 walker 拥有独立上下文和固定的事务 ID；页表总线允许多笔请求同时
  * 在途，响应按 ID 送回对应 walker。busOutstanding 可限制同时占用的总线
  * 事务数（0 表示跟随 walker 数）。in_order 模式下只允许最老的已完成请求
  * 先返回；out_of_order 可选任意完成者。
  */
class ysyx_26030103_PTWCluster(
    outstanding: Int = 2,
    busOutstanding: Int = 0,
    adMode: String = "fault",
    cacheEnable: Boolean = false,
    cacheEntries: Int = 16,
    cacheWays: Int = 2,
    superpage: Boolean = false,
    pteConfig: _root_.ysyx_26030103.config.ysyx_26030103_PteConfig =
      _root_.ysyx_26030103.config.ysyx_26030103_PteConfig(),
    pageBits: Int = 12,
    levels: Int = 2,
    tableEntries: Int = 1024,
    fillSuperpage: Boolean = false,
    pteSizeBytes: Int = 4,
    inOrder: Boolean = true,
    respPorts: Int = 1,
    hasU: Boolean = true
) extends ysyx_26030103_PTWBackend(respPorts) {
  require(outstanding >= 1 && outstanding <= 16,
    "PTW outstanding 必须在 1..16（页表总线事务 ID 为 4 位）")
  require(busOutstanding >= 0 && busOutstanding <= 16,
    "PTW 页表总线 outstanding 必须在 0..16（0 表示跟随 outstanding）")
  require(respPorts >= 1, "PTW 响应端口数必须 >= 1")
  private val ptrBits = log2Ceil(outstanding.max(2))
  private val busSlots =
    if (busOutstanding <= 0) outstanding else math.min(busOutstanding, outstanding)

  val walkers = Seq.tabulate(outstanding)(i => Module(new ysyx_26030103_PTW(
    adMode = adMode,
    cacheEnable = cacheEnable,
    cacheEntries = cacheEntries,
    cacheWays = cacheWays,
    superpage = superpage,
    pteConfig = pteConfig,
    pageBits = pageBits,
    levels = levels,
    tableEntries = tableEntries,
    fillSuperpage = fillSuperpage,
    pteSizeBytes = pteSizeBytes,
    hasU = hasU,
    busId = i
  )))
  val busy = RegInit(VecInit(Seq.fill(outstanding)(false.B)))
  // 每个 walker 最多一笔页表总线事务；busIssued 用于限制总在途数并在
  // 响应回收时释放信用。flush 后继续保留，直到旧事务真正响应完成。
  val busIssued = RegInit(VecInit(Seq.fill(outstanding)(false.B)))

  // 空闲且当前可接受请求的 walker。不能只选 !busy：flush 后 walker 可能
  // 还在收尾旧事务，此时它虽然不 busy，但 req.ready 仍为低。
  val free = VecInit((0 until outstanding).map(i =>
    !busy(i) && walkers(i).io.req.ready))
  val freeIndex = PriorityEncoder(free)
  val anyFree = free.reduce(_ || _)
  io.req.ready := anyFree && !io.flush
  for (i <- 0 until outstanding) {
    walkers(i).io.req.valid := io.req.valid && anyFree && !io.flush &&
      freeIndex === i.U
    walkers(i).io.req.bits := io.req.bits
    walkers(i).io.flush := io.flush
  }
  when(io.flush) {
    for (i <- 0 until outstanding) { busy(i) := false.B }
  }.elsewhen(io.req.fire) {
    for (i <- 0 until outstanding) {
      when(freeIndex === i.U) { busy(i) := true.B }
    }
  }

  // 页表总线请求仲裁：每拍最多选一个 walker。响应按事务 ID 路由，
  // 因此不同 walker 的请求可以重叠；busSlots 限制同时在途的事务数。
  val reqValidVec = VecInit((0 until outstanding).map(i =>
    walkers(i).io.bus.req.valid && !busIssued(i)))
  val anyReq = reqValidVec.reduce(_ || _)
  val reqIndex = PriorityEncoder(reqValidVec)
  val reqOH = UIntToOH(reqIndex, outstanding)
  val selectedReq = Mux1H(reqOH, (0 until outstanding).map(i => walkers(i).io.bus.req))

  val respId = io.bus.resp.bits.id
  val respReadyVec = VecInit((0 until outstanding).map(i =>
    walkers(i).io.bus.resp.ready))
  val respReady = MuxLookup(respId, false.B)(
    (0 until outstanding).map(i => i.U -> respReadyVec(i)))
  val respFire = io.bus.resp.valid && respReady
  // 信用在响应真正被接收后的下一拍才释放。这样同一拍不会既回收旧响应
  // 又发新请求，单槽总线模型/从设备无需处理同拍双向握手。
  val creditAvailable = PopCount(busIssued) < busSlots.U

  io.bus.req.valid := anyReq && creditAvailable
  io.bus.req.bits := selectedReq.bits
  for (i <- 0 until outstanding) {
    walkers(i).io.bus.req.ready :=
      anyReq && creditAvailable && reqOH(i) && io.bus.req.ready
    walkers(i).io.bus.resp.valid := io.bus.resp.valid && respId === i.U
    walkers(i).io.bus.resp.bits := io.bus.resp.bits
  }
  io.bus.resp.ready := respReady
  when(io.bus.resp.valid) {
    assert(respId < outstanding.U,
      "PTW bus response ID exceeds walker count")
  }
  when(io.bus.req.valid && io.bus.req.ready) {
    busIssued(reqIndex) := true.B
  }
  for (i <- 0 until outstanding) {
    when(respFire && respId === i.U) { busIssued(i) := false.B }
  }

  // 响应顺序：in_order 下高编号完成者必须等待低编号尚未完成的请求。
  val done = VecInit((0 until outstanding).map(i => busy(i) && walkers(i).io.resp.valid))
  def lowerPending(i: Int): Bool =
    (0 until i).map(j => busy(j) && !walkers(j).io.resp.valid).reduceOption(_ || _).getOrElse(false.B)
  val eligibleBase = (0 until outstanding).map { i =>
    if (inOrder) done(i) && !lowerPending(i) else done(i)
  }

  // 每个响应端口独立选择一个完成者；端口之间按优先级消去，
  // 因而同一拍最多可以回填 refillPorts 个 TLB 表项。
  var remaining: Seq[Bool] = eligibleBase
  val selValid = Wire(Vec(respPorts, Bool()))
  val selIndex = Wire(Vec(respPorts, UInt(ptrBits.W)))
  val selOH = Wire(Vec(respPorts, UInt(outstanding.W)))
  for (port <- 0 until respPorts) {
    val remVec = VecInit(remaining)
    val valid = remVec.reduce(_ || _)
    val index = PriorityEncoder(remVec)
    val oh = UIntToOH(index, outstanding)
    selValid(port) := valid
    selIndex(port) := index
    selOH(port) := oh
    io.resp(port).valid := valid
    io.resp(port).bits := MuxLookup(index, 0.U.asTypeOf(new ysyx_26030103_PTWResponse))(
      (0 until outstanding).map(i => i.U -> walkers(i).io.resp.bits)
    )
    when(valid && io.resp(port).ready) {
      for (i <- 0 until outstanding) {
        when(oh(i)) { busy(i) := false.B }
      }
    }
    remaining = remaining.zipWithIndex.map { case (bit, i) => bit && !oh(i) }
  }

  for (i <- 0 until outstanding) {
    val selected = (0 until respPorts).map { p =>
      selValid(p) && selOH(p)(i) && io.resp(p).ready
    }.reduceOption(_ || _).getOrElse(false.B)
    // flush 后 busy 已清零的 walker 仍可能在收尾旧 walk，完成时自动弹掉
    // 结果，避免卡在 done 状态而永久占住空闲列表。
    walkers(i).io.resp.ready := !busy(i) || selected
  }
}
