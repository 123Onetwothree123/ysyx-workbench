package ysyx_26030103.mem
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._
import _root_.ysyx_26030103.infra._
import _root_.ysyx_26030103.cache._
import _root_.ysyx_26030103.config._
class ysyx_26030103_LSU(
    val XLEN: Int = ysyx_26030103_KconfigDefaults.NpcXlen,
    // D 扩展存在时，即使 XLEN=32 也必须支持 8 字节 FLD/FSD 访存。
    val UseD: Boolean = false,
    val WBufDepth: Int = ysyx_26030103_KconfigDefaults.NpcWBufDepth, // 写缓冲项数(必须是2的幂, 环形队列按位回绕)
    val DCacheEnable: Boolean = false,
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
  require(XLEN == 32 || XLEN == 64, s"LSU XLEN 非法: $XLEN")
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

  // DCache 关闭时数据通路没有 VIPT 语义：请求直接走 LSU 的 AXI 读/写，
  // ALUResult 已是 DMMU 给出的物理地址，不能再用它当 VA 去等带标签 PA。
  private val dataVIPT = DCacheVIPT && DCacheEnable
  private[mem] val dataVIPTParallel = dataVIPT && VIPTParallel

  val io = IO(new ysyx_26030103_LSUIO(XLEN, AddressWidth, BusDataWidth))
  dontTouch(io.DCacheFlush)
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
  DCache.io.dma_snoop_valid := io.DCacheDMASnoopValid
  DCache.io.dma_snoop_start := io.DCacheDMASnoopStart
  DCache.io.dma_snoop_end := io.DCacheDMASnoopEnd
  io.DCacheDMASnoopReady := DCache.io.dma_snoop_ready
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
  val pendingStoreHeadBits =
    if (WBufDepth == 1) pendingStore(0) else pendingStore(pendingStoreHead)
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
  val atomicOpReg = RegInit(0.U(ysyx_26030103_AtomicOp.Width.W))
  val atomicNewData = RegInit(0.U(XLEN.W))
  val atomicResultData = RegInit(0.U(XLEN.W))
  val atomicReadLow = RegInit(0.U(32.W))
  val atomicReadBeat = RegInit(0.U(1.W))
  // AMO 的读阶段故障应按 load access fault(cause=5) 提交，
  // 写阶段故障才是 store/AMO access fault(cause=7)。
  val atomicReadFaultReg = RegInit(false.B)
  val atomicAWDone = RegInit(false.B)
  val atomicWDone = RegInit(false.B)
  val atomicWBeat = RegInit(false.B)
  // SC 的 reservation：地址使用物理地址，任何同 hart store 或显式
  // 上下文切换/异常都会清除。LR 成功时重新建立。
  val reservationValid = RegInit(false.B)
  val reservationAddress = Reg(UInt(AddressWidth.W))
  // 原子写内存完成（无论 BRESP 是否正确）的单拍事件。
  val atomicWriteComplete = WireDefault(false.B)

  val stageIsIdle = stageState === stageIdle
  val stageInputBits = Wire(chiselTypeOf(io.in.bits))
  stageInputBits := Mux(pendingStoreValid, pendingStoreHeadBits, io.in.bits)
  val stageInputValid = Mux(pendingStoreValid, true.B, io.in.valid)
  val stageInputReady = WireDefault(false.B)
  val stageInputFire = stageInputValid && stageInputReady
  val isMemOp = stageInputBits.MemoryValid
  val stageCanReplaceDone =
    stageState === stageDone && io.out.ready && !memFaultReg
  val startMem =
    (stageIsIdle || stageCanReplaceDone) && stageInputFire && isMemOp
  when(startMem) {
    messageReg := stageInputBits
    // 每次新的原子事务（包括随后因非对齐/PMP 被拒绝的）都必须清除上一次
    // AMO 读阶段的故障标记，否则拒绝路径会把它误当成当前访问的 cause=5。
    when(stageInputBits.IsAtomic) {
      atomicReadFaultReg := false.B
    }
  }
  val activeInstruction = Mux(stageIsIdle, stageInputBits, messageReg)
  val viptActiveMem = activeInstruction.MemoryValid
  val effectiveALUResult = WireDefault(activeInstruction.ALUResult)
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
    effectiveALUResult :=
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

  val stageInputMisaligned = addressIsMisaligned(
    stageInputBits.ALUResult,
    stageInputBits.WidthSelect
  )
  // PMP 故障只针对访存指令；非访存直通指令不消费 PmpFault。
  val stageInputPMPFault = io.PMPFault && stageInputBits.MemoryValid
  when(startMem) {
    misalignedReg := stageInputMisaligned
  }
  when(stageState === stageWait && transactionComplete) {
    memFaultReg := transactionFault
  }.elsewhen(startMem) {
    memFaultReg := stageInputMisaligned || stageInputPMPFault
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
    Mux(messageReg.IsAtomic && atomicReadFaultReg,
      5.U(32.W), // AMO 读阶段失败按 load access fault 处理
      Mux(messageReg.MemoryWrite, 7.U(32.W), 5.U(32.W))) // 访问故障：写=7，读=5
  )
  io.MemTrapPC := messageReg.pc
  io.MemTrapTval := messageReg.VAddress
  io.FlushIDEX := memTrapCommit
  io.FlushEXMEM := memTrapCommit
  io.out.valid := false.B
  stageFsmBody()
  val pendingStorePop =
    (stageIsIdle || stageCanReplaceDone) && pendingStoreValid && stageInputFire
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
    !pendingStoreValid && (stageIsIdle || stageCanReplaceDone)
  io.in.ready := Mux(
    externalFeedsStage,
    stageInputReady,
    canQueueYoungerStore
  )
  val pendingStorePush = io.in.fire &&
    !(externalFeedsStage && stageInputFire)
  pendingStoreBody()
  io.out.bits := activeInstruction
  io.out.bits.ALUResult := effectiveALUResult
  io.out.bits.LoadData := loadDataReg
  io.out.bits.RegisterWrite := activeInstruction.RegisterWrite && !memFaultReg // 读错误时禁止写回
  io.out.bits.Retire := activeInstruction.Retire && !memFaultReg
  // F/D load：FLW 必须把低 32 位结果 NaN-box 到 64 位 f 寄存器；FLD
  // 直接使用完整 64 位 LoadData。非 load 的 FPU 结果沿用 EXU 字段。
  val fpLoadWriteData = Mux(activeInstruction.FP64,
    loadDataReg,
    Cat(Fill(32, 1.U(1.W)), loadDataReg(31, 0)))
  io.out.bits.FPRegisterWrite :=
    activeInstruction.FPRegisterWrite && !memFaultReg
  io.out.bits.FPWriteData := Mux(
    activeInstruction.MemoryValid && activeInstruction.FPRegisterWrite,
    fpLoadWriteData, activeInstruction.FPWriteData)
  io.out.bits.FP64 := activeInstruction.FP64
  // Busy的赋值在写缓冲声明之后(见下)
  io.HazardValid := stageInputValid || (stageState =/= stageIdle)
  io.HazardRd := activeInstruction.Rd
  io.HazardRegWrite := activeInstruction.RegisterWrite && !memFaultReg
  io.HazardMemOp := activeInstruction.MemoryValid
  // 向译码级转发最终写回值
  io.ForwardData := Mux(
    activeInstruction.WBSelect === 2.U,
    activeInstruction.snpc,
    Mux(
      activeInstruction.WBSelect === 3.U,
      activeInstruction.CSRReadData,
      Mux(
        activeInstruction.WBSelect === 1.U,
        Mux(loadForwardValid, loadForwardData, loadDataReg),
        activeInstruction.ALUResult
      )
    )
  )
  io.ForwardReady := io.HazardValid && io.HazardRegWrite &&
    (!activeInstruction.MemoryValid || stageState === stageDone ||
      (loadForwardValid && !loadForwardFault))
  io.HazardFPRegWrite := activeInstruction.FPRegisterWrite && !memFaultReg
  io.HazardFPRd := activeInstruction.Rd
  val fpForwardLoadData = Mux(activeInstruction.FP64,
    Mux(loadForwardValid, loadForwardData, loadDataReg),
    Cat(Fill(32, 1.U(1.W)),
      Mux(loadForwardValid, loadForwardData, loadDataReg)(31, 0)))
  io.ForwardFPData := Mux(
    activeInstruction.MemoryValid && activeInstruction.FPRegisterWrite,
    fpForwardLoadData, activeInstruction.FPWriteData)
  io.ForwardFPReady := io.HazardValid && io.HazardFPRegWrite &&
    (!activeInstruction.MemoryValid || stageState === stageDone ||
      (loadForwardValid && !loadForwardFault))
  val waitValid =
    ((stageState =/= stageIdle) || pendingStoreValid) && io.in.valid // 本级忙时等待槽中的年轻指令
  io.Hazard2Valid := waitValid
  io.Hazard2Rd := io.in.bits.Rd
  io.Hazard2RegWrite := io.in.bits.RegisterWrite
  io.Hazard2MemOp := io.in.bits.MemoryValid
  io.Hazard2ForwardData := Mux(
    io.in.bits.WBSelect === 2.U,
    io.in.bits.snpc,
    Mux(
      io.in.bits.WBSelect === 3.U,
      io.in.bits.CSRReadData,
      io.in.bits.ALUResult
    )
  )
  io.Hazard2ForwardReady := waitValid && io.in.bits.RegisterWrite && !io.in.bits.MemoryValid
  io.Hazard2FPRegWrite := io.in.bits.FPRegisterWrite
  io.Hazard2FPRd := io.in.bits.Rd
  io.Hazard2ForwardFPData := io.in.bits.FPWriteData
  io.Hazard2ForwardFPReady :=
    waitValid && io.in.bits.FPRegisterWrite && !io.in.bits.MemoryValid
  io.DebugMemoryWrite := activeInstruction.MemoryWrite
  io.DebugLoadDATA := loadDataReg(31, 0)

  val axiSize = axiSizeFor(activeInstruction.WidthSelect)
  val incomingAXISize = axiSizeFor(io.in.bits.WidthSelect)
  val stateMachine = Enum(10)
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
  val state = RegInit(stateIdle)
  // DCacheRequestActive 必须保持到 resp.fire；flush 不取消 demand。
  val dataCacheRequestActive = RegInit(false.B)
  // DCache 有在途 AR/R 时原子事务必须等待，否则会误认握手。
  val dataCacheBusActive =
    if (DCacheEnable) dataCacheRequestActive || DCache.io.axi_active else false.B
  // 非 DCache 直读在缺少 RLAST 时的排空超时。AXI 从设备必须给 RLAST，
  // 超时后按访问故障完成，避免 LSU 永久停在 StatesReadDrain。
  val readDrainTimer = RegInit(0.U(10.W))
  when(state =/= stateReadDrain) { readDrainTimer := 0.U }

  private val incomingWritePayload = alignStore(
    io.in.bits.StoreData,
    io.in.bits.ALUResult,
    io.in.bits.WidthSelect
  )
  val incomingWriteData = incomingWritePayload._1
  val incomingWriteMask = incomingWritePayload._2
  // SD 的第二拍数据。RV32 且无 D 扩展时该寄存器不会被执行路径使用。
  val incomingWriteDataHi =
    if (wideMem) io.in.bits.StoreData(63, 32) else 0.U(32.W)

  val loadWord0Reg = RegInit(0.U(32.W))
  val loadBeat = RegInit(0.U(1.W))
  val readIsWide = activeInstruction.WidthSelect === "b11".U
  val directWord =
    busSelectWord(io.DataBus.R.RDATA, activeInstruction.ALUResult)
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
  io.AccessFault := memTrapCommit && !misalignedReg
  io.AccessFaultResponse := accessFaultResponseReg
  io.DataBus.AW.AWVALID := false.B
  io.DataBus.AW.AWID := 0.U
  io.DataBus.AW.AWADDR := 0.U
  io.DataBus.AW.AWLEN := 0.U
  io.DataBus.AW.AWSIZE := axiSize // 接soc改的
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
  io.DataBus.AR.ARSIZE := axiSize
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
  // PIPT/无 MMU 时入队地址就是物理地址，保持原有行为完全不变。
  private val wbNeedsPhysicalAddress = dataVIPTParallel
  val wbHead = RegInit(0.U(log2Ceil(WBufDepth).max(1).W)) // 队首(最老)
  val wbTail = RegInit(0.U(log2Ceil(WBufDepth).max(1).W)) // 下一个空位
  val wbCount = RegInit(0.U(log2Ceil(WBufDepth + 1).W))
  val wbufEmpty = wbCount === 0.U
  val wbufFull = wbCount === WBufDepth.U
  io.PerfWBufCount := wbCount
  val storeBufferIndex = Reg(UInt(log2Ceil(WBufDepth).max(1).W)) // 当前写操作的缓冲槽位
  // 每条外部 store 在第一次被 LSU 接收时就占据一个真实写缓冲项。
  // 后续从 PendingStore 提升为活动队首时绝不重复入队。
  val wbPush = io.in.fire && io.in.bits.MemoryValid && io.in.bits.MemoryWrite &&
    !io.in.bits.IsAtomic
  assert(!wbPush || !wbufFull, "LSU accepted a store while its write buffer was full")
  // bypass store 的 PA 在 LSU 受理拍已经带正确 VA 标签到达，可以同拍登记；
  // 需要页表翻译的 store 则先保持无效，等独立 PA 端口。
  val incomingVIPTPhysicalAddressMatch =
    io.VIPTPAddressValid && io.VIPTPAddressVirtualAddress === io.in.bits.VAddress
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
      wbData(0) := incomingWriteData
      wbDataHi(0) := incomingWriteDataHi
      wbStrobe(0) := incomingWriteMask
      wbSize(0) := incomingAXISize
      wbWide(0) := io.in.bits.WidthSelect === "b11".U
      wbMisaligned(0) := addressIsMisaligned(
        io.in.bits.ALUResult,
        io.in.bits.WidthSelect
      )
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
      wbData(wbTail) := incomingWriteData
      wbDataHi(wbTail) := incomingWriteDataHi
      wbStrobe(wbTail) := incomingWriteMask
      wbSize(wbTail) := incomingAXISize
      wbWide(wbTail) := io.in.bits.WidthSelect === "b11".U
      wbMisaligned(wbTail) := addressIsMisaligned(
        io.in.bits.ALUResult,
        io.in.bits.WidthSelect
      )
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
  DCache.io.req.valid := dataCacheRequestActive && state === stateReadRequest // 复用LSU的AR/R通道
  DCache.io.req.bits.addr := viptRequestAddress
  DCache.io.req.bits.vaddr := activeInstruction.VAddress
  DCache.io.req.bits.WidthSelect := activeInstruction.WidthSelect
  DCache.io.req.bits.signed := activeInstruction.LoadSigned
  DCache.io.req_paddr_valid := viptRequestPhysicalAddressValid
  DCache.io.req_paddr_vaddr := viptRequestPhysicalAddressVirtualAddress
  DCache.io.req_kill := viptRequestKill
  DCache.io.resp.ready := dataCacheRequestActive && state === stateReadResponse
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
  val wbAWDone = RegInit(false.B)
  val wbWDone = RegInit(false.B)
  val wbAWfire = io.DataBus.AW.AWVALID && io.DataBus.AW.AWREADY
  val wbWfire = io.DataBus.W.WVALID && io.DataBus.W.WREADY
  val wbBMatch = io.DataBus.B.BID === 0.U
  val activeStoreAwaitsB = stageState === stageWait &&
    messageReg.MemoryValid && messageReg.MemoryWrite && !misalignedReg &&
    state === stateWriteWaitB && wbHead === storeBufferIndex
  val wbBReady = wbState === wbWaitB && activeStoreAwaitsB
  val wbBFire = wbBReady && io.DataBus.B.BVALID
  val wbPop = wbBFire && wbBMatch
  // 写直达 DCache 不能在 store 入队时投机更新。匹配B握手时再通知：
  // OKAY按字节更新命中行；错误响应只失效该行，以覆盖从机可能的部分副作用。
  val wbHeadAddress = if (WBufDepth == 1) wbAddress(0) else wbAddress(wbHead)
  val wbHeadVirtualAddress = if (WBufDepth == 1) wbVirtualAddress(0) else wbVirtualAddress(wbHead)
  val wbHeadPhysicalAddress = if (WBufDepth == 1) wbPhysicalAddress(0) else wbPhysicalAddress(wbHead)
  val wbHeadPhysicalAddressValid =
    if (WBufDepth == 1) wbPhysicalAddressValid(0) else wbPhysicalAddressValid(wbHead)
  val wbHeadData = if (WBufDepth == 1) wbData(0) else wbData(wbHead)
  val wbHeadDataHi = if (WBufDepth == 1) wbDataHi(0) else wbDataHi(wbHead)
  val wbHeadStrobe = if (WBufDepth == 1) wbStrobe(0) else wbStrobe(wbHead)
  val wbHeadSize = if (WBufDepth == 1) wbSize(0) else wbSize(wbHead)
  val wbHeadWide = if (WBufDepth == 1) wbWide(0) else wbWide(wbHead)
  val wbHeadMisaligned =
    if (WBufDepth == 1) wbMisaligned(0) else wbMisaligned(wbHead)
  // 只有写缓冲队首会对总线/DCache 产生副作用。VIPT 下必须等这笔 store
  // 自己的 PA 带匹配 VA 标签返回；旧标签或尚未翻译的 PA 都被拒绝。
  val wbHeadReady =
    !wbNeedsPhysicalAddress.B || wbHeadPhysicalAddressValid
  val wbHeadEffectiveAddress =
    if (wbNeedsPhysicalAddress) wbHeadPhysicalAddress else wbHeadAddress
  // 同拍 pop+push 时新 store 的 PA 有效位不能被旧 PA 覆盖。
  val wbPushOverwritesHead =
    wbPush && (if (WBufDepth == 1) true.B else wbTail === wbHead)
  val wbPhysicalAddressMatchesHead = io.VIPTPAddressValid &&
    io.VIPTPAddressVirtualAddress === wbHeadVirtualAddress && !wbHeadPhysicalAddressValid &&
    !wbPushOverwritesHead
  when(!wbufEmpty && wbNeedsPhysicalAddress.B && wbPhysicalAddressMatchesHead) {
    if (WBufDepth == 1) {
      wbPhysicalAddress(0) := io.VIPTPAddress
      wbPhysicalAddressValid(0) := true.B
    } else {
      wbPhysicalAddress(wbHead) := io.VIPTPAddress
      wbPhysicalAddressValid(wbHead) := true.B
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
  DCache.io.StoreValid := wbPop
  DCache.io.StoreResponse := io.DataBus.B.BRESP
  DCache.io.StoreAddress := wbHeadEffectiveAddress
  DCache.io.StoreVAddress := wbHeadVirtualAddress
  DCache.io.StoreData :=
    (if (wideMem) Cat(wbHeadDataHi, wbHeadData) else wbHeadData)
  DCache.io.StoreStrobe :=
    (if (wideMem)
       Mux(wbHeadWide, "hff".U(8.W), Cat(0.U(4.W), wbHeadStrobe))
     else wbHeadStrobe)
  // store B 完成同时更新 DCache 并触发 ICache snoop。
  io.StoreCommit := wbPop || atomicWriteComplete
  io.StoreCommitAddress :=
    Mux(atomicWriteComplete, atomicAddressReg, wbHeadEffectiveAddress)
  io.StoreCommitLastAddress := Mux(
    atomicWriteComplete,
    atomicAddressReg + Mux(atomicWide, 7.U, 3.U),
    wbHeadEffectiveAddress +
      Mux(
        wbHeadWide,
        (if (wideMem) 7.U(AddressWidth.W) else (XLEN / 8 - 1).U(AddressWidth.W)),
        (1.U(AddressWidth.W) << wbHeadSize) - 1.U(AddressWidth.W)
      )
  )
  // 原子写完成后顶层全局失效 DCache；这是最小接口，不引入 alias 行扫描。
  io.AtomicCommit := atomicWriteComplete
  io.AtomicBusy := atomicActive
  writeBufferBody()
  io.Busy := (stageState =/= stageIdle) || io.in.valid ||
    pendingStoreValid || !wbufEmpty // 当前级、输入或写缓冲任一忙
  io.MemoryBusy :=
    (stageState === stageWait && messageReg.MemoryValid) ||
      (stageInputValid && stageInputBits.MemoryValid) || !wbufEmpty

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
  }
  dCacheBusBody()
  pmpAndStallBody()
}
