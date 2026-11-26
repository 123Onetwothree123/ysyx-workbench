package ysyx_26030103.mmu
import _root_.ysyx_26030103.common.ysyx_26030103_KconfigDefaults
import _root_.ysyx_26030103.common.ysyx_26030103_AnyOf

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
    outstanding: Int = ysyx_26030103_KconfigDefaults.MMUPTWOutstanding,
    busOutstanding: Int = ysyx_26030103_KconfigDefaults.MMUPTWBusOutstanding,
    adMode: String = "fault",
    cacheEnable: Boolean = false,
    cacheEntries: Int = ysyx_26030103_KconfigDefaults.MMUPTWCacheEntries,
    cacheWays: Int = ysyx_26030103_KconfigDefaults.MMUPTWCacheWays,
    superpage: Boolean = false,
    PTEConfig: _root_.ysyx_26030103.config.ysyx_26030103_PTEConfig =
      _root_.ysyx_26030103.config.ysyx_26030103_PTEConfig(),
    pageBits: Int = ysyx_26030103_KconfigDefaults.MMUPageBits,
    levels: Int = ysyx_26030103_KconfigDefaults.MMULevels,
    tableEntries: Int = ysyx_26030103_KconfigDefaults.MMUTableEntries,
    fillSuperpage: Boolean = false,
    PTESizeBytes: Int = ysyx_26030103_KconfigDefaults.MMUPTESizeBytes,
    inOrder: Boolean = true,
    ResponsePorts: Int = 1,
    hasU: Boolean = true,
    XLEN: Int = ysyx_26030103_KconfigDefaults.NpcXlen,
    addressWidth: Int = ysyx_26030103_KconfigDefaults.NpcAddressWidth,
    vaBits: Int = ysyx_26030103_KconfigDefaults.MMUVaBits
) extends ysyx_26030103_PTWBackend(ResponsePorts, XLEN, addressWidth, PTESizeBytes * 8) {
  require(outstanding >= 1 && outstanding <= 16,
    "PTW outstanding 必须在 1..16（页表总线事务 ID 为 4 位）")
  require(busOutstanding >= 0 && busOutstanding <= 16,
    "PTW 页表总线 outstanding 必须在 0..16（0 表示跟随 outstanding）")
  require(ResponsePorts >= 1, "PTW 响应端口数必须 >= 1")
  private val busSlots =
    if (busOutstanding <= 0) outstanding else math.min(busOutstanding, outstanding)

  val walkers = Seq.tabulate(outstanding)(i => Module(new ysyx_26030103_PTW(
    adMode = adMode,
    cacheEnable = cacheEnable,
    cacheEntries = cacheEntries,
    cacheWays = cacheWays,
    superpage = superpage,
    PTEConfig = PTEConfig,
    pageBits = pageBits,
    levels = levels,
    tableEntries = tableEntries,
    fillSuperpage = fillSuperpage,
    PTESizeBytes = PTESizeBytes,
    hasU = hasU,
    XLEN = XLEN,
    addressWidth = addressWidth,
    vaBits = vaBits,
    BusID = i
  )))
  val busy = RegInit(VecInit(Seq.fill(outstanding)(false.B)))
  // 每个 walker 最多一笔页表总线事务；busIssued 用于限制总在途数并在
  // 响应回收时释放信用。flush 后继续保留，直到旧事务真正响应完成。
  val busIssued = RegInit(VecInit(Seq.fill(outstanding)(false.B)))

  // 空闲且可接受请求的 walker：flush 后可能仍不 busy 但 request.ready 为低。
  val free = VecInit((0 until outstanding).map(i =>
    !busy(i) && walkers(i).io.request.ready))
  val freeIndex = PriorityEncoder(free)
  val anyFree = free.reduce(_ || _)
  io.request.ready := anyFree && !io.flush
  for (i <- 0 until outstanding) {
    walkers(i).io.request.valid := io.request.valid && anyFree && !io.flush &&
      freeIndex === i.U
    walkers(i).io.request.bits := io.request.bits
    walkers(i).io.flush := io.flush
  }
  when(io.flush) {
    for (i <- 0 until outstanding) { busy(i) := false.B }
  }.elsewhen(io.request.fire) {
    for (i <- 0 until outstanding) {
      when(freeIndex === i.U) { busy(i) := true.B }
    }
  }

  // 页表总线请求仲裁：每拍最多选一个 walker。响应按事务 ID 路由，
  // 因此不同 walker 的请求可以重叠；busSlots 限制同时在途的事务数。
  val requestValidVec = VecInit((0 until outstanding).map(i =>
    walkers(i).io.bus.request.valid && !busIssued(i)))
  val anyRequest = requestValidVec.reduce(_ || _)
  val RequestIndex = PriorityEncoder(requestValidVec)
  val requestOneHot = UIntToOH(RequestIndex, outstanding)
  val selectedRequest = Mux1H(requestOneHot, (0 until outstanding).map(i => walkers(i).io.bus.request))

  val ResponseID = io.bus.response.bits.ID
  val responseReadyVec = VecInit((0 until outstanding).map(i =>
    walkers(i).io.bus.response.ready))
  val responseReady = MuxLookup(ResponseID, false.B)(
    (0 until outstanding).map(i => i.U -> responseReadyVec(i)))
  val responseFire = io.bus.response.valid && responseReady
  // 信用在响应真正被接收后的下一拍才释放。这样同一拍不会既回收旧响应
  // 又发新请求，单槽总线模型/从设备无需处理同拍双向握手。
  val creditAvailable = PopCount(busIssued) < busSlots.U

  io.bus.request.valid := anyRequest && creditAvailable
  io.bus.request.bits := selectedRequest.bits
  for (i <- 0 until outstanding) {
    walkers(i).io.bus.request.ready :=
      anyRequest && creditAvailable && requestOneHot(i) && io.bus.request.ready
    walkers(i).io.bus.response.valid := io.bus.response.valid && ResponseID === i.U
    walkers(i).io.bus.response.bits := io.bus.response.bits
  }
  io.bus.response.ready := responseReady
  when(io.bus.response.valid) {
    assert(ResponseID < outstanding.U,
      "PTW bus response ID exceeds walker count")
  }
  when(io.bus.request.valid && io.bus.request.ready) {
    busIssued(RequestIndex) := true.B
  }
  for (i <- 0 until outstanding) {
    when(responseFire && ResponseID === i.U) { busIssued(i) := false.B }
  }

  // 响应顺序：in_order 下高编号完成者必须等待低编号尚未完成的请求。
  val done = VecInit((0 until outstanding).map(i => busy(i) && walkers(i).io.response.valid))
  def lowerPending(i: Int): Bool =
    ysyx_26030103_AnyOf((0 until i).map(j => busy(j) && !walkers(j).io.response.valid))
  val eligibleBase = (0 until outstanding).map { i =>
    if (inOrder) done(i) && !lowerPending(i) else done(i)
  }

  // 每个响应端口独立选择一个完成者；端口之间按优先级消去，
  // 因而同一拍最多可以回填 refillPorts 个 TLB 表项。
  var remaining: Seq[Bool] = eligibleBase
  val selectedValid = Wire(Vec(ResponsePorts, Bool()))
  val selectedOneHot = Wire(Vec(ResponsePorts, UInt(outstanding.W)))
  for (port <- 0 until ResponsePorts) {
    val remVec = VecInit(remaining)
    val valid = remVec.reduce(_ || _)
    val index = PriorityEncoder(remVec)
    val oh = UIntToOH(index, outstanding)
    selectedValid(port) := valid
    selectedOneHot(port) := oh
    io.response(port).valid := valid
    // 默认项必须使用本实例的 XLEN/地址宽度/PTE 宽度；直接 new 默认类型会
    // 回落到 Kconfig 默认的 64 位，与 RV32 walker 的响应位宽不一致。
    io.response(port).bits := MuxLookup(
      index,
      0.U.asTypeOf(new ysyx_26030103_PTWResponse(
        XLEN, addressWidth, PTESizeBytes * 8))
    )(
      (0 until outstanding).map(i => i.U -> walkers(i).io.response.bits)
    )
    when(valid && io.response(port).ready) {
      for (i <- 0 until outstanding) {
        when(oh(i)) { busy(i) := false.B }
      }
    }
    remaining = remaining.zipWithIndex.map { case (bit, i) => bit && !oh(i) }
  }

  for (i <- 0 until outstanding) {
    val selected = ysyx_26030103_AnyOf((0 until ResponsePorts).map { port =>
      selectedValid(port) && selectedOneHot(port)(i) && io.response(port).ready
    })
    // flush 后 busy 已清零的 walker 仍可能在收尾旧 walk，完成时自动弹掉
    // 结果，避免卡在 done 状态而永久占住空闲列表。
    walkers(i).io.response.ready := !busy(i) || selected
  }
}
