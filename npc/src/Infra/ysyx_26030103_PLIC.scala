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
  require(DataWidth == 32 || DataWidth == 64,
    s"PLIC DataWidth 非法: $DataWidth")
  // UART 中断固定使用 source 10（xv6/OpenSBI 都按 1 << UART0_IRQ 编程）。
  // 少于 11 个 source 时该 bit 不存在，软件写 enable/priority 会被静默丢弃，
  // 外部中断永远无法到达 hart。配置阶段直接拒绝，而不是生成残缺 PLIC。
  require(numSources > 10 && numSources <= 1024,
    s"PLIC 必须支持 source 10（UART），numSources=$numSources 非法")
  require(priorityBits >= 1 && priorityBits <= 8, "PLIC priority 位宽非法")
  require(base >= 0 && base < (BigInt(1) << AddressWidth),
    s"PLIC base=$base 超出地址宽度 $AddressWidth")
  // 寄存器都按 4 字节访问，窗口按 4KiB 页对齐便于 PMA/Xbar 使用同一区间。
  require(base % BigInt(4096) == BigInt(0),
    s"PLIC base=0x${base.toString(16)} 必须 4KiB 对齐")
  private val Words = (numSources + 31) / 32
  private val IdBits = log2Ceil(numSources.max(2))
  private val PriorityMax = maxPriority.U(priorityBits.W)

  val io = IO(new ysyx_26030103_AXI5Slave(AddressWidth, DataWidth, IdWidth))
  val sources = IO(Input(UInt(numSources.W)))
  val external_irq = IO(Output(Bool()))
  val perf_claim = IO(Output(Bool()))
  val perf_complete = IO(Output(Bool()))
  io.ACLK := clock.asBool
  io.ARESETn := !reset.asBool

  val priority = RegInit(VecInit(Seq.fill(numSources)(0.U(priorityBits.W))))
  val enable = RegInit(VecInit(Seq.fill(Words)(0.U(32.W))))
  val threshold = RegInit(0.U(priorityBits.W))
  // 与 RISC-V PLIC 规范一致：bit/寄存器下标就是 source ID，source 0
  // 保留不用。因此 xv6 的 (1 << UART0_IRQ)=bit10 对应 source 10。
  val pending = RegInit(0.U(numSources.W))
  val claimed = RegInit(0.U(numSources.W))
  // gateway 只把尚未被 claim 的 source 电平转成 pending；这样 claim 后
  // source 仍为高也不会立刻重新触发，complete 清 claimed 后才重新挂起。
  pending := pending | (sources & ~claimed)
  val enabledPending = VecInit((0 until numSources).map { i =>
    i.U =/= 0.U && pending(i) && !claimed(i) &&
      enable(i / 32)(i % 32) && priority(i) > threshold
  })
  external_irq := enabledPending.reduce(_ || _)

  def addrOf(offset: BigInt): UInt = (base + offset).U(AddressWidth.W)
  // AXI 字节写：只覆盖 WSTRB 选中的字节，其余字节保留寄存器原值。
  def merge32(old: UInt, data: UInt, strb: UInt): UInt = {
    val Old = old.pad(32)
    val Data = data.pad(32)
    val Strb = strb.pad(4)
    Cat(
      Mux(Strb(3), Data(31, 24), Old(31, 24)),
      Mux(Strb(2), Data(23, 16), Old(23, 16)),
      Mux(Strb(1), Data(15, 8), Old(15, 8)),
      Mux(Strb(0), Data(7, 0), Old(7, 0))
    )
  }
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
  private val BusWide = DataWidth == 64
  private def LaneData(Data: UInt, Address: UInt): UInt =
    if (BusWide)
      Mux(Address(2), Cat(Data(31, 0), 0.U(32.W)),
        Cat(0.U(32.W), Data(31, 0)))
    else Data(31, 0)
  val WWord =
    if (BusWide) Mux(AWAddr(2), WData(63, 32), WData(31, 0))
    else WData(31, 0)
  val WByte =
    if (BusWide) Mux(AWAddr(2), WStrb(7, 4), WStrb(3, 0))
    else WStrb(3, 0)
  val RWord = WireDefault(0.U(32.W))

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
  val perfComplete = WireDefault(false.B)
  perf_complete := perfComplete
  // claim/complete 可能在同一拍同时发生（读 claim 与写 complete 走独立
  // AXI 通道）。先记录 complete 目标，最后统一更新 claimed，避免两个
  // when 块按 Chisel last-connect 互相覆盖：complete 清 A 的同时 claim B
  // 必须既清 A 又置 B。
  val completeFire = WireDefault(false.B)
  val completeIndex = WireDefault(0.U(IdBits.W))
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
    when(isPriority && WByte =/= 0.U) {
      val merged = merge32(priority(idx(IdBits - 1, 0)), WWord, WByte)
      priority(idx(IdBits - 1, 0)) := Mux(
        merged(priorityBits - 1, 0) > PriorityMax,
        PriorityMax,
        merged(priorityBits - 1, 0)
      )
    }.elsewhen(isEnable && WByte =/= 0.U) {
      enable(enableWordIndex(AWAddr)) :=
        merge32(enable(enableWordIndex(AWAddr)), WWord, WByte)
    }.elsewhen(isThreshold && WByte =/= 0.U) {
      val merged = merge32(threshold, WWord, WByte)
      threshold := Mux(
        merged(priorityBits - 1, 0) > PriorityMax,
        PriorityMax,
        merged(priorityBits - 1, 0)
      )
    }.elsewhen(isComplete && WByte =/= 0.U && WWord =/= 0.U) {
      // claim 返回值就是 source ID（bit 下标），complete 必须清同一 bit。
      // 实际清位在下方与 claim 合并执行；这里只记录目标。
      val doneIdx = WWord(IdBits - 1, 0)
      completeIndex := doneIdx
      completeFire := doneIdx < numSources.U
      perfComplete := true.B
    }
    BResp := Mux(isPriority || isEnable || isThreshold || isComplete, OKAY, SLVERR)
    BValid := true.B
    AWValid := false.B
    WValid := false.B
  }
  when(BFire) { BValid := false.B }

  // 读 claim 有副作用：选择最高优先级的未 claim source，并清 pending。
  // 同优先级时 PriorityEncoder 返回最低 ID。threshold 只控制是否向 hart
  // 发出中断通知（external_irq），规范要求已 enable、已挂起且未 claim 的
  // source 即使优先级不高于 threshold 也必须能被 claim。
  val claimCandidates = VecInit((0 until numSources).map { i =>
    i.U =/= 0.U && pending(i) && !claimed(i) &&
      enable(i / 32)(i % 32) && priority(i) =/= 0.U
  })
  val claimValid = claimCandidates.reduce(_ || _)
  val candidatePriority = VecInit((0 until numSources).map { i =>
    Mux(claimCandidates(i), priority(i), 0.U(priorityBits.W))
  })
  val highestPriority =
    candidatePriority.reduce((a, b) => Mux(a > b, a, b))
  val highestPriorityMask = VecInit((0 until numSources).map { i =>
    claimCandidates(i) && priority(i) === highestPriority
  })
  val claimIndex = PriorityEncoder(highestPriorityMask)
  val claimId = Mux(claimValid, claimIndex, 0.U)
  perf_claim := ARFire && isClaimAddr(io.AR.ARADDR) && claimValid

  when(ARFire) {
    RID := io.AR.ARID
    RValid := true.B
    RResp := OKAY
    val idx = (io.AR.ARADDR - addrOf(0L)) >> 2
    when(isClaimAddr(io.AR.ARADDR)) {
      RWord := claimId
      when(claimValid) {
        pending := pending & ~UIntToOH(claimIndex, numSources)
      }
    }.elsewhen(io.AR.ARADDR === PendingAddr) {
      RWord := pending
    }.elsewhen(isThresholdAddr(io.AR.ARADDR)) {
      RWord := threshold
    }.elsewhen(io.AR.ARADDR >= addrOf(0L) && io.AR.ARADDR < addrOf(4L * numSources) &&
      idx > 0.U && idx < numSources.U) {
      RWord := priority(idx(IdBits - 1, 0))
    }.elsewhen(isEnableAddr(io.AR.ARADDR)) {
      RWord := enable(enableWordIndex(io.AR.ARADDR))
    }.otherwise {
      RWord := 0.U
      RResp := SLVERR
    }
    RData := LaneData(RWord, io.AR.ARADDR)
  }
  // claimed 的唯一更新点：complete 先清，claim 再置。两笔事件的目标在
  // 正常协议下互斥（已 claimed 的 source 不会被再次 claim），但即使发生
  // 同 ID 冲突，也以本拍新 claim 为准，不会丢掉 complete 对其他 source
  // 的清除。这个赋值必须在两个事件块之后，才能覆盖所有旧式局部更新。
  val completeMask = Mux(
    completeFire,
    UIntToOH(completeIndex, numSources),
    0.U(numSources.W)
  )
  val claimMask = Mux(
    ARFire && isClaimAddr(io.AR.ARADDR) && claimValid,
    UIntToOH(claimIndex, numSources),
    0.U(numSources.W)
  )
  claimed := (claimed & ~completeMask) | claimMask
  when(RFire) { RValid := false.B }
}
