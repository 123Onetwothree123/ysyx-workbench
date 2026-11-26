package ysyx_26030103
import chisel3._
import chisel3.util._

//他妈的，我们伟大的scala插件和编译器设计专家应该要以死谢罪，是哪个天才想到的，如果直接写ysyx_26030103，因为我这个顶层模块类和包同名了
//能被解读成ysyx_26030103的ysyx_26030103的AXI模块，还得手动指定从最顶层的根目录去找
import _root_.ysyx_26030103.common._
import _root_.ysyx_26030103.config._
import _root_.ysyx_26030103.cache._
import _root_.ysyx_26030103.infra._
import _root_.ysyx_26030103.ifu._
import _root_.ysyx_26030103.idu._
import _root_.ysyx_26030103.exu._
import _root_.ysyx_26030103.mem._
import _root_.ysyx_26030103.wbu._
import _root_.ysyx_26030103.mmu._

class ysyx_26030103(
    val config: ysyx_26030103_NPCConfig = ysyx_26030103_NPCConfig()
) extends Module
    with FrontendConnect
    with MemoryConnect
    with AXIConnect
    with PipelineConnect
    with ObservabilityConnect {
  protected def XLEN: Int = config.XLEN
  // 顶层 AXI 端口宽度由实际生效的 SoC XLEN 决定；CPU 内部地址/数据
  // 宽度不能直接决定这个 ABI。
  val io = IO(new ysyx_26030103_IO(config.EffectiveSocXLEN))
  protected val busDataWidth: Int = config.EffectiveInternalBusWidth
  val ifu = Module(new ysyx_26030103_IFU(config.ResetAddress, XLEN, config.UseC))
  val idu = Module(new ysyx_26030103_IDU(config))
  val exu = Module(new ysyx_26030103_EXU(config))
  val wbu = Module(new ysyx_26030103_WBU(XLEN))
  // 数据侧 VIPT 并行只在 DCache 打开时才有意义；关闭 DCache 的配置
  // 仍走原来的“先翻译后访存”。
  val parallelDataVIPT =
    config.MMU.cacheMode == "vipt" && config.DCacheEnable
  val LSU = Module(
    new ysyx_26030103_LSU(
      XLEN = XLEN,
      UseD = config.UseD,
      WBufDepth = config.WBufDepth,
      DCacheEnable = config.DCacheEnable,
      UseMMU = config.MMU.enabled,
      MisalignedSupport = config.MisalignedSupport,
      MisalignedGranuleBytes = config.MisalignedGranuleBytes,
      MisalignedNCSplit = config.MisalignedNCSplit,
      MisalignedMMIOFault = config.MisalignedMMIOFault,
      DCacheStorage = config.DCacheStorage,
      DCacheBlockSizeLog2 = config.DCacheBlockSizeLog2,
      DCacheIndexBits = config.DCacheIndexBits,
      DCacheableBase = config.CacheableBase,
      DCacheableMask = config.CacheableMask,
      DCachePMARegions = config.CachePMARegions,
      // DCache 关闭时没有 VIPT 数据通路：数据访问由 LSU 直接完成，
      // ALUResult 已经是 DMMU 的物理地址，绝不能再用它当作 VA 等翻译标签。
      DCacheVIPT = config.MMU.cacheMode == "vipt" && config.DCacheEnable,
      DCacheVIPTAliasSafe = config.DCacheVIPTAliasHardware,
      DCacheVIPTAliasScanMode = config.DCacheVIPTAliasScanMode,
      DCacheDMASnoop = config.DMAHardwareSnoop && config.DCacheEnable,
      DCacheVIPTAliasScanWidth = config.DCacheVIPTAliasScanWidth,
      DCacheVIPTAliasDirectoryEntries = config.DCacheVIPTAliasDirectoryEntries,
      DCacheVIPTAliasEventQueueDepth = config.DCacheVIPTAliasEventQueueDepth,
      VIPTParallel = parallelDataVIPT,
      AddressWidth = config.AddressWidth,
      BusDataWidth = busDataWidth,
      DCacheDrainTimeout = config.DCacheDrainTimeout,
      DrainTimeout = config.LSUDrainTimeout
    )
  )
  val GPR = Module(new ysyx_26030103_GPR(XLEN, config.EnableDynamicGPRDebug))
  // 浮点寄存器堆独立于 GPR；FLEN=64 固定 32 个 f 寄存器。
  val FPROpt =
    if (config.UseF)
      Some(Module(new ysyx_26030103_FPR(config.FPULen, config.EnableDynamicFPRDebug)))
    else None
  val ICache = ysyx_26030103_ICacheFactory.Create(config)
  val arbiter = Module(
    new ysyx_26030103_AXI5Arbiter(
      AddressWidth = config.AddressWidth,
      DataWidth = busDataWidth,
      HardwareSnoop = config.DMAHardwareSnoop,
      DMAWindowBase = BigInt(config.ChipLinkMemoryBase),
      DMAWindowSize = BigInt(config.ChipLinkMemorySize),
      GrantTimeout = config.ArbiterGrantTimeout,
      CpuResponseQueueDepth = config.AXIArbCpuResponseQueueDepth,
      DMAResponseQueueDepth = config.AXIArbDMAResponseQueueDepth
    )
  )
  val xbar = Module(
    new ysyx_26030103_AXI5Xbar(
      AddressWidth = config.AddressWidth,
      PMARegions = config.PMARegions,
      PLICBaseValue = BigInt(config.MMU.PLICBase),
      // 孔径由 Kconfig 的 PLIC_SIZE 决定；默认 3MiB，止于 0x0f000000，
      // 避免覆盖 ysyxSoC 的片上 SRAM。详见 PMA 中 PLIC 区的注释。
      PLICSizeBytes = BigInt(config.MMU.PLICSize),
      CLINTBaseValue = BigInt(config.CLINTBase),
      CLINTSizeBytes = BigInt(config.CLINTSize),
      DataWidth = busDataWidth,
      IDWidth = 4,
      ResponseQueueDepth = config.AXIXbarResponseQueueDepth
    )
  )
  val CLINT = Module(
    new ysyx_26030103_AXI5CLINTSlave(
      AddressWidth = config.AddressWidth,
      DataWidth = busDataWidth
    )
  )
  val PLIC = Module(
    new ysyx_26030103_PLIC(
      NumberSources = config.MMU.PLICSources,
      priorityBits = config.MMU.PLICPriorityBits,
      maxPriority = config.MMU.PLICMaxPriority,
      base = BigInt(config.MMU.PLICBase),
      AddressWidth = config.AddressWidth,
      DataWidth = busDataWidth
    )
  )
  // ysyxSoC的ChipLink内存窗口：PMA完整覆盖时才代理入站DMA请求，否则DECERR终止
  protected val DMAProxyEnable = config.DMAWindowPresent
  val DMAErrorSlave =
    if (DMAProxyEnable) None
    else
      Some(
        Module(
          new ysyx_26030103_AXI5DMAErrorSlave(
            AddressWidth = config.AddressWidth,
            DataWidth = busDataWidth
          )
        )
      )
  protected val pipeFlush = exu.io.FlushIF

  // BTB 在 IFU 取指级查询、接受取指时快照预测标签、EXU 提交时更新；jal 另用
  // 独立 BTB（方案B：与分支表零干扰，表项带 kind 区分 Jal/Call/Ret），Ret 目标取 RAS 栈顶。
  // C 扩展打开时 BTB 索引/标签保留 PC bit1，避免同一条 32 位指令的
  // 两个半字或相邻 16 位指令互相混叠。
  val BTB = Module(
    new ysyx_26030103_BTB(
      config.BTBBits,
      config.BTBWays,
      config.AddressWidth,
      0,
      config.UseC
    )
  )
  val JalBTB =
    Module(
      new ysyx_26030103_BTB(
        config.JalBTBBits,
        config.JalBTBWays,
        config.AddressWidth,
        2,
        config.UseC
      )
    )
  val RAS = Module(new ysyx_26030103_RAS(config.RASBits, config.AddressWidth))
  val predictorSelect =
    Module(new ysyx_26030103_PredictorSelect(config.AddressWidth))
  protected val MMUEnabled = config.MMU.enabled
  // VIPT 取指并行：ICache 先用 VA 选 index，再等待带 VA 标签的 PA。
  // PIPT 走原来的“翻译完成后再取指”路径，行为不变。
  protected val parallelFetchVIPT = config.MMU.cacheMode == "vipt"
  val PMPOpt =
    if (config.MMU.PMPEnabled)
      Some(
        Module(
          new ysyx_26030103_PMP(config.MMU.PMPEntries, config.AddressWidth)
        )
      )
    else None
  // 无 MMU 时的取指 PMP 检查：PMP 直接看 IFU 的物理取指地址（VA==PA）。
  // 故障请求不进入 ICache，而是在响应级注入一条带 cause=1 的精确异常。
  // pending 只产生一次注入；blocked 一直保持到流水冲刷，防止故障 PC 被
  // 反复取指而灌入一串年轻故障指令。
  protected val PMPFetchFault = PMPOpt.map(_.io.fetchFault).getOrElse(false.B)
  protected val PMPFetchBlocked = RegInit(false.B)
  protected val PMPFetchAllow =
    if (MMUEnabled) true.B
    else PMPOpt.map(_ => !PMPFetchFault && !PMPFetchBlocked).getOrElse(true.B)
  // IFU 翻译故障锁存：MMU 故障注入与 ICache 取指门控都要读，故提到顶层。
  protected val IFUFaultValid = RegInit(false.B)
  // HAS_U=n 时 MPP 理论上已被 CSR 夹到 S；顶层再做一次防御性夹取，
  // 避免 MPRV 数据访问把不存在的 U 特权级送进 MMU/PMP。HAS_S=n 但
  // HAS_U=y 时 M+U 仍可保存 U，MPRV 降级必须按 MPP 而不是固定 M；
  // 只有 M-only 才没有 MPRV 降级，数据访问固定按 M 特权级检查。
  val hasS = config.MMU.hasS
  val hasU = config.MMU.hasU
  val mprvPrivilege =
    if (hasU) exu.io.CSRCurrentMstatus(12, 11)
    else if (hasS)
      Mux(
        exu.io.CSRCurrentMstatus(12, 11) === 0.U,
        1.U(2.W),
        exu.io.CSRCurrentMstatus(12, 11)
      )
    else 3.U(2.W)
  protected val dataPrivilege =
    if (!hasS && !hasU) 3.U(2.W)
    else
      Mux(
        exu.io.CSRPrivilege === 3.U && exu.io.CSRCurrentMstatus(17),
        mprvPrivilege,
        exu.io.CSRPrivilege
      )
  // SUM 仅在 U/S 页表权限检查存在时有意义；HAS_U=n 时固定为 0，
  // 让相关逻辑在综合和仿真中被常量传播裁剪。
  protected val fetchSum = if (hasU) exu.io.CSRCurrentMstatus(18) else false.B
  protected val dataSum = if (hasU) exu.io.CSRCurrentMstatus(18) else false.B

  protected val MMUFlush: Bool =
    if (MMUEnabled) RegNext(pipeFlush, false.B) else false.B

  val MMUUnitOpt =
    if (MMUEnabled) Some(Module(new ysyx_26030103_MMU(config.MMU))) else None
  val IMMUOpt =
    if (MMUEnabled)
      Some(
        Module(
          new ysyx_26030103_IFUMMU(
            ParallelVIPT = parallelFetchVIPT,
            XLEN = XLEN,
            AddressWidth = config.AddressWidth
          )
        )
      )
    else None
  val DMMUOpt =
    if (MMUEnabled)
      Some(
        Module(
          new ysyx_26030103_LSUMMU(
            ParallelVIPT = parallelDataVIPT,
            XLEN = XLEN,
            AddressWidth = config.AddressWidth
          )
        )
      )
    else None
  val MMUArbiterOpt =
    if (MMUEnabled)
      Some(
        Module(
          new ysyx_26030103_MMURequestArbiter(
            XLEN = XLEN,
            AddressWidth = config.AddressWidth
          )
        )
      )
    else None
  val PTWToAXIOpt =
    if (MMUEnabled) {
      // PTW 页表总线可多 outstanding；适配器内部给每笔读分配独立 AXI ID
      // （3..15）并保存 PTW 原事务 ID，响应按 ID 回送对应页表槽位。
      // 4 位下游 ID 还要保留 0/1/2 给 ICache/LSU/DMA，因此 AXI 读槽位
      // 最多 13 个；超出的页表请求留在适配器队列里等待槽位。
      val busDepth =
        if (config.MMU.PTWBusOutstanding > 0) config.MMU.PTWBusOutstanding
        else config.MMU.PTWOutstanding
      val AXIReadSlots =
        math.min(busDepth.max(1), ysyx_26030103_AXIReadID.PTWSlots)
      Some(
        Module(
          new ysyx_26030103_PTWToAXI(
            busDepth = busDepth.max(1),
            AXIReadSlots = AXIReadSlots,
            AddressWidth = config.AddressWidth,
            DataWidth = busDataWidth
          )
        )
      )
    } else None
  // 隐式页表访问必须按 S 权限经过 PMP；在 PTW 总线与 AXI 适配器之间加
  // 过滤层，PMP 关闭时保持原来的直连。
  val PTWPMPFilterOpt =
    if (MMUEnabled && config.MMU.PMPEnabled)
      Some(
        Module(
          new ysyx_26030103_PTWPMPFilter(
            AddressWidth = config.AddressWidth,
            entries = config.MMU.PMPEntries,
            faultQueueDepth = config.MMU.PTWPMPFaultQueueDepth
          )
        )
      )
    else None
  val AXIReadMuxOpt =
    if (MMUEnabled && config.MMU.PTWPath != "reuse_dcache")
      Some(
        Module(
          new ysyx_26030103_AXIReadMux(
            AddressWidth = config.AddressWidth,
            DataWidth = busDataWidth
          )
        )
      )
    else None
  val AXIReadLSUMuxOpt =
    if (MMUEnabled && config.MMU.PTWPath == "reuse_dcache")
      Some(
        Module(
          new ysyx_26030103_AXIReadMux(
            passWriteA = true,
            AddressWidth = config.AddressWidth,
            DataWidth = busDataWidth
          )
        )
      )
    else None
  // PTW 只有 A/D=hardware 时才需要写回；其余配置让 LSU 的读写通道
  // 直接接仲裁器，避免写 mux 影响普通 load。
  val AXIWriteMuxOpt =
    if (MMUEnabled && config.MMU.adMode == "hardware")
      Some(
        Module(
          new ysyx_26030103_AXIWriteMux(
            PassRead = false,
            AddressWidth = config.AddressWidth,
            DataWidth = busDataWidth
          )
        )
      )
    else None

  connectFrontend()
  connectMemory()
  connectAXI()
  connectPipeline()
  connectObservability()
}