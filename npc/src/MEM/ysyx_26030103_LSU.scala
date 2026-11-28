package ysyx_26030103.mem
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._
import _root_.ysyx_26030103.common.ysyx_26030103_AXI5Lane.SelectWord
import _root_.ysyx_26030103.infra._
import _root_.ysyx_26030103.cache._
import _root_.ysyx_26030103.config._
class ysyx_26030103_LSU(
    val XLEN: Int = ysyx_26030103_KconfigDefaults.NpcXlen,
    // D 扩展存在时，即使 XLEN=32 也必须支持 8 字节 FLD/FSD 访存。
    val UseD: Boolean = false,
    val WBufDepth: Int = ysyx_26030103_KconfigDefaults.NpcWBufDepth, // 写缓冲项数(必须是2的幂, 环形队列按位回绕)
    val DCacheEnable: Boolean = false,
    // MMU 打开时跨页访问必须等第二页翻译；无 MMU 时 VA==PA，可直接外推。
    val UseMMU: Boolean = false,
    val MisalignedSupport: Boolean =
      ysyx_26030103_KconfigDefaults.NpcMisalignedSupport != 0,
    val MisalignedGranuleBytes: Int =
      ysyx_26030103_KconfigDefaults.NpcMisalignedGranuleBytes,
    val MisalignedNCSplit: Boolean =
      ysyx_26030103_KconfigDefaults.NpcMisalignedNCSplit != 0,
    val MisalignedMMIOFault: Boolean =
      ysyx_26030103_KconfigDefaults.NpcMisalignedMMIOFault != 0,
    val DCacheStorage: ysyx_26030103_CacheStorage = ysyx_26030103_CacheStorage.Reg,
    val DCacheBlockSizeLog2: Int = ysyx_26030103_KconfigDefaults.NpcDCacheBlockSizeLog2,
    val DCacheIndexBits: Int = ysyx_26030103_KconfigDefaults.NpcDCacheIndexBits,
    val DCacheableBase: Long = 0L,
    val DCacheableMask: Long = 0L,
    val DCachePMARegions: Seq[ysyx_26030103_PMARegion] =
      ysyx_26030103_PhysicalMemoryMap.SoC(HasChipLink = false),
    val DCacheVIPT: Boolean = false,
    // VIPT alias 安全模式：index 含 VPN 位时，DCache 需要按物理 tag 失效
    // 其他 set 中的同名物理行副本。
    val DCacheVIPTAliasSafe: Boolean = false,
    // alias 失效扫描方式：all_sets 单拍全扫描 / serial 每周期一个 set /
    // parallel_n 每周期 N 个 set / directory 物理行目录。
    val DCacheVIPTAliasScanMode: String = "all_sets",
    // DMA 地址级硬件 snoop：只有 DMA_COHERENCE=hardware_snoop 生成。
    val DCacheDMASnoop: Boolean = false,
    val DCacheVIPTAliasScanWidth: Int = 0,
    val DCacheVIPTAliasDirectoryEntries: Int = 0,
    val DCacheVIPTAliasEventQueueDepth: Int =
      ysyx_26030103_KconfigDefaults.MMUCacheVIPTAliasEventQueueDepth,
    // VIPT 并行模式：DCache/LSU 在 PA 到达前就用 VA 选 index 并持有请求，
    // MMU 故障通过 ViptKill 取消该请求。
    val VIPTParallel: Boolean = false,
    val AddressWidth: Int = ysyx_26030103_KconfigDefaults.NpcAddressWidth,
    val BusDataWidth: Int = ysyx_26030103_KconfigDefaults.NpcInternalBusWidth,
    val DCacheDrainTimeout: Int =
      ysyx_26030103_KconfigDefaults.DCacheDrainTimeout,
    val DrainTimeout: Int = ysyx_26030103_KconfigDefaults.LSUDrainTimeout
) extends Module
    with LSUHelpers
    with LSUAtomic
    with LSURead
    with LSUWrite
    with LSUStage
    with LSUPMP {
  ysyx_26030103_XLENRequire(XLEN, "LSU")
  require(AddressWidth == 32 || AddressWidth == 64,
    s"LSU AddressWidth 非法: $AddressWidth")
  require(AddressWidth <= XLEN,
    s"LSU AddressWidth($AddressWidth) 不能超过 XLEN($XLEN)")
  require(BusDataWidth == 32 || BusDataWidth == 64,
    s"LSU BusDataWidth 非法: $BusDataWidth")
  // 写缓冲是环形队列，head/tail 指针按 log2Ceil(WBufDepth) 位回绕；
  // 非 2 的幂会让指针回绕与下标范围不一致，必须在 elaboration 期拒绝。
  require(WBufDepth >= 1 && (WBufDepth & (WBufDepth - 1)) == 0,
    s"LSU WBufDepth($WBufDepth) 必须是 2 的幂")
  private[mem] val wideMem = XLEN == 64 || UseD
  private[mem] val busWide = BusDataWidth == 64
  private[mem] val busWideBit = busWide.B
  private[mem] val dcDataWidth = if (wideMem) 64 else 32
  require(MisalignedGranuleBytes > 0,
    s"MISALIGNED_GRANULE_BYTES 必须为正，收到 $MisalignedGranuleBytes")
  require((MisalignedGranuleBytes & (MisalignedGranuleBytes - 1)) == 0,
    s"MISALIGNED_GRANULE_BYTES 必须是 2 的幂，收到 $MisalignedGranuleBytes")
  require(MisalignedGranuleBytes <= (1 << DCacheBlockSizeLog2),
    s"MISALIGNED_GRANULE_BYTES(${MisalignedGranuleBytes}B) 不能超过 " +
      s"cache line(${1 << DCacheBlockSizeLog2}B)")
  // 配置粒度不钳位；段内按总线/DCache 单次请求宽度继续拆。
  private[mem] val GranuleBytes = MisalignedGranuleBytes
  private[mem] val GranuleLog2 =
    Integer.numberOfTrailingZeros(GranuleBytes)
  private[mem] val ChunkBytes = Seq(
    GranuleBytes,
    BusDataWidth / 8,
    dcDataWidth / 8
  ).min
  private[mem] val ChunkLog2 = Integer.numberOfTrailingZeros(ChunkBytes)
  private[mem] val maxAccessBytes = if (wideMem) 8 else 4
  // 实际段数由可发的请求块数和最宽访问推出。
  private[mem] val maxSegments =
    ((ChunkBytes - 1) + maxAccessBytes - 1) / ChunkBytes + 1
  private[mem] val SegmentIndexWidth = log2Ceil(maxSegments).max(1)
  private[mem] val SegmentCountWidth = log2Ceil(maxSegments + 1).max(1)
  private[mem] val MisalignedSupportEnable = MisalignedSupport.B
  private[mem] val MisalignedNCSplitEnable = MisalignedNCSplit.B
  private[mem] val MisalignedMMIOFaultEnable = MisalignedMMIOFault.B

  // DCache 关闭时数据通路没有 VIPT 语义：请求直接走 LSU 的 AXI 读/写，
  // ALUResult 已是 DMMU 给出的物理地址，不能再用它当 VA 去等带标签 PA。
  private val dataVIPT = DCacheVIPT && DCacheEnable
  private[mem] val dataVIPTParallel = dataVIPT && VIPTParallel

  private def HeadOf[T <: Data](vec: Vec[T], head: UInt): T =
    if (WBufDepth == 1) vec(0) else vec(head)

  val io = IO(new ysyx_26030103_LSUIO(XLEN, AddressWidth, BusDataWidth))
  dontTouch(io.DCacheFlush)
  dontTouch(io.in.bits.CSRStateMepc)
  val DCache = ysyx_26030103_DCacheFactory.Create(
    Enable = DCacheEnable,
    BlockSizeLog2 = DCacheBlockSizeLog2,
    IndexBits = DCacheIndexBits,
    AddressWidth = AddressWidth,
    // RV32D 的 FLD/FSD 和 RV64 的 LD/SD 一样是 8 字节访问：DCache
    // 对外提供 64 位响应/写数据，内部仍按 32 位 word 存放。
    DataWidth = if (XLEN == 64 || UseD) 64 else 32,
    BusDataWidth = BusDataWidth,
    CacheableBase = DCacheableBase,
    CacheableMask = DCacheableMask,
    PMARegions = DCachePMARegions,
    Storage = DCacheStorage,
    VIPT = dataVIPT,
    VIPTAliasSafe = DCacheVIPTAliasSafe,
    VIPTAliasScanMode = DCacheVIPTAliasScanMode,
    DMASnoop = DCacheDMASnoop,
    VIPTAliasScanWidth = DCacheVIPTAliasScanWidth,
    VIPTAliasDirectoryEntries = DCacheVIPTAliasDirectoryEntries,
    VIPTAliasEventQueueDepth = DCacheVIPTAliasEventQueueDepth,
    DrainTimeout = DCacheDrainTimeout
  )
  DCache.io.DMASnoopValid := io.DCacheDMASnoopValid
  DCache.io.DMASnoopStart := io.DCacheDMASnoopStart
  DCache.io.DMASnoopEnd := io.DCacheDMASnoopEnd
  io.DCacheDMASnoopReady := DCache.io.DMASnoopReady
  io.DCachePerfHit := DCache.io.perf_hit
  io.DCachePerfMiss := DCache.io.perf_miss
  io.DCachePerfRefillRequest := DCache.io.perf_refill_req
  io.DCachePerfRefillResponse := DCache.io.perf_refill_resp
  io.DCachePerfLineFill := DCache.io.perf_line_fill
  io.DCachePerfWriteback := DCache.io.perf_writeback
  // 阶段级FSM: 接受/等待/退休
  val stageStates = Enum(3)
  val stageIdle = stageStates(0)
  val stageWait = stageStates(1)
  val stageDone = stageStates(2)
  val stageState = RegInit(stageIdle)
  val messageReg = Reg(chiselTypeOf(io.in.bits))
  private val storePointerWidth = log2Ceil(WBufDepth).max(1)
  private val storeCountWidth = log2Ceil(WBufDepth + 1)
  val pendingStore = Reg(Vec(WBufDepth, chiselTypeOf(io.in.bits)))
  val pendingStoreHead = RegInit(0.U(storePointerWidth.W))
  val pendingStoreTail = RegInit(0.U(storePointerWidth.W))
  val pendingStoreCount = RegInit(0.U(storeCountWidth.W))
  val pendingStoreValid = pendingStoreCount =/= 0.U
  val pendingStoreHeadBits = HeadOf(pendingStore, pendingStoreHead)
  // 架构 load 结果统一保留 64 位：RV64 的 LD 和 RV32D 的 FLD 都要完整
  // 高字；整数 load 只消费低 XLEN 位，FP load 由 FPWriteData 写 f 寄存器。
  val loadDataReg = RegInit(0.U(64.W))
  val accessFaultReg = RegInit(false.B)
  val accessFaultResponseReg = RegInit(0.U(2.W))
  val storeFaultReg = RegInit(false.B) // 等待B响应时锁存写访问错误
  // 地址非对齐和 AXI access fault 是两种不同的异常。单独锁存前者，
  // 避免调试端把 cause=4/6 误报成 RESP=0 的总线故障。
  val misalignedReg = RegInit(false.B)
  val memFaultReg = RegInit(false.B) // 锁存总线完成时的访存错误
  // 总线状态机在真正完成的握手拍直接通知流水级，避免先进入一个 Done
  // 状态、再让外层状态机晚一拍观察到它。
  val transactionComplete = WireDefault(false.B)
  val transactionFault = WireDefault(false.B)
  // load 数据一旦在响应通道有效即可前递给 IDU；寄存后的 LoadDataReg
  // 仍是退休消息的稳定数据源。
  val loadForwardValid = WireDefault(false.B)
  val loadForwardData = WireDefault(loadDataReg)
  val loadForwardFault = WireDefault(false.B)
  val atomicActive = RegInit(false.B)
  val atomicAddressReg = Reg(UInt(AddressWidth.W))
  // 原子宽度：true=.D(8B)，false=.W(4B)。RV32 恒为 .W。
  val atomicWide = RegInit(false.B)
  // LR/SC/AMO 的读/写两拍协议形状（.D 在 32 位总线上拆两拍）。
  val atomicBeatPlan = ysyx_26030103_LSUAXIBeat.Atomic(atomicWide, busWide)
  val atomicOpReg = RegInit(0.U(ysyx_26030103_AtomicOp.Width.W))
  val atomicNewData = RegInit(0.U(XLEN.W))
  val atomicResultData = RegInit(0.U(XLEN.W))
  val atomicReadLow = RegInit(0.U(32.W))
  val atomicReadBeat = RegInit(0.U(1.W))
  val AtomicAWDone = RegInit(false.B)
  val atomicWDone = RegInit(false.B)
  val atomicWBeat = RegInit(false.B)
  // SC 的 reservation：地址使用物理地址，任何同 hart store 或显式
  // 上下文切换/异常都会清除。LR 成功时重新建立。
  val reservationValid = RegInit(false.B)
  val reservationAddress = Reg(UInt(AddressWidth.W))
  // SC 发起读时锁存的 reservation 匹配结果，读返回后决定是否写
  val scReservationOk = RegInit(false.B)
  // 原子写内存完成（无论 BRESP 是否正确）的单拍事件。
  val atomicWriteComplete = WireDefault(false.B)

  val stageIsIdle = stageState === stageIdle
  val stageInputBits = Wire(chiselTypeOf(io.in.bits))
  stageInputBits := Mux(pendingStoreValid, pendingStoreHeadBits, io.in.bits)
  val stageInputValid = Mux(pendingStoreValid, true.B, io.in.valid)
  val stageInputReady = WireDefault(false.B)
  val stageInputFire = stageInputValid && stageInputReady
  val isMemOp = stageInputBits.MemoryValid
  val StageCanReplaceDone =
    stageState === stageDone && io.out.ready && !memFaultReg
  val startMem =
    (stageIsIdle || StageCanReplaceDone) && stageInputFire && isMemOp
  when(startMem) {
    messageReg := stageInputBits
  }
  val activeInstruction = Mux(stageIsIdle, stageInputBits, messageReg)
  val viptActiveMem = activeInstruction.MemoryValid
  val EffectiveALUResult = WireDefault(activeInstruction.ALUResult)
  // 即便当前配置不使用并行的 VIPT 数据通路，也保留这两个寄存器作为
  // “物理地址已就绪”的统一判据；原子事务必须等 PA 有效后才能发总线。
  val physicalAddressValidReg = RegInit(false.B)
  val physicalAddressReg = Reg(UInt(AddressWidth.W))
  if (dataVIPTParallel) {
    when(startMem) { physicalAddressValidReg := false.B }
    when(io.VIPTPAddressValid && io.VIPTPAddressVirtualAddress === activeInstruction.VAddress &&
        viptActiveMem) {
      physicalAddressValidReg := true.B
      physicalAddressReg := io.VIPTPAddress
    }
    EffectiveALUResult :=
      Mux(physicalAddressValidReg && viptActiveMem, physicalAddressReg, activeInstruction.ALUResult)
  } else {
    // PIPT/无 MMU 时 ALUResult 已经是物理地址，恒为就绪。
    physicalAddressValidReg := true.B
    physicalAddressReg := 0.U
  }
  // 原子事务使用的物理地址：VIPT 并行时等待带标签的 PA，其余模式直接用
  // LSU 入端的 ALUResult（DMMU 已完成翻译）。
  val atomicPhysicalAddressReady =
    if (dataVIPTParallel) physicalAddressValidReg else true.B

  // PMA 分类使用物理地址；端点上界可能等于 2^AddressWidth（例如 4GiB），
  // 因此比较时统一多扩一位，避免字面量放不进 AddressWidth。
  def addressInRegion(address: UInt, region: ysyx_26030103_PMARegion): Bool =
    ysyx_26030103_PMAAddress.InRegion(address, region, AddressWidth)
  // 返回 (PMA 命中, cacheable 子集命中)，均以 MemoryValid 为前提。
  private[mem] def classifyPMA(
      address: UInt,
      memoryValid: Bool
  ): (Bool, Bool) = {
    val mapped = memoryValid &&
      ysyx_26030103_AnyOf(DCachePMARegions.map(addressInRegion(address, _)))
    val cacheable = memoryValid &&
      ysyx_26030103_AnyOf(
        DCachePMARegions.filter(_.Cacheable).map(addressInRegion(address, _)))
    (mapped, cacheable)
  }
  val StageInputPlan = PlanAccess(
    stageInputBits.VAddress,
    stageInputBits.ALUResult,
    stageInputBits.WidthSelect,
    stageInputBits.MemoryValid,
    stageInputBits.IsAtomic,
    MisalignedSupportEnable,
    MisalignedMMIOFaultEnable,
    MisalignedNCSplitEnable,
    AddressWidth,
    GranuleBytes
  )
  // 关闭支持时沿用 cause 4/6；MMIO 按策略选择 access fault 或交给软件。
  val StageInputCause46 = StageInputPlan.cause46
  val StageInputCause57 = StageInputPlan.cause57
  val stageInputMisaligned = StageInputPlan.misaligned
  // 自然非对齐或跨粒度块的普通访问走分段路径。
  val StageInputNeedsSegments = StageInputPlan.needsSegments
  // PMP 故障只针对访存指令；非访存直通指令不消费 PmpFault。
  val StageInputPMPFault = io.PMPFault && stageInputBits.MemoryValid
  // 非对齐策略陷阱（cause 4/6 或 MMIO 的 5/7）都没有总线事务，
  // 不能产生 AXI AccessFault 事件。
  val PolicyTrapReg = RegInit(false.B)
  when(startMem) {
    // 只有 cause 4/6 的陷阱由该位选择；MMIO access fault 走 5/7。
    misalignedReg := StageInputCause46
    PolicyTrapReg := stageInputMisaligned
  }
  when(stageState === stageWait && transactionComplete) {
    memFaultReg := transactionFault
  }.elsewhen(startMem) {
    memFaultReg := stageInputMisaligned || StageInputPMPFault
  }
  when(
    stageState === stageDone && io.out.ready &&
      (!stageInputFire || !isMemOp)
  ) {
    memFaultReg := false.B
  }
  val memTrapCommit = stageState === stageDone && memFaultReg && io.out.ready

  io.MemTrapCommit := memTrapCommit
  io.MemTrapCause := Mux(
    misalignedReg,
    Mux(messageReg.MemoryWrite, 6.U(32.W), 4.U(32.W)), // 地址非对齐：写=6，读=4
    Mux(messageReg.MemoryWrite, 7.U(32.W), 5.U(32.W)) // 访问故障：写/AMO=7，读=5
  )
  io.MemTrapPC := messageReg.PC
  io.MemTrapTval := messageReg.VAddress
  io.FlushIDEX := memTrapCommit
  io.FlushEXMEM := memTrapCommit
  io.out.valid := false.B
  stageFsmBody()
  val pendingStorePop =
    (stageIsIdle || StageCanReplaceDone) && pendingStoreValid && stageInputFire
  // 原子指令虽然对 DMMU 表现为 store（AMO/SC），但它绝不进入普通
  // 写缓冲，必须占用独占的原子事务通路。
  val currentStoreBuffered = stageState =/= stageIdle &&
    messageReg.MemoryValid && messageReg.MemoryWrite && !messageReg.IsAtomic
  val storeSlotsUsed = pendingStoreCount + currentStoreBuffered.asUInt
  val pendingStoreSpace = storeSlotsUsed < WBufDepth.U || pendingStorePop
  // 有排队项时内部队首优先进入执行槽；同拍仍可把新的连续 store 放到
  // 队尾。load/ALU/分支等保持在上游，不能越过尚未确认成功的 store。
  val canQueueYoungerStore =
    (currentStoreBuffered || pendingStoreValid) &&
      io.in.bits.MemoryValid && io.in.bits.MemoryWrite &&
      !io.in.bits.IsAtomic &&
      !memFaultReg && !storeFaultReg && pendingStoreSpace
  val externalFeedsStage =
    !pendingStoreValid && (stageIsIdle || StageCanReplaceDone)
  io.in.ready := Mux(
    externalFeedsStage,
    stageInputReady,
    canQueueYoungerStore
  )
  val pendingStorePush = io.in.fire &&
    !(externalFeedsStage && stageInputFire)
  pendingStoreBody()
  io.out.bits := activeInstruction
  io.out.bits.ALUResult := EffectiveALUResult
  io.out.bits.LoadData := loadDataReg
  io.out.bits.RegisterWrite := activeInstruction.RegisterWrite && !memFaultReg // 读错误时禁止写回
  io.out.bits.Retire := activeInstruction.Retire && !memFaultReg
  // F/D load：FLW 必须把低 32 位结果 NaN-box 到 64 位 f 寄存器；FLD
  // 直接使用完整 64 位 LoadData。非 load 的 FPU 结果沿用 EXU 字段。
  val FPLoadWriteData = Mux(activeInstruction.FP64,
    loadDataReg,
    Cat(Fill(32, 1.U(1.W)), loadDataReg(31, 0)))
  io.out.bits.FPRegisterWrite :=
    activeInstruction.FPRegisterWrite && !memFaultReg
  io.out.bits.FPWriteData := Mux(
    activeInstruction.MemoryValid && activeInstruction.FPRegisterWrite,
    FPLoadWriteData, activeInstruction.FPWriteData)
  io.out.bits.FP64 := activeInstruction.FP64
  // Busy的赋值在写缓冲声明之后(见下)
  io.HazardValid := stageInputValid || (stageState =/= stageIdle)
  io.HazardRd := activeInstruction.Rd
  io.HazardRegWrite := activeInstruction.RegisterWrite && !memFaultReg
  io.HazardMemOp := activeInstruction.MemoryValid
  // 向译码级转发最终写回值
  io.ForwardData := ysyx_26030103_WritebackSource.Select(
    activeInstruction.WBSelect,
    activeInstruction.ALUResult,
    Mux(loadForwardValid, loadForwardData, loadDataReg),
    activeInstruction.snpc,
    activeInstruction.CSRReadData,
    activeInstruction.ALUResult
  )
  io.ForwardReady := io.HazardValid && io.HazardRegWrite &&
    (!activeInstruction.MemoryValid || stageState === stageDone ||
      (loadForwardValid && !loadForwardFault))
  io.HazardFPRegWrite := activeInstruction.FPRegisterWrite && !memFaultReg
  io.HazardFPRd := activeInstruction.Rd
  val FPForwardLoadData = Mux(activeInstruction.FP64,
    Mux(loadForwardValid, loadForwardData, loadDataReg),
    Cat(Fill(32, 1.U(1.W)),
      Mux(loadForwardValid, loadForwardData, loadDataReg)(31, 0)))
  io.ForwardFPData := Mux(
    activeInstruction.MemoryValid && activeInstruction.FPRegisterWrite,
    FPForwardLoadData, activeInstruction.FPWriteData)
  io.ForwardFPReady := io.HazardValid && io.HazardFPRegWrite &&
    (!activeInstruction.MemoryValid || stageState === stageDone ||
      (loadForwardValid && !loadForwardFault))
  val waitValid =
    ((stageState =/= stageIdle) || pendingStoreValid) && io.in.valid // 本级忙时等待槽中的年轻指令
  io.Hazard2Valid := waitValid
  io.Hazard2Rd := io.in.bits.Rd
  io.Hazard2RegWrite := io.in.bits.RegisterWrite
  io.Hazard2MemOp := io.in.bits.MemoryValid
  io.Hazard2ForwardData := ysyx_26030103_WritebackSource.Select(
    io.in.bits.WBSelect,
    io.in.bits.ALUResult,
    io.in.bits.ALUResult,
    io.in.bits.snpc,
    io.in.bits.CSRReadData,
    io.in.bits.ALUResult
  )
  io.Hazard2ForwardReady := waitValid && io.in.bits.RegisterWrite && !io.in.bits.MemoryValid
  io.Hazard2FPRegWrite := io.in.bits.FPRegisterWrite
  io.Hazard2FPRd := io.in.bits.Rd
  io.Hazard2ForwardFPData := io.in.bits.FPWriteData
  io.Hazard2ForwardFPReady :=
    waitValid && io.in.bits.FPRegisterWrite && !io.in.bits.MemoryValid
  io.DebugMemoryWrite := activeInstruction.MemoryWrite
  io.DebugLoadDATA := loadDataReg(31, 0)

  val AXISize = axiSizeFor(activeInstruction.WidthSelect)
  val IncomingAXISize = axiSizeFor(io.in.bits.WidthSelect)
  val stateMachine = Enum(12)
  val stateIdle = stateMachine(0)
  val stateReadRequest = stateMachine(1)
  val stateReadResponse = stateMachine(2)
  val stateWriteWaitB = stateMachine(3) // store等待属于自己的B响应
  val stateReadDrain = stateMachine(4) // 非法单拍读缺少RLAST时排空剩余响应
  // A 扩展原子事务：等总线安静 -> 读（LR/AMO）-> 写（AMO/成功的SC）-> 等B。
  val stateAtomicQuiet = stateMachine(5)
  val stateAtomicReadRequest = stateMachine(6)
  val stateAtomicReadResponse = stateMachine(7)
  val stateAtomicWriteRequest = stateMachine(8)
  val stateAtomicWriteWaitB = stateMachine(9)
  // 非对齐普通 load：按 4B 段顺序读，最后拼装。
  val stateMisLoadRequest = stateMachine(10)
  val stateMisLoadResponse = stateMachine(11)
  val state = RegInit(stateIdle)
  // DCacheRequestActive 必须保持到 response.fire；flush 不取消 demand。
  val dataCacheRequestActive = RegInit(false.B)
  // DCache 有在途 AR/R 时原子事务必须等待，否则会误认握手。
  val dataCacheBusActive =
    if (DCacheEnable) dataCacheRequestActive || DCache.io.AXIActive else false.B
  // 非 DCache 直读在缺少 RLAST 时的排空超时。AXI 从设备必须给 RLAST，
  // 超时后按访问故障完成，避免 LSU 永久停在 StatesReadDrain。
  val readDrainTimer = RegInit(0.U(10.W))
  when(state =/= stateReadDrain) { readDrainTimer := 0.U }

  private val IncomingWritePayload = alignStore(
    io.in.bits.StoreData,
    io.in.bits.ALUResult,
    io.in.bits.WidthSelect
  )
  val IncomingWriteData = IncomingWritePayload._1
  val IncomingWriteMask = IncomingWritePayload._2
  // SD 的第二拍数据。RV32 且无 D 扩展时该寄存器不会被执行路径使用。
  val IncomingWriteDataHigh =
    if (wideMem) io.in.bits.StoreData(63, 32) else 0.U(32.W)

  val loadWord0Reg = RegInit(0.U(32.W))
  val loadBeat = RegInit(0.U(1.W))
  val readIsWide = activeInstruction.WidthSelect === "b11".U
  // 普通 load 的 AXI 拍形状；与原子/写 helper 共用同一套 LEN/SIZE/beat 规则。
  val readBeatPlan = ysyx_26030103_LSUAXIBeat.Read(
    activeInstruction.WidthSelect, readIsWide, busWide)
  // 非对齐访存段状态。所有子访问都是 4B 对齐的字，最多 3 段。
  val MisalignedSegmentIndex = RegInit(0.U(SegmentIndexWidth.W))
  val MisalignedWordRegisters = RegInit(
    VecInit(Seq.fill(maxSegments)(0.U((ChunkBytes * 8).W))))
  // 额外页的翻译结果。VIPT/PIPT 都由 LSUMMU 在 memActive 期间补发。
  val xlatePaReg = RegInit(0.U(AddressWidth.W))
  val xlateVaReg = RegInit(0.U(XLEN.W))
  val xlateValidReg = RegInit(false.B)
  when(io.VIPTPAddressValid) {
    xlatePaReg := io.VIPTPAddress
    xlateVaReg := io.VIPTPAddressVirtualAddress
    xlateValidReg := true.B
  }
  // 每条指令只保留自己期间的翻译结果。
  when(startMem) { xlateValidReg := false.B }
  val activeVa = addressOf(activeInstruction.VAddress)
  val activeSegCount = accessGranuleCount(
    activeVa, activeInstruction.WidthSelect, ChunkLog2)
  val activePage1Va = Page1Va(activeVa, AddressWidth)
  val MisalignedSegmentVirtualAddress =
    ysyx_26030103_LSUSegment.SegmentAddress(
      activeVa, MisalignedSegmentIndex, ChunkLog2, AddressWidth)
  val MisalignedSegmentUsesPage1 =
    ysyx_26030103_LSUSegment.SegmentUsesPage1(
      MisalignedSegmentVirtualAddress, activeVa, AddressWidth)
  val MisalignedBasePhysicalAddress =
    if (dataVIPTParallel) physicalAddressReg else addressOf(activeInstruction.ALUResult)
  val MisalignedBaseReady = if (dataVIPTParallel) physicalAddressValidReg else true.B
  // PIPT 只通过该端口补发第二页；VIPT 的基页标签是本指令 VA，需排除。
  val MisalignedPage1TranslationValid = xlateValidReg &&
    (xlateVaReg === activePage1Va || xlateVaReg =/= activeInstruction.VAddress)
  val MisalignedPage1Ready = !UseMMU.B || MisalignedPage1TranslationValid
  val MisalignedSegmentReady = MisalignedBaseReady && (!MisalignedSegmentUsesPage1 || MisalignedPage1Ready)
  // 有第二页翻译结果就用它；无 MMU 时按连续物理页外推。
  val MisalignedSegmentPhysicalAddress =
    ysyx_26030103_LSUSegment.SegmentPaddr(
      MisalignedBasePhysicalAddress,
      xlatePaReg,
      MisalignedPage1TranslationValid,
      MisalignedSegmentVirtualAddress,
      activeVa,
      true.B,
      AddressWidth
    )
  val directWord =
    SelectWord(io.DataBus.R.RDATA, activeInstruction.ALUResult, busWide)
  val directLoadData =
    if (!wideMem)
      formatLoad(directWord, 0.U(32.W),
        activeInstruction.ALUResult, activeInstruction.WidthSelect,
        activeInstruction.LoadSigned)
    else if (busWide)
      Mux(readIsWide,
        formatLoad(io.DataBus.R.RDATA(31, 0),
          io.DataBus.R.RDATA(63, 32),
          activeInstruction.ALUResult, activeInstruction.WidthSelect,
          activeInstruction.LoadSigned),
        formatLoad(directWord, 0.U(32.W),
          activeInstruction.ALUResult, activeInstruction.WidthSelect,
          activeInstruction.LoadSigned))
    else
      Mux(readIsWide && loadBeat === 1.U,
        formatLoad(loadWord0Reg, io.DataBus.R.RDATA,
          activeInstruction.ALUResult, activeInstruction.WidthSelect,
          activeInstruction.LoadSigned),
        formatLoad(io.DataBus.R.RDATA, 0.U(32.W),
          activeInstruction.ALUResult, activeInstruction.WidthSelect,
          activeInstruction.LoadSigned))
  val isStoreTransaction = RegInit(false.B)
  // 只在总线故障真正提交的周期产生事件。顶层会再寄存一拍供 C++ 在
  // 上升沿后采样；misaligned 不属于 AXI access fault，必须排除。
  io.AccessFault := memTrapCommit && !PolicyTrapReg
  io.AccessFaultResponse := accessFaultResponseReg
  io.DataBus.AW.AWVALID := false.B
  io.DataBus.AW.AWID := 0.U
  io.DataBus.AW.AWADDR := 0.U
  io.DataBus.AW.AWLEN := 0.U
  io.DataBus.AW.AWSIZE := AXISize // 接SOC改的
  io.DataBus.AW.AWBURST := 1.U
  io.DataBus.AW.AWPROT := 0.U
  io.DataBus.AW.AWCACHE := 0.U
  io.DataBus.AW.AWQOS := 0.U
  io.DataBus.W.WVALID := false.B
  io.DataBus.W.WDATA := 0.U
  io.DataBus.W.WSTRB := 0.U
  io.DataBus.W.WLAST := false.B
  io.DataBus.B.BREADY := false.B
  io.DataBus.AR.ARVALID := false.B
  io.DataBus.AR.ARID := 0.U
  io.DataBus.AR.ARADDR := 0.U
  io.DataBus.AR.ARLEN := 0.U
  io.DataBus.AR.ARSIZE := AXISize
  io.DataBus.AR.ARBURST := 1.U
  io.DataBus.AR.ARPROT := 0.U
  io.DataBus.AR.ARCACHE := 0.U
  io.DataBus.AR.ARQOS := 0.U
  io.DataBus.R.RREADY := false.B
  io.Complete := transactionComplete
  // 所有 store 都先进入写缓冲，但只有属于该指令的 B 响应成功后才能退休。
  // 这样 RAM 的 SLVERR/DECERR 也能精确转换成 store access fault(cause=7)。
  val wbAddress = Reg(Vec(WBufDepth, UInt(AddressWidth.W)))
  val wbVirtualAddress = Reg(Vec(WBufDepth, UInt(AddressWidth.W)))
  // VIPT 并行 store：wbAddr 暂存 VA，翻译后的 PA 单独保存并带有效位。
  // 只有该位为 1（或处于 PIPT 模式）才允许驱动 AW/W 和更新 DCache。
  val wbPhysicalAddress = Reg(Vec(WBufDepth, UInt(AddressWidth.W)))
  val wbPhysicalAddressValid = RegInit(VecInit(Seq.fill(WBufDepth)(false.B)))
  val wbData = Reg(Vec(WBufDepth, UInt(32.W)))
  val wbDataHi = Reg(Vec(WBufDepth, UInt(32.W)))
  val wbStrobe = Reg(Vec(WBufDepth, UInt(4.W)))
  val wbSize = Reg(Vec(WBufDepth, UInt(3.W)))
  // AXISize 对 8 字节访问也编码为 2（每拍 4 字节），因此必须单独保存
  // 架构宽度以区分 SD 与 SW。
  val wbWide = Reg(Vec(WBufDepth, Bool()))
  val wbMisaligned = Reg(Vec(WBufDepth, Bool()))
  // 关闭非对齐支持或策略判定为故障的 store 只作为屏障，不发总线。
  val wbTrapBlocked = Reg(Vec(WBufDepth, Bool()))
  // 非对齐 store 的原始数据和分段信息。
  val wbStoreData = Reg(Vec(WBufDepth, UInt(64.W)))
  val wbSegCount = Reg(Vec(WBufDepth, UInt(SegmentCountWidth.W)))
  val wbCrossPage = Reg(Vec(WBufDepth, Bool()))
  val wbPage1Va = Reg(Vec(WBufDepth, UInt(AddressWidth.W)))
  val wbPage1Pa = Reg(Vec(WBufDepth, UInt(AddressWidth.W)))
  val wbPage1Valid = RegInit(VecInit(Seq.fill(WBufDepth)(false.B)))
  // PIPT/无 MMU 时入队地址就是物理地址，保持原有行为完全不变。
  private val wbNeedsPhysicalAddress = dataVIPTParallel
  val wbHead = RegInit(0.U(log2Ceil(WBufDepth).max(1).W)) // 队首(最老)
  val wbTail = RegInit(0.U(log2Ceil(WBufDepth).max(1).W)) // 下一个空位
  val wbCount = RegInit(0.U(log2Ceil(WBufDepth + 1).W))
  val WBufEmpty = wbCount === 0.U
  val WBufFull = wbCount === WBufDepth.U
  io.PerfWBufCount := wbCount
  val storeBufferIndex = Reg(UInt(log2Ceil(WBufDepth).max(1).W)) // 当前写操作的缓冲槽位
  // 每条外部 store 在第一次被 LSU 接收时就占据一个真实写缓冲项。
  // 后续从 PendingStore 提升为活动队首时绝不重复入队。
  val wbPush = io.in.fire && io.in.bits.MemoryValid && io.in.bits.MemoryWrite &&
    !io.in.bits.IsAtomic
  assert(!wbPush || !WBufFull, "LSU accepted a store while its write buffer was full")
  // bypass store 的 PA 在 LSU 受理拍已经带正确 VA 标签到达，可以同拍登记；
  // 需要页表翻译的 store 则先保持无效，等独立 PA 端口。
  val incomingVIPTPhysicalAddressMatch =
    io.VIPTPAddressValid && io.VIPTPAddressVirtualAddress === io.in.bits.VAddress
  val IncomingVirtualAddress = addressOf(io.in.bits.VAddress)
  val IncomingPlan = PlanAccess(
    io.in.bits.VAddress,
    io.in.bits.ALUResult,
    io.in.bits.WidthSelect,
    io.in.bits.MemoryValid,
    false.B,
    MisalignedSupportEnable,
    MisalignedMMIOFaultEnable,
    MisalignedNCSplitEnable,
    AddressWidth,
    GranuleBytes
  )
  val IncomingCrossPage = IncomingPlan.crossPage
  val IncomingPage1VirtualAddress = IncomingPlan.page1Va
  val IncomingBlocked = IncomingPlan.misaligned
  val IncomingNeedsSegments = IncomingPlan.needsSegments
  when(wbPush) {
    if (WBufDepth == 1) {
      wbAddress(0) := addressOf(io.in.bits.ALUResult)
      wbVirtualAddress(0) := addressOf(io.in.bits.VAddress)
      wbPhysicalAddress(0) := Mux(
        wbNeedsPhysicalAddress.B && incomingVIPTPhysicalAddressMatch,
        io.VIPTPAddress,
        addressOf(io.in.bits.ALUResult)
      )
      wbPhysicalAddressValid(0) := !wbNeedsPhysicalAddress.B || incomingVIPTPhysicalAddressMatch
      wbData(0) := IncomingWriteData
      wbDataHi(0) := IncomingWriteDataHigh
      wbStrobe(0) := IncomingWriteMask
      wbSize(0) := IncomingAXISize
      wbWide(0) := io.in.bits.WidthSelect === "b11".U
      wbMisaligned(0) := IncomingNeedsSegments
      wbTrapBlocked(0) := IncomingBlocked
      wbStoreData(0) := io.in.bits.StoreData
      wbSegCount(0) := accessGranuleCount(
        IncomingVirtualAddress, io.in.bits.WidthSelect, ChunkLog2)
      wbCrossPage(0) := IncomingCrossPage
      wbPage1Va(0) := IncomingPage1VirtualAddress
      wbPage1Pa(0) := 0.U
      wbPage1Valid(0) := false.B
      wbTail := 0.U
    } else {
      wbAddress(wbTail) := addressOf(io.in.bits.ALUResult)
      wbVirtualAddress(wbTail) := addressOf(io.in.bits.VAddress)
      wbPhysicalAddress(wbTail) := Mux(
        wbNeedsPhysicalAddress.B && incomingVIPTPhysicalAddressMatch,
        io.VIPTPAddress,
        addressOf(io.in.bits.ALUResult)
      )
      wbPhysicalAddressValid(wbTail) := !wbNeedsPhysicalAddress.B || incomingVIPTPhysicalAddressMatch
      wbData(wbTail) := IncomingWriteData
      wbDataHi(wbTail) := IncomingWriteDataHigh
      wbStrobe(wbTail) := IncomingWriteMask
      wbSize(wbTail) := IncomingAXISize
      wbWide(wbTail) := io.in.bits.WidthSelect === "b11".U
      wbMisaligned(wbTail) := IncomingNeedsSegments
      wbTrapBlocked(wbTail) := IncomingBlocked
      wbStoreData(wbTail) := io.in.bits.StoreData
      wbSegCount(wbTail) := accessGranuleCount(
        IncomingVirtualAddress, io.in.bits.WidthSelect, ChunkLog2)
      wbCrossPage(wbTail) := IncomingCrossPage
      wbPage1Va(wbTail) := IncomingPage1VirtualAddress
      wbPage1Pa(wbTail) := 0.U
      wbPage1Valid(wbTail) := false.B
      wbTail := wbTail + 1.U
    }
  }
  when(startMem && stageInputBits.MemoryWrite && !stageInputBits.IsAtomic) {
    // Store 指令队列和总线队列保持同序；当前活动 store 必然对应总线队首。
    storeBufferIndex := wbHead
  }
  val viptRequestAddress =
    if (dataVIPTParallel) io.VIPTPAddress
    else activeInstruction.ALUResult
  val viptRequestPhysicalAddressValid =
    if (dataVIPTParallel) io.VIPTPAddressValid else true.B
  // 非并行 VIPT：PA 标签必须用当前指令 VAddr，否则 VA≠PA 时 DCache 死锁。
  val viptRequestPhysicalAddressVirtualAddress =
    if (dataVIPTParallel) io.VIPTPAddressVirtualAddress
    else activeInstruction.VAddress
  val viptRequestKill =
    if (dataVIPTParallel) io.VIPTKill else false.B
  val MisalignedLoadRequesting = state === stateMisLoadRequest
  DCache.io.request.valid :=
    (dataCacheRequestActive && state === stateReadRequest) ||
      (MisalignedLoadRequesting && MisalignedSegmentReady)
  DCache.io.request.bits.address :=
    Mux(MisalignedLoadRequesting, MisalignedSegmentPhysicalAddress, viptRequestAddress)
  DCache.io.request.bits.vaddr :=
    Mux(MisalignedLoadRequesting, MisalignedSegmentVirtualAddress, activeInstruction.VAddress)
  // 分段 load 只发粒度块请求，由 LSU 自己拼装。
  DCache.io.request.bits.WidthSelect := Mux(
    MisalignedLoadRequesting, ChunkLog2.U, activeInstruction.WidthSelect)
  DCache.io.request.bits.signed :=
    Mux(MisalignedLoadRequesting, false.B, activeInstruction.LoadSigned)
  DCache.io.RequestPaddrValid :=
    Mux(MisalignedLoadRequesting, true.B, viptRequestPhysicalAddressValid)
  DCache.io.RequestPaddrVaddr :=
    Mux(MisalignedLoadRequesting, MisalignedSegmentVirtualAddress, viptRequestPhysicalAddressVirtualAddress)
  DCache.io.RequestKill := viptRequestKill
  DCache.io.response.ready :=
    (dataCacheRequestActive && state === stateReadResponse) ||
      (state === stateMisLoadResponse)
  DCache.io.flush := io.DCacheFlush
  DCache.io.AXI.AW.AWREADY := false.B // DCache不使用AXI写通道
  DCache.io.AXI.W.WREADY := false.B
  DCache.io.AXI.B.BID := 0.U
  DCache.io.AXI.B.BRESP := 0.U
  DCache.io.AXI.B.BVALID := false.B
  // 写事务FSM: 队首向总线发AW/W(可独立握手), 都完成后等B, B到了出队
  val wbStates = Enum(3)
  val wbIdle = wbStates(0)
  val wbIssue = wbStates(1)
  val wbWaitB = wbStates(2)
  val wbState = RegInit(wbIdle)
  // 非对齐 store 当前正在发第几段。
  val wbSegIndex = RegInit(0.U(SegmentIndexWidth.W))
  val WbAWDone = RegInit(false.B)
  val wbWDone = RegInit(false.B)
  val wbAWfire = io.DataBus.AW.AWVALID && io.DataBus.AW.AWREADY
  val wbWfire = io.DataBus.W.WVALID && io.DataBus.W.WREADY
  val wbBMatch = io.DataBus.B.BID === 0.U
  val activeStoreAwaitsB = stageState === stageWait &&
    messageReg.MemoryValid && messageReg.MemoryWrite && !misalignedReg &&
    state === stateWriteWaitB && wbHead === storeBufferIndex
  val wbBReady = wbState === wbWaitB && activeStoreAwaitsB
  val wbBFire = wbBReady && io.DataBus.B.BVALID
  val wbBMatchFire = wbBFire && wbBMatch
  // 写直达 DCache 不能在 store 入队时投机更新。匹配B握手时再通知：
  // OKAY按字节更新命中行；错误响应只失效该行，以覆盖从机可能的部分副作用。
  val wbHeadAddress = HeadOf(wbAddress, wbHead)
  val wbHeadVirtualAddress = HeadOf(wbVirtualAddress, wbHead)
  val wbHeadPhysicalAddress = HeadOf(wbPhysicalAddress, wbHead)
  val wbHeadPhysicalAddressValid = HeadOf(wbPhysicalAddressValid, wbHead)
  val wbHeadData = HeadOf(wbData, wbHead)
  val wbHeadDataHi = HeadOf(wbDataHi, wbHead)
  val wbHeadStrobe = HeadOf(wbStrobe, wbHead)
  val wbHeadSize = HeadOf(wbSize, wbHead)
  val wbHeadWide = HeadOf(wbWide, wbHead)
  val wbHeadMisaligned = HeadOf(wbMisaligned, wbHead)
  val wbHeadTrapBlocked = HeadOf(wbTrapBlocked, wbHead)
  val wbHeadStoreData = HeadOf(wbStoreData, wbHead)
  val wbHeadSegCount = HeadOf(wbSegCount, wbHead)
  val wbHeadCrossPage = HeadOf(wbCrossPage, wbHead)
  val wbHeadPage1Pa = HeadOf(wbPage1Pa, wbHead)
  val wbHeadPage1Valid = HeadOf(wbPage1Valid, wbHead)
  // 每个不同页的 PA 必须都就绪后才发任何一段写；无 MMU 页连续。
  val wbPage1Needed = wbHeadCrossPage && UseMMU.B
  val wbHeadReady =
    (!wbNeedsPhysicalAddress.B || wbHeadPhysicalAddressValid) &&
      (!wbPage1Needed || wbHeadPage1Valid)
  val wbHeadEffectiveAddress =
    if (wbNeedsPhysicalAddress) wbHeadPhysicalAddress else wbHeadAddress
  val wbHeadWidth = Mux(wbHeadWide, 3.U, wbHeadSize)
  val wbHeadSegVa = ysyx_26030103_LSUSegment.SegmentAddress(
    wbHeadVirtualAddress, wbSegIndex, ChunkLog2, AddressWidth)
  val wbHeadSegPaddr = ysyx_26030103_LSUSegment.SegmentPaddr(
    wbHeadEffectiveAddress,
    wbHeadPage1Pa,
    wbHeadPage1Valid,
    wbHeadSegVa,
    wbHeadVirtualAddress,
    wbHeadCrossPage,
    AddressWidth
  )
  val wbHeadSegPayload = segmentedStorePayload(
    wbHeadStoreData,
    wbHeadVirtualAddress,
    wbHeadWidth,
    wbSegIndex,
    ChunkLog2
  )
  val dcSegData =
    Cat(0.U((dcDataWidth - ChunkBytes * 8).W), wbHeadSegPayload._1)
  val dcSegStrobe =
    Cat(0.U((dcDataWidth / 8 - ChunkBytes).W), wbHeadSegPayload._2)
  val wbLastSegment = wbSegIndex === wbHeadSegCount - 1.U
  // 非对齐 store 只有最后一段的 B 成功才出队。
  val wbPop = wbBMatchFire && (!wbHeadMisaligned || wbLastSegment)
  val wbMisSegmentCommit = wbBMatchFire && wbHeadMisaligned
  // 同拍 pop+push 时新 store 的 PA 有效位不能被旧 PA 覆盖。
  val wbPushOverwritesHead =
    wbPush && (if (WBufDepth == 1) true.B else wbTail === wbHead)
  val wbPhysicalAddressMatchesHead = io.VIPTPAddressValid &&
    io.VIPTPAddressVirtualAddress === wbHeadVirtualAddress && !wbHeadPhysicalAddressValid &&
    !wbPushOverwritesHead
  when(!WBufEmpty && wbNeedsPhysicalAddress.B && wbPhysicalAddressMatchesHead) {
    if (WBufDepth == 1) {
      wbPhysicalAddress(0) := io.VIPTPAddress
      wbPhysicalAddressValid(0) := true.B
    } else {
      wbPhysicalAddress(wbHead) := io.VIPTPAddress
      wbPhysicalAddressValid(wbHead) := true.B
    }
  }
  // VIPT 下每个写缓冲项各自登记自己的基页 PA，不要求它已经成为队首。
  for (i <- 0 until WBufDepth) {
    when(io.VIPTPAddressValid && wbNeedsPhysicalAddress.B &&
        !wbPhysicalAddressValid(i) &&
        io.VIPTPAddressVirtualAddress === wbVirtualAddress(i)) {
      wbPhysicalAddress(i) := io.VIPTPAddress
      wbPhysicalAddressValid(i) := true.B
    }
  }
  // 跨页 store 的第二页 PA：按 tag 匹配对应写缓冲项，等待期间不阻断更老项。
  for (i <- 0 until WBufDepth) {
    when(io.VIPTPAddressValid && wbCrossPage(i) && !wbPage1Valid(i) &&
        (io.VIPTPAddressVirtualAddress === wbPage1Va(i) ||
          io.VIPTPAddressVirtualAddress =/= wbVirtualAddress(i))) {
      wbPage1Pa(i) := io.VIPTPAddress
      wbPage1Valid(i) := true.B
    }
  }
  // 出队槽位不再持有待用 PA。若同拍有新 store 复用它，上面的 wbPush
  // 已经写入该项自己的有效位，不能被清零覆盖。
  when(wbPop && !wbPush) {
    if (WBufDepth == 1) {
      wbPhysicalAddressValid(0) := false.B
    } else {
      wbPhysicalAddressValid(wbHead) := false.B
    }
  }
  val wbStoreNotify = Mux(wbHeadMisaligned, wbMisSegmentCommit, wbPop)
  val wbNotifyAddress =
    Mux(wbHeadMisaligned, wbHeadSegPaddr, wbHeadEffectiveAddress)
  DCache.io.StoreValid := wbStoreNotify
  DCache.io.StoreResponse := io.DataBus.B.BRESP
  DCache.io.StoreAddress := wbNotifyAddress
  DCache.io.StoreVAddress :=
    Mux(wbHeadMisaligned, wbHeadSegVa, wbHeadVirtualAddress)
  DCache.io.StoreData :=
    (if (wideMem)
       Mux(wbHeadMisaligned, dcSegData, Cat(wbHeadDataHi, wbHeadData))
     else
       Mux(wbHeadMisaligned, dcSegData, Cat(0.U(0.W), wbHeadData)))
  DCache.io.StoreStrobe :=
    (if (wideMem)
       Mux(wbHeadMisaligned, dcSegStrobe,
         Mux(wbHeadWide, "hff".U(8.W), Cat(0.U(4.W), wbHeadStrobe)))
     else Mux(wbHeadMisaligned, dcSegStrobe, wbHeadStrobe))
  // store B 完成同时更新 DCache 并触发 ICache snoop。
  io.StoreCommit := wbStoreNotify || atomicWriteComplete
  io.StoreCommitAddress :=
    Mux(atomicWriteComplete, atomicAddressReg, wbNotifyAddress)
  io.StoreCommitLastAddress := Mux(
    atomicWriteComplete,
    atomicAddressReg + Mux(atomicWide, 7.U, 3.U),
    Mux(
      wbHeadMisaligned,
      wbNotifyAddress + 3.U,
      wbHeadEffectiveAddress +
        Mux(
          wbHeadWide,
          (if (wideMem) 7.U(AddressWidth.W) else (XLEN / 8 - 1).U(AddressWidth.W)),
          (1.U(AddressWidth.W) << wbHeadSize) - 1.U(AddressWidth.W)
        )
    )
  )
  // 原子写完成后顶层全局失效 DCache；这是最小接口，不引入 alias 行扫描。
  io.AtomicCommit := atomicWriteComplete
  io.AtomicBusy := atomicActive
  writeBufferBody()
  io.Busy := (stageState =/= stageIdle) || io.in.valid ||
    pendingStoreValid || !WBufEmpty // 当前级、输入或写缓冲任一忙
  io.MemoryBusy :=
    (stageState === stageWait && messageReg.MemoryValid) ||
      (stageInputValid && stageInputBits.MemoryValid) || !WBufEmpty

  switch(state) {
    is(stateIdle) {
      idleBody()
    }
    is(stateWriteWaitB) {
      storeWaitResponseBody()
    }
    // A 扩展原子事务
    is(stateAtomicQuiet) {
      atomicQuietBody()
    }
    is(stateAtomicReadRequest) {
      atomicReadRequestBody()
    }
    is(stateAtomicReadResponse) {
      atomicReadResponseBody()
    }
    is(stateAtomicWriteRequest) {
      atomicWriteRequestBody()
    }
    is(stateAtomicWriteWaitB) {
      atomicWriteWaitResponseBody()
    }
    is(stateReadRequest) {
      readRequestBody()
    }
    is(stateReadResponse) {
      readResponseBody()
    }
    is(stateReadDrain) {
      readDrainBody()
    }
    is(stateMisLoadRequest) {
      MisalignedLoadRequestBody()
    }
    is(stateMisLoadResponse) {
      MisalignedLoadResponseBody()
    }
  }
  dCacheBusBody()
  pmpAndStallBody()
}
