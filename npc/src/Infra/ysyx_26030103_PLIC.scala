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
    base: BigInt = BigInt("0c000000", 16),
    AddressWidth: Int = 32,
    DataWidth: Int = 32,
    IdWidth: Int = 4
) extends Module {
  require(numSources >= 1 && numSources <= 1024, "PLIC source 数非法")
  require(priorityBits >= 1 && priorityBits <= 8, "PLIC priority 位宽非法")
  private val Words = (numSources + 31) / 32
  private val IdBits = log2Ceil(numSources.max(2))
  private val PriorityMax = maxPriority.U(priorityBits.W)

  val io = IO(new ysyx_26030103_AXI5Slave(AddressWidth, DataWidth, IdWidth))
  val sources = IO(Input(UInt(numSources.W)))
  val external_irq = IO(Output(Bool()))
  io.ACLK := clock.asBool
  io.ARESETn := !reset.asBool

  val priority = RegInit(VecInit(Seq.fill(numSources)(0.U(priorityBits.W))))
  val enable = RegInit(VecInit(Seq.fill(Words)(0.U(32.W))))
  val threshold = RegInit(0.U(priorityBits.W))
  // 与 RISC-V PLIC 规范一致：bit/寄存器下标就是 source ID，source 0
  // 保留不用。因此 xv6 的 (1 << UART0_IRQ)=bit10 对应 source 10。
  val pending = RegInit(0.U(numSources.W))
  val claimed = RegInit(0.U(numSources.W))
  pending := pending | sources
  val enabledPending = VecInit((0 until numSources).map { i =>
    i.U =/= 0.U && pending(i) && enable(i / 32)(i % 32) && priority(i) > threshold
  })
  external_irq := enabledPending.reduce(_ || _)

  def addrOf(offset: BigInt): UInt = (base + offset).U(AddressWidth.W)
  def priorityAddr(i: Int): UInt = addrOf(4L * i)
  // 兼容两套 enable 布局：
  //   - QEMU/RISC-V 标准：hart0 M 上下文 0x2000，S 上下文 0x2080；
  //   - 早期简化 PLIC：0x2000 起的连续 enable word。
  // 单 hart 模型把两个上下文映射到同一份 enable 状态。xv6 使用
  // PLIC_SENABLE(0)=0x2080。
  val LegacyEnableBase = 0x2000L
  val StandardSEnableBase = 0x2080L
  val StandardThresholdAddr = addrOf(0x201000L)
  val StandardClaimAddr = addrOf(0x201004L)
  val PendingAddr = addrOf(0x1000L)
  // 早期地址 0x200000/0x200004 实际对应标准 PLIC 的 M 上下文 0，
  // 继续支持以保持既有测试/软件兼容。
  val ThresholdAddr = addrOf(0x200000L)
  val ClaimAddr = addrOf(0x200004L)
  val CompleteAddr = addrOf(0x200004L)

  def enableBaseIsValid(address: UInt, base: BigInt): Bool =
    address >= addrOf(base) &&
      address < addrOf(base + 4L * Words) &&
      ((address - addrOf(base)) & 3.U) === 0.U

  def isEnableAddr(address: UInt): Bool =
    enableBaseIsValid(address, LegacyEnableBase) ||
      enableBaseIsValid(address, StandardSEnableBase)

  def enableWordIndex(address: UInt): UInt = {
    val offset = Mux(
      address >= addrOf(StandardSEnableBase),
      (address - addrOf(StandardSEnableBase)) >> 2,
      (address - addrOf(LegacyEnableBase)) >> 2
    )
    if (Words == 1) 0.U
    else offset(log2Ceil(Words) - 1, 0)
  }

  def isThresholdAddr(address: UInt): Bool =
    address === ThresholdAddr || address === StandardThresholdAddr
  def isClaimAddr(address: UInt): Bool =
    address === ClaimAddr || address === StandardClaimAddr

  val OKAY = 0.U(2.W)
  val SLVERR = 2.U(2.W)
  val AWValid = RegInit(false.B)
  val WValid = RegInit(false.B)
  val BValid = RegInit(false.B)
  val AWID = RegInit(0.U(IdWidth.W))
  val AWAddr = RegInit(0.U(AddressWidth.W))
  val WData = RegInit(0.U(DataWidth.W))
  val WStrb = RegInit(0.U((DataWidth / 8).W))
  val BResp = RegInit(OKAY)
  val RValid = RegInit(false.B)
  val RID = RegInit(0.U(IdWidth.W))
  val RData = RegInit(0.U(DataWidth.W))
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
    val idx = (AWAddr - addrOf(0L)) >> 2
    val isPriority = AWAddr >= addrOf(0L) && AWAddr < addrOf(4L * numSources) &&
      idx > 0.U && idx < numSources.U
    val isEnable = isEnableAddr(AWAddr)
    val isThreshold = isThresholdAddr(AWAddr)
    val isComplete = isClaimAddr(AWAddr)
    when(isPriority && WStrb =/= 0.U) {
      priority(idx(IdBits - 1, 0)) := Mux(WData(priorityBits - 1, 0) > PriorityMax,
        PriorityMax, WData(priorityBits - 1, 0))
    }.elsewhen(isEnable && WStrb =/= 0.U) {
      enable(enableWordIndex(AWAddr)) := WData
    }.elsewhen(isThreshold && WStrb =/= 0.U) {
      threshold := Mux(WData(priorityBits - 1, 0) > PriorityMax,
        PriorityMax, WData(priorityBits - 1, 0))
    }.elsewhen(isComplete && WStrb =/= 0.U && WData =/= 0.U) {
      val doneIdx = (WData - 1.U)(IdBits - 1, 0)
      claimed := claimed & ~UIntToOH(doneIdx, numSources)
    }
    BResp := Mux(isPriority || isEnable || isThreshold || isComplete, OKAY, SLVERR)
    BValid := true.B
    AWValid := false.B
    WValid := false.B
  }
  when(BFire) { BValid := false.B }

  // 读 claim 有副作用：选择最高优先级 pending source 并清 pending。
  val claimCandidates = (0 until numSources).map { i =>
    i.U =/= 0.U && pending(i) && enable(i / 32)(i % 32) && priority(i) > threshold
  }
  val claimIndex = PriorityEncoder(claimCandidates)
  val claimValid = claimCandidates.reduce(_ || _)
  val claimId = Mux(claimValid, claimIndex, 0.U)

  when(ARFire) {
    RID := io.AR.ARID
    RValid := true.B
    RResp := OKAY
    val idx = (io.AR.ARADDR - addrOf(0L)) >> 2
    when(isClaimAddr(io.AR.ARADDR)) {
      RData := claimId
      when(claimValid) {
        pending := pending & ~UIntToOH(claimIndex, numSources)
        claimed := claimed | UIntToOH(claimIndex, numSources)
      }
    }.elsewhen(io.AR.ARADDR === PendingAddr) {
      RData := pending
    }.elsewhen(isThresholdAddr(io.AR.ARADDR)) {
      RData := threshold
    }.elsewhen(io.AR.ARADDR >= addrOf(0L) && io.AR.ARADDR < addrOf(4L * numSources) &&
      idx > 0.U && idx < numSources.U) {
      RData := priority(idx(IdBits - 1, 0))
    }.elsewhen(isEnableAddr(io.AR.ARADDR)) {
      RData := enable(enableWordIndex(io.AR.ARADDR))
    }.otherwise {
      RData := 0.U
      RResp := SLVERR
    }
  }
  when(RFire) { RValid := false.B }
}
