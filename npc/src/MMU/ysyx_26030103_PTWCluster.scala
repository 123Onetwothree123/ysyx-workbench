package ysyx_26030103.mmu

import chisel3._
import chisel3.util._

/** 多笔在途 PTW 簇。
  *
  * 每个 walker 拥有独立上下文，页表总线由本模块做单端口仲裁；因此多个
  * 请求可以同时处于不同 walk 阶段，实际并发受 outstanding 和总线带宽限制。
  * in_order 模式下只允许最老的已完成请求先返回；out_of_order 可选任意完成者。
  */
class ysyx_26030103_PTWCluster(
    outstanding: Int = 2,
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
    inOrder: Boolean = true
) extends Module {
  require(outstanding >= 1, "PTW outstanding 必须 >= 1")
  private val ptrBits = log2Ceil(outstanding.max(2))

  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new ysyx_26030103_PTWRequest))
    val resp = Decoupled(new ysyx_26030103_PTWResponse)
    val bus = new ysyx_26030103_PTEBusIO
    val flush = Input(Bool())
  })

  val walkers = Seq.fill(outstanding)(Module(new ysyx_26030103_PTW(
    adMode = adMode,
    cacheEnable = cacheEnable,
    cacheEntries = cacheEntries,
    cacheWays = cacheWays,
    superpage = superpage,
    pteConfig = pteConfig,
    pageBits = pageBits,
    levels = levels,
    tableEntries = tableEntries,
    fillSuperpage = fillSuperpage
  )))
  val busy = RegInit(VecInit(Seq.fill(outstanding)(false.B)))
  val owner = RegInit(0.U(ptrBits.W))
  val ownerValid = RegInit(false.B)

  // 空闲 walker 的选择；请求只在当前空闲 slot 上握手。
  val free = VecInit((0 until outstanding).map(i => !busy(i)))
  val freeIndex = PriorityEncoder(free)
  val anyFree = free.reduce(_ || _)
  io.req.ready := anyFree && MuxLookup(freeIndex, false.B)(
    (0 until outstanding).map(i => i.U -> walkers(i).io.req.ready)
  )
  for (i <- 0 until outstanding) {
    walkers(i).io.req.valid := io.req.valid && anyFree && freeIndex === i.U
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

  // 单页表总线仲裁：取得 owner 后一直保持到响应返回。
  val reqValidVec = VecInit((0 until outstanding).map(i => walkers(i).io.bus.req.valid))
  val reqIndex = PriorityEncoder(reqValidVec)
  val reqOH = UIntToOH(reqIndex, outstanding)
  val selectedReq = Mux1H(reqOH, (0 until outstanding).map(i => walkers(i).io.bus.req))
  val anyReq = reqValidVec.reduce(_ || _)
  io.bus.req.valid := anyReq && !ownerValid
  io.bus.req.bits := selectedReq.bits
  for (i <- 0 until outstanding) {
    walkers(i).io.bus.req.ready :=
      !ownerValid && anyReq && reqOH(i) && io.bus.req.ready
    walkers(i).io.bus.resp.valid := ownerValid && owner === i.U &&
      io.bus.resp.valid
    walkers(i).io.bus.resp.bits := io.bus.resp.bits
  }
  io.bus.resp.ready := Mux(
    ownerValid,
    MuxLookup(owner, false.B)((0 until outstanding).map(i => i.U -> walkers(i).io.bus.resp.ready)),
    false.B
  )
  when(!ownerValid && anyReq && io.bus.req.ready) {
    ownerValid := true.B
    owner := reqIndex
  }.elsewhen(ownerValid && io.bus.resp.valid && io.bus.resp.ready) {
    ownerValid := false.B
  }

  // 响应顺序：in_order 下高编号完成者必须等待低编号尚未完成的请求。
  val done = VecInit((0 until outstanding).map(i => busy(i) && walkers(i).io.resp.valid))
  def lowerPending(i: Int): Bool =
    (0 until i).map(j => busy(j) && !walkers(j).io.resp.valid).reduceOption(_ || _).getOrElse(false.B)
  val eligible = VecInit((0 until outstanding).map { i =>
    if (inOrder) done(i) && !lowerPending(i) else done(i)
  })
  val respIndex = PriorityEncoder(eligible)
  val respValid = eligible.reduce(_ || _)
  io.resp.valid := respValid
  io.resp.bits := MuxLookup(respIndex, 0.U.asTypeOf(new ysyx_26030103_PTWResponse))(
    (0 until outstanding).map(i => i.U -> walkers(i).io.resp.bits)
  )
  for (i <- 0 until outstanding) {
    walkers(i).io.resp.ready := respValid && respIndex === i.U && io.resp.ready
  }
  when(io.resp.fire) {
    for (i <- 0 until outstanding) {
      when(respIndex === i.U) { busy(i) := false.B }
    }
  }
}
