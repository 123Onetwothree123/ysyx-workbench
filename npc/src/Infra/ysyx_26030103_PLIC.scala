package ysyx_26030103.infra
import _root_.ysyx_26030103.common.ysyx_26030103_KconfigDefaults

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common.ysyx_26030103_PhysicalMemoryMap
import ysyx_26030103_AXI5WriteData.{LaneWord, merge32}

// 简化 PLIC：只支持单拍 AXI 访问，source 由上层以电平输入；
// claim 读清对应 pending，complete 写清同一 source 的 claim 状态。
class ysyx_26030103_PLIC(
    NumberSources: Int = ysyx_26030103_KconfigDefaults.MMUPLICSources,
    priorityBits: Int = ysyx_26030103_KconfigDefaults.MMUPLICPriorityBits,
    maxPriority: Int = ysyx_26030103_KconfigDefaults.MMUPLICMaxPriority,
    base: BigInt = BigInt(ysyx_26030103_PhysicalMemoryMap.DefaultPLICBase),
    AddressWidth: Int = ysyx_26030103_KconfigDefaults.NpcAddressWidth,
    DataWidth: Int = ysyx_26030103_KconfigDefaults.NpcInternalBusWidth,
    IDWidth: Int = ysyx_26030103_KconfigDefaults.AXIIDWidth
) extends Module {
  require(DataWidth == 32 || DataWidth == 64,
    s"PLIC DataWidth 非法: $DataWidth")
  // UART 中断固定使用 source 10（xv6/OpenSBI 都按 1 << UART0_IRQ 编程）。
  // 少于 11 个 source 时该 bit 不存在，软件写 enable/priority 会被静默丢弃，
  // 外部中断永远无法到达 hart。配置阶段直接拒绝，而不是生成残缺 PLIC。
  require(NumberSources > 10 && NumberSources <= 1024,
    s"PLIC 必须支持 source 10（UART），NumberSources=$NumberSources 非法")
  require(priorityBits >= 1 && priorityBits <= 8, "PLIC priority 位宽非法")
  require(base >= 0 && base < (BigInt(1) << AddressWidth),
    s"PLIC base=$base 超出地址宽度 $AddressWidth")
  // 寄存器都按 4 字节访问，窗口按 4KiB 页对齐便于 PMA/Xbar 使用同一区间。
  require(base % BigInt(4096) == BigInt(0),
    s"PLIC base=0x${base.toString(16)} 必须 4KiB 对齐")
  private val words = (NumberSources + 31) / 32
  private val IDBits = log2Ceil(NumberSources.max(2))
  private val priorityMax = maxPriority.U(priorityBits.W)

  val io = IO(new ysyx_26030103_AXI5Slave(AddressWidth, DataWidth, IDWidth))
  val sources = IO(Input(UInt(NumberSources.W)))
  val ExternalIRQ = IO(Output(Bool()))
  val perf_claim = IO(Output(Bool()))
  val perf_complete = IO(Output(Bool()))

  val priority = RegInit(VecInit(Seq.fill(NumberSources)(0.U(priorityBits.W))))
  val enable = RegInit(VecInit(Seq.fill(words)(0.U(32.W))))
  val threshold = RegInit(0.U(priorityBits.W))
  // 与 RISC-V PLIC 规范一致：bit/寄存器下标就是 source ID，source 0
  // 保留不用。因此 xv6 的 (1 << UART0_IRQ)=bit10 对应 source 10。
  val pending = RegInit(0.U(NumberSources.W))
  val claimed = RegInit(0.U(NumberSources.W))
  // gateway 只把尚未被 claim 的 source 电平转成 pending；这样 claim 后
  // source 仍为高也不会立刻重新触发，complete 清 claimed 后才重新挂起。
  pending := pending | (sources & ~claimed)
  val enabledPending = VecInit((0 until NumberSources).map { i =>
    i.U =/= 0.U && pending(i) && !claimed(i) &&
      enable(i / 32)(i % 32) && priority(i) > threshold
  })
  ExternalIRQ := enabledPending.reduce(_ || _)

  def addressOf(offset: BigInt): UInt = (base + offset).U(AddressWidth.W)
  // 兼容两套 enable 布局：
  //   - QEMU/RISC-V 标准：hart0 M 上下文 0x2000，S 上下文 0x2080；
  //   - 早期简化 PLIC：0x2000 起的连续 enable word。
  // 单 hart 模型把两个上下文映射到同一份 enable 状态。xv6 使用
  // PLIC_SENABLE(0)=0x2080。
  val legacyEnableBase = 0x2000L
  val standardSEnableBase = 0x2080L
  val standardThresholdAddress = addressOf(0x201000L)
  val standardClaimAddress = addressOf(0x201004L)
  val pendingAddress = addressOf(0x1000L)
  // pending 与 enable 一样按 32 位字分页：NumberSources>32 时 0x1004/0x1008/…
  // 分别是高位 pending 字。只解码 0x1000 会把高位 source 截断成 0。
  private def isPendingAddress(address: UInt): Bool =
    address >= pendingAddress &&
      address < addressOf(0x1000L + 4L * words) &&
      ((address - pendingAddress) & 3.U) === 0.U
  private def pendingWordIndex(address: UInt): UInt =
    if (words == 1) 0.U
    else ((address - pendingAddress) >> 2)(log2Ceil(words) - 1, 0)
  private val pendingPadded = pending.pad(words * 32)
  private val pendingWords =
    VecInit((0 until words).map(w => pendingPadded(w * 32 + 31, w * 32)))
  // 早期地址 0x200000/0x200004 实际对应标准 PLIC 的 M 上下文 0，
  // 继续支持以保持既有测试/软件兼容。
  val thresholdAddress = addressOf(0x200000L)
  val claimAddress = addressOf(0x200004L)

  def enableBaseIsValid(address: UInt, base: BigInt): Bool =
    address >= addressOf(base) &&
      address < addressOf(base + 4L * words) &&
      ((address - addressOf(base)) & 3.U) === 0.U

  def isEnableAddress(address: UInt): Bool =
    enableBaseIsValid(address, legacyEnableBase) ||
      enableBaseIsValid(address, standardSEnableBase)

  def enableWordIndex(address: UInt): UInt = {
    val offset = Mux(
      address >= addressOf(standardSEnableBase),
      (address - addressOf(standardSEnableBase)) >> 2,
      (address - addressOf(legacyEnableBase)) >> 2
    )
    if (words == 1) 0.U
    else offset(log2Ceil(words) - 1, 0)
  }

  def isThresholdAddress(address: UInt): Bool =
    address === thresholdAddress || address === standardThresholdAddress
  def isClaimAddress(address: UInt): Bool =
    address === claimAddress || address === standardClaimAddress

  val OKAY = 0.U(2.W)
  val SLVERR = 2.U(2.W)
  val awValid = RegInit(false.B)
  val wValid = RegInit(false.B)
  val bValid = RegInit(false.B)
  val awID = RegInit(0.U(IDWidth.W))
  val AWAddress = RegInit(0.U(AddressWidth.W))
  val wData = RegInit(0.U(DataWidth.W))
  val wStrobe = RegInit(0.U((DataWidth / 8).W))
  val bResponse = RegInit(OKAY)
  val rValid = RegInit(false.B)
  val rID = RegInit(0.U(IDWidth.W))
  val rData = RegInit(0.U(DataWidth.W))
  val rResponse = RegInit(OKAY)
  private val busWide = DataWidth == 64
  val wWord =
    if (busWide) Mux(AWAddress(2), wData(63, 32), wData(31, 0))
    else wData(31, 0)
  val wByte =
    if (busWide) Mux(AWAddress(2), wStrobe(7, 4), wStrobe(3, 0))
    else wStrobe(3, 0)
  val rWord = WireDefault(0.U(32.W))

  io.AW.AWREADY := !awValid && !bValid
  io.W.WREADY := !wValid && !bValid
  io.B.BID := awID
  io.B.BVALID := bValid
  io.B.BRESP := bResponse
  io.AR.ARREADY := !rValid
  io.R.RID := rID
  io.R.RVALID := rValid
  io.R.RDATA := rData
  io.R.RRESP := rResponse
  io.R.RLAST := rValid

  val AWFire = io.AW.AWVALID && io.AW.AWREADY
  val wFire = io.W.WVALID && io.W.WREADY
  val bFire = io.B.BVALID && io.B.BREADY
  val ARFire = io.AR.ARVALID && io.AR.ARREADY
  val rFire = io.R.RVALID && io.R.RREADY
  val perfComplete = WireDefault(false.B)
  perf_complete := perfComplete
  // claim/complete 可能在同一拍同时发生（读 claim 与写 complete 走独立
  // AXI 通道）。先记录 complete 目标，最后统一更新 claimed，避免两个
  // when 块按 Chisel last-connect 互相覆盖：complete 清 A 的同时 claim B
  // 必须既清 A 又置 B。
  val completeFire = WireDefault(false.B)
  val completeIndex = WireDefault(0.U(IDBits.W))
  when(AWFire) { awID := io.AW.AWID; AWAddress := io.AW.AWADDR; awValid := true.B }
  when(wFire) { wData := io.W.WDATA; wStrobe := io.W.WSTRB; wValid := true.B }

  // 单拍写：priority / enable / threshold / complete。
  when(awValid && wValid && !bValid) {
    val index = (AWAddress - addressOf(0L)) >> 2
    val isPriority = AWAddress >= addressOf(0L) && AWAddress < addressOf(4L * NumberSources) &&
      index > 0.U && index < NumberSources.U
    val isEnable = isEnableAddress(AWAddress)
    val isThreshold = isThresholdAddress(AWAddress)
    val isComplete = isClaimAddress(AWAddress)
    when(isPriority && wByte =/= 0.U) {
      val merged = merge32(priority(index(IDBits - 1, 0)), wWord, wByte)
      priority(index(IDBits - 1, 0)) := Mux(
        merged(priorityBits - 1, 0) > priorityMax,
        priorityMax,
        merged(priorityBits - 1, 0)
      )
    }.elsewhen(isEnable && wByte =/= 0.U) {
      enable(enableWordIndex(AWAddress)) :=
        merge32(enable(enableWordIndex(AWAddress)), wWord, wByte)
    }.elsewhen(isThreshold && wByte =/= 0.U) {
      val merged = merge32(threshold, wWord, wByte)
      threshold := Mux(
        merged(priorityBits - 1, 0) > priorityMax,
        priorityMax,
        merged(priorityBits - 1, 0)
      )
    }.elsewhen(isComplete && wByte =/= 0.U && wWord =/= 0.U) {
      // claim 返回值就是 source ID（bit 下标），complete 必须清同一 bit。
      // 实际清位在下方与 claim 合并执行；这里只记录目标。
      // 先在完整 32 位上做范围判断，再截断到 IDBits。若先截断，越界 ID
      // （例如 NumberSources=32 时写 32..63）会回绕成合法下标，误清其他
      // source 的 claimed 位。
      completeIndex := wWord(IDBits - 1, 0)
      completeFire := wWord < NumberSources.U
      perfComplete := true.B
    }
    bResponse := Mux(isPriority || isEnable || isThreshold || isComplete, OKAY, SLVERR)
    bValid := true.B
    awValid := false.B
    wValid := false.B
  }
  when(bFire) { bValid := false.B }

  // 读 claim 有副作用：选择最高优先级的未 claim source，并清 pending。
  // 同优先级时 PriorityEncoder 返回最低 ID。threshold 只控制是否向 hart
  // 发出中断通知（ExternalIRQ），规范要求已 enable、已挂起且未 claim 的
  // source 即使优先级不高于 threshold 也必须能被 claim。
  val claimCandidates = VecInit((0 until NumberSources).map { i =>
    i.U =/= 0.U && pending(i) && !claimed(i) &&
      enable(i / 32)(i % 32) && priority(i) =/= 0.U
  })
  val claimValid = claimCandidates.reduce(_ || _)
  val candidatePriority = VecInit((0 until NumberSources).map { i =>
    Mux(claimCandidates(i), priority(i), 0.U(priorityBits.W))
  })
  val highestPriority =
    candidatePriority.reduce((a, b) => Mux(a > b, a, b))
  val highestPriorityMask = VecInit((0 until NumberSources).map { i =>
    claimCandidates(i) && priority(i) === highestPriority
  })
  val claimIndex = PriorityEncoder(highestPriorityMask)
  val claimIdentifier = Mux(claimValid, claimIndex, 0.U)
  perf_claim := ARFire && isClaimAddress(io.AR.ARADDR) && claimValid

  when(ARFire) {
    rID := io.AR.ARID
    rValid := true.B
    rResponse := OKAY
    val index = (io.AR.ARADDR - addressOf(0L)) >> 2
    when(isClaimAddress(io.AR.ARADDR)) {
      rWord := claimIdentifier
      when(claimValid) {
        pending := pending & ~UIntToOH(claimIndex, NumberSources)
      }
    }.elsewhen(isPendingAddress(io.AR.ARADDR)) {
      rWord := pendingWords(pendingWordIndex(io.AR.ARADDR))
    }.elsewhen(isThresholdAddress(io.AR.ARADDR)) {
      rWord := threshold
    }.elsewhen(io.AR.ARADDR >= addressOf(0L) && io.AR.ARADDR < addressOf(4L * NumberSources) &&
      index > 0.U && index < NumberSources.U) {
      rWord := priority(index(IDBits - 1, 0))
    }.elsewhen(isEnableAddress(io.AR.ARADDR)) {
      rWord := enable(enableWordIndex(io.AR.ARADDR))
    }.otherwise {
      rWord := 0.U
      rResponse := SLVERR
    }
    rData := LaneWord(rWord, io.AR.ARADDR, busWide)
  }
  // claimed 的唯一更新点：complete 先清，claim 再置。两笔事件的目标在
  // 正常协议下互斥（已 claimed 的 source 不会被再次 claim），但即使发生
  // 同 ID 冲突，也以本拍新 claim 为准，不会丢掉 complete 对其他 source
  // 的清除。这个赋值必须在两个事件块之后，才能覆盖所有旧式局部更新。
  val completeMask = Mux(
    completeFire,
    UIntToOH(completeIndex, NumberSources),
    0.U(NumberSources.W)
  )
  val claimMask = Mux(
    ARFire && isClaimAddress(io.AR.ARADDR) && claimValid,
    UIntToOH(claimIndex, NumberSources),
    0.U(NumberSources.W)
  )
  claimed := (claimed & ~completeMask) | claimMask
  when(rFire) { rValid := false.B }
}
