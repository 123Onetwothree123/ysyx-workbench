package ysyx_26030103.infra

import chisel3._
import chisel3.util._

/** 简化 PLIC：优先级、pending、enable、threshold、claim/complete。
  *
  * 当前只实现单拍 AXI 访问；source 由上层以电平输入。claim 读会清除
  * 对应 pending 位，complete 写清除同一 source 的 claim 状态。
  */
class ysyx_26030103_PLIC(
    numSources: Int = 32,
    priorityBits: Int = 3,
    maxPriority: Int = 7,
    base: BigInt = BigInt("0c000000", 16)
) extends Module {
  require(numSources >= 1 && numSources <= 1024, "PLIC source 数非法")
  require(priorityBits >= 1 && priorityBits <= 8, "PLIC priority 位宽非法")
  private val Words = (numSources + 31) / 32
  private val IdBits = log2Ceil(numSources.max(2))
  private val PriorityMax = maxPriority.U(priorityBits.W)

  val io = IO(new ysyx_26030103_AXI5Slave(32))
  val sources = IO(Input(UInt(numSources.W)))
  val external_irq = IO(Output(Bool()))
  io.ACLK := clock.asBool
  io.ARESETn := !reset.asBool

  val priority = RegInit(VecInit(Seq.fill(numSources)(0.U(priorityBits.W))))
  val enable = RegInit(VecInit(Seq.fill(Words)(0.U(32.W))))
  val threshold = RegInit(0.U(priorityBits.W))
  val pending = RegInit(0.U(numSources.W))
  val claimed = RegInit(0.U(numSources.W))
  pending := pending | sources
  val enabledPending = VecInit((0 until numSources).map { i =>
    pending(i) && enable(i / 32)(i % 32) && priority(i) > threshold
  })
  external_irq := enabledPending.reduce(_ || _)

  def addrOf(offset: BigInt): UInt = (base + offset).U(32.W)
  def priorityAddr(i: Int): UInt = addrOf(4L * i)
  def enableAddr(word: Int): UInt = addrOf(0x2000L + 4L * word)
  val PendingAddr = addrOf(0x1000L)
  val ThresholdAddr = addrOf(0x200000L)
  val ClaimAddr = addrOf(0x200004L)
  val CompleteAddr = addrOf(0x200004L)

  val OKAY = 0.U(2.W)
  val SLVERR = 2.U(2.W)
  val AWValid = RegInit(false.B)
  val WValid = RegInit(false.B)
  val BValid = RegInit(false.B)
  val AWID = RegInit(0.U(4.W))
  val AWAddr = RegInit(0.U(32.W))
  val WData = RegInit(0.U(32.W))
  val WStrb = RegInit(0.U(4.W))
  val BResp = RegInit(OKAY)
  val RValid = RegInit(false.B)
  val RID = RegInit(0.U(4.W))
  val RData = RegInit(0.U(32.W))
  val RResp = RegInit(OKAY)

  io.AW.AWREADY := !AWValid && !BValid
  io.W.WREADY := !WValid && !BValid
  io.B.BID := AWID
  io.B.BVALID := BValid
  io.B.BRESP := BResp
  io.AR.ARREADY := !RValid
  io.R.RID := RID
  io.R.RVALID := RValid
  io.R.RDATA := RData
  io.R.RRESP := RResp
  io.R.RLAST := RValid

  val AWFire = io.AW.AWVALID && io.AW.AWREADY
  val WFire = io.W.WVALID && io.W.WREADY
  val BFire = io.B.BVALID && io.B.BREADY
  val ARFire = io.AR.ARVALID && io.AR.ARREADY
  val RFire = io.R.RVALID && io.R.RREADY
  when(AWFire) { AWID := io.AW.AWID; AWAddr := io.AW.AWADDR; AWValid := true.B }
  when(WFire) { WData := io.W.WDATA; WStrb := io.W.WSTRB; WValid := true.B }

  // 单拍写：priority / enable / threshold / complete。
  when(AWValid && WValid && !BValid) {
    val idx = AWAddr >> 2
    val isPriority = AWAddr < addrOf(0x1000L) && idx > 0.U &&
      idx < numSources.U
    val isEnable = AWAddr >= addrOf(0x2000L) &&
      AWAddr < addrOf(0x2000L + 4L * Words)
    val isThreshold = AWAddr === ThresholdAddr
    val isComplete = AWAddr === CompleteAddr
    when(isPriority && WStrb =/= 0.U) {
      priority(idx(IdBits - 1, 0)) := Mux(WData(priorityBits - 1, 0) > PriorityMax,
        PriorityMax, WData(priorityBits - 1, 0))
    }.elsewhen(isEnable && WStrb =/= 0.U) {
      val word = ((AWAddr - addrOf(0x2000L)) >> 2)(log2Ceil(Words.max(2)) - 1, 0)
      enable(word) := WData
    }.elsewhen(isThreshold && WStrb =/= 0.U) {
      threshold := Mux(WData(priorityBits - 1, 0) > PriorityMax,
        PriorityMax, WData(priorityBits - 1, 0))
    }
    BResp := Mux(isPriority || isEnable || isThreshold || isComplete, OKAY, SLVERR)
    BValid := true.B
    AWValid := false.B
    WValid := false.B
  }
  when(BFire) { BValid := false.B }

  // 读 claim 有副作用：选择最高优先级 pending source 并清 pending。
  val claimCandidates = (0 until numSources).map { i =>
    pending(i) && enable(i / 32)(i % 32)
  }
  val claimIndex = PriorityEncoder(claimCandidates)
  val claimValid = claimCandidates.reduce(_ || _)
  val claimId = Mux(claimValid, claimIndex + 1.U, 0.U)

  when(ARFire) {
    RID := io.AR.ARID
    RValid := true.B
    RResp := OKAY
    val idx = io.AR.ARADDR >> 2
    when(io.AR.ARADDR === ClaimAddr) {
      RData := claimId
      when(claimValid) {
        pending(claimIndex) := false.B
        claimed(claimIndex) := true.B
      }
    }.elsewhen(io.AR.ARADDR === PendingAddr) {
      RData := pending
    }.elsewhen(io.AR.ARADDR === ThresholdAddr) {
      RData := threshold
    }.elsewhen(io.AR.ARADDR < addrOf(0x1000L) && idx > 0.U &&
      idx < numSources.U) {
      RData := priority(idx(IdBits - 1, 0))
    }.elsewhen(io.AR.ARADDR >= addrOf(0x2000L) &&
      io.AR.ARADDR < addrOf(0x2000L + 4L * Words)) {
      val word = ((io.AR.ARADDR - addrOf(0x2000L)) >> 2)(
        log2Ceil(Words.max(2)) - 1, 0)
      RData := enable(word)
    }.otherwise {
      RData := 0.U
      RResp := SLVERR
    }
  }
  when(RFire) { RValid := false.B }
}
