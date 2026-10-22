package ysyx_26030103.config

import _root_.ysyx_26030103.common.ysyx_26030103_MULBoothConfig
import _root_.ysyx_26030103.common.ysyx_26030103_PMARegion
import _root_.ysyx_26030103.common.ysyx_26030103_PhysicalMemoryMap

/** NPC 的全部elaboration期配置。
  *
  * 字段与Kconfig/环境变量一一对应（见 `ysyx_26030103_Elaborate`），并在此处
  * 做统一合法性校验（require）与派生量计算（使能开关、最大在途数等）。
  */
case class ysyx_26030103_NPCConfig(
    XLEN: Int = 32,
    UseM: Boolean = false,
    UseA: Boolean = false,
    UseC: Boolean = false,
    MULImpl: ysyx_26030103_MULImpl = ysyx_26030103_MULImpl.ShiftAdd,
    MULEncoding: ysyx_26030103_MULEncoding = ysyx_26030103_MULEncoding.Plain,
    DIVImpl: ysyx_26030103_DIVImpl = ysyx_26030103_DIVImpl.Restoring,
    MULRadix: Int = 4,
    MULWidth: Int = 32,
    MULCompressorInputs: Int = 3,
    MULCompressorOutputs: Int = 2,
    MULCompressorOutputOffsets: IndexedSeq[Int] = IndexedSeq(0, 1),
    MULCompressorHasCin: Boolean = false,
    MULCompressorHasCout: Boolean = false,
    MULCompressorCoutOffset: Int = 1,
    MULCompressorAllowRedundant: Boolean = false,
    DIVRadix: Int = 2,
    DIVIterBits: Int = 1,
    DIVEarlyOut: Boolean = false,
    MULIterBits: Int = 1,
    MULPipeline: Int = 0,
    MULSplit: Int = 1,
    MULEarlyOut: Boolean = false,
    // 目标平台
    ResetAddr: Long = 0x30000000L,
    AddressWidth: Int = 32,
    // 缓存
    ICacheEnable: Boolean = true,
    // ICache数据阵列的存储介质：寄存器阵列或同步读SRAM
    ICacheStorage: ysyx_26030103_CacheStorage = ysyx_26030103_CacheStorage.Reg,
    DCacheEnable: Boolean = false,
    // DCache数据阵列的存储介质：寄存器阵列或同步读SRAM
    DCacheStorage: ysyx_26030103_CacheStorage = ysyx_26030103_CacheStorage.Reg,
    BlockSizeLog2: Int = 4,
    IndexBits: Int = 5,
    // 每级缓存可单独配置几何。默认值与共享的 BlockSizeLog2/IndexBits 相同；
    // 通过 elaboration 环境变量构建时（ICACHE_*/DCACHE_* 未设置则跟随
    // CACHE_BLOCK_SIZE_LOG2/CACHE_INDEX_BITS），由 Elaborate 统一填充，
    // 既有构建脚本与测试因此完全兼容。
    ICacheBlockSizeLog2: Int = 4,
    ICacheIndexBits: Int = 5,
    DCacheBlockSizeLog2: Int = 4,
    DCacheIndexBits: Int = 5,
    // LSU 写缓冲项数(必须是2的幂, 环形队列按位回绕)
    WBufDepth: Int = 4,
    CacheableBase: Long = 0x80000000L,
    CacheableMask: Long = 0x80000000L,
    PMARegions: Seq[ysyx_26030103_PMARegion] =
      ysyx_26030103_PhysicalMemoryMap.SoC(HasChipLink = false),
    // 分支预测
    BTBBits: Int = 4,
    BTBWays: Int = 1,
    JalBTBBits: Int = 4,
    JalBTBWays: Int = 1,
    RASBits: Int = 4,
    // 仿真观测逻辑也是硬件配置：顶层端口 ABI 始终保持不变，但关闭相应
    // 软件功能后，端口会绑常量，内部动态读 mux/事件快照寄存器可被裁剪。
    // 默认打开是为了保持直接实例化本配置的单元测试兼容；生产 elaborator
    // 会根据 Kconfig 对每一项显式赋值。
    SDBEnable: Boolean = true,
    DiffTestEnable: Boolean = true,
    MTraceEnable: Boolean = true,
    ITraceEnable: Boolean = true,
    FTraceEnable: Boolean = true,
    IRingBufEnable: Boolean = true,
    PerfStatsEnable: Boolean = true,
    // 性能计数器细分：仅在PerfStatsEnable打开时有意义。关闭的组在顶层
    // 输出常量，未被读取的叶模块信号会被综合展平/常量传播优化掉，
    // 仿真侧也不再逐拍采样，从而同时节省面积与编译产物。
    PerfCounterBasic: Boolean = true,
    PerfCounterOpClass: Boolean = true,
    PerfCounterMDU: Boolean = true,
    PerfCounterIfuStall: Boolean = true,
    PerfCounterICache: Boolean = true,
    PerfCounterDCache: Boolean = true,
    PerfCounterExuStall: Boolean = true,
    PerfCounterLSU: Boolean = true,
    PerfCounterIduStall: Boolean = true,
    PerfCounterTrap: Boolean = true,
    // 单计数器选择：键名如"ifu_fetch"/"mdu_req"。实际生效集合还要与
    // 主开关及所属组总开关相与（见EnabledPerfCounters）。
    PerfCounters: Set[String] =
      ysyx_26030103_PerfCounterCatalog.AllPerfCounters,
    // MMU/TLB 配置；当前已接入标准 Sv32 的 IFU/LSU 翻译路径。
    MMU: ysyx_26030103_MMUProfile = ysyx_26030103_MMUProfile()
) {
  require(XLEN == 32 || XLEN == 64,
    s"XLEN 只允许 32 或 64，收到 XLEN=$XLEN")
  require(!UseA, "RISCV_A 还没有做")
  require(!UseC, "RISCV_C 还没做")
  // 第一阶段 RV64 只实现无 MMU 的整数/乘除法核。MMU 的页表格式、satp
  // 和 Sv39 地址翻译属于后续阶段，这里必须在 elaboration 期明确拒绝，
  // 不能生成一个“看起来能跑”但不满足 RV64 虚存语义的核。
  require(XLEN == 32 || !MMU.enabled,
    "XLEN=64 第一阶段暂不支持 MMU/Sv39；请设置 HAS_MMU=n")
  // RV64 下 LD/SD 需要一次访问 8 字节。当前 LSU/AXI 数据通路固定 32 位，
  // 通过两拍窄传输实现；DCache 尚未适配 64 位访问，因此暂不允许同时开启。
  require(XLEN == 32 || !DCacheEnable,
    "XLEN=64 第一阶段暂不支持 DCache；请设置 DCACHE=n")
  require(XLEN == 32 || !MMU.cacheMode.equals("vipt") || !ICacheEnable,
    "XLEN=64 第一阶段 ICache 只支持物理地址索引")
  require(
    AddressWidth == 32,
    "当前RV32核的PC/PMA/流水线消息固定为32位；暂不支持非32位AddressWidth"
  )
  require(BlockSizeLog2 >= 2, "Cache block必须至少容纳1个32位字")
  require(IndexBits >= 1, "Cache必须至少包含2个set")
  require(ICacheBlockSizeLog2 >= 2, "ICache block必须至少容纳1个32位字")
  require(ICacheIndexBits >= 1, "ICache必须至少包含2个set")
  require(
    ICacheIndexBits + ICacheBlockSizeLog2 < AddressWidth,
    "ICache索引位+块内偏移必须小于地址宽度"
  )
  require(DCacheBlockSizeLog2 >= 2, "DCache block必须至少容纳1个32位字")
  require(DCacheIndexBits >= 1, "DCache必须至少包含2个set")
  require(
    DCacheIndexBits + DCacheBlockSizeLog2 < AddressWidth,
    "DCache索引位+块内偏移必须小于地址宽度"
  )
  require(
    IndexBits + BlockSizeLog2 < AddressWidth,
    "Cache地址几何必须留出至少1位tag"
  )
  // VIPT 合法性：VA 选中的 index+块内偏移必须完全落在页内，才能保证
  // 不同虚拟页映射到不同物理页时不会在 Cache 中错误 alias。若
  // indexBits+blockSizeLog2 > pageBits，index 会包含页号位，而物理 tag
  // 尚未到达前无法区分同 VA index 的不同物理页，必须显式拒绝。
  val viptCacheEnabled = MMU.enabled && MMU.cacheMode == "vipt"
  require(
    !viptCacheEnabled || !ICacheEnable ||
      ICacheIndexBits + ICacheBlockSizeLog2 <= MMU.pageBits,
    s"VIPT ICache 非法：ICacheIndexBits($ICacheIndexBits)+" +
      s"ICacheBlockSizeLog2($ICacheBlockSizeLog2) 超过页内偏移位数" +
      s"(${MMU.pageBits})；请增大 MMU_PAGE_SIZE_BYTES 或减小 Cache 索引/块大小"
  )
  require(
    !viptCacheEnabled || !DCacheEnable ||
      DCacheIndexBits + DCacheBlockSizeLog2 <= MMU.pageBits,
    s"VIPT DCache 非法：DCacheIndexBits($DCacheIndexBits)+" +
      s"DCacheBlockSizeLog2($DCacheBlockSizeLog2) 超过页内偏移位数" +
      s"(${MMU.pageBits})；请增大 MMU_PAGE_SIZE_BYTES 或减小 Cache 索引/块大小"
  )
  require(BTBBits >= 0, "BTBBits不能为负数；0表示单组BTB")
  require(
    BTBBits < AddressWidth - 2,
    "BTB地址几何必须在去除PC对齐位后留出至少1位tag"
  )
  require(BTBWays >= 1, "BTB必须至少包含1路")
  require(JalBTBBits >= 0, "JalBTBBits不能为负数；0表示单组JAL BTB")
  require(
    JalBTBBits < AddressWidth - 2,
    "JAL BTB地址几何必须在去除PC对齐位后留出至少1位tag"
  )
  require(JalBTBWays >= 1, "JAL BTB必须至少包含1路")
  require(
    RASBits >= 0 && RASBits < 31,
    "RASBits必须在0到30之间；0表示单项RAS"
  )
  require((ResetAddr & 0x3L) == 0L, "未启用RISCV_C时ResetAddr必须按4字节对齐")
  require(PMARegions.nonEmpty, "物理地址图不能为空")
  require(
    PMARegions.exists(r => ResetAddr >= r.Base && ResetAddr < r.EndExclusive && r.Executable),
    "ResetAddr必须位于可执行PMA区域"
  )
  require(
    Integer.bitCount(WBufDepth) == 1,
    "WBufDepth必须是2的幂(1/2/4/8/16)"
  )
  require(
    ysyx_26030103_MULBoothConfig.IsValidRadix(MULRadix),
    "MULRadix必须是大于等于2的2的幂"
  )
  require(
    MULWidth >= 32 && Integer.bitCount(MULWidth) == 1,
    "MULWidth必须是不小于32的2的幂(32/64/128/... )"
  )
  // 乘法器内部操作数宽度至少覆盖架构 XLEN。RV32 时与 MULWidth 完全一致；
  // RV64 默认 MULWidth=32 会自动提升到 64，保证 MUL/MULH* 的 2*XLEN 乘积。
  final val MULEffectiveWidth: Int = math.max(XLEN, MULWidth)
  require(
    MULCompressorInputs >= 3 && MULCompressorInputs <= 64,
    "MULCompressorInputs必须在3到64之间"
  )
  final val MULCompressorConfig = ysyx_26030103_MULGPCConfig(
    Inputs = MULCompressorInputs,
    Outputs = MULCompressorOutputs,
    OutputOffsets = MULCompressorOutputOffsets,
    HasCin = MULCompressorHasCin,
    HasCout = MULCompressorHasCout,
    CoutOffset = MULCompressorCoutOffset,
    AllowRedundant = MULCompressorAllowRedundant
  )
  require(
    ysyx_26030103_MULBoothConfig.IsValidRadix(DIVRadix) && DIVRadix <= 16,
    "DIVRadix必须是2/4/8/16之一，避免恢复余数除法器发生不可综合的静态展开"
  )
  require(DIVIterBits >= 1 && DIVIterBits <= 4, "DIVIterBits必须在1到4之间")
  require(MULIterBits >= 1 && MULIterBits <= 8, "MULIterBits必须在1到8之间")
  require(MULPipeline >= 0 && MULPipeline <= 4, "MULPipeline必须在0到4之间")
  require(MULSplit >= 1 && MULSplit <= 4, "MULSplit必须在1到4之间")
  // 压缩树核心包含 MULPipeline+1 个弹性结果槽。迭代移位加法核心始终只允许
  // 一笔在途请求，不受这个对它并不适用的设置影响。MDU 还会增加一个独立的
  // DIV 槽位。
  final val MULMaxInflight: Int = MULImpl match {
    case ysyx_26030103_MULImpl.ShiftAdd => 1
    case _                              => MULPipeline + 1
  }
  final val MDUMaxInflight: Int = MULMaxInflight + 1
  final val EnableDynamicGPRDebug: Boolean = SDBEnable || DiffTestEnable
  final val EnableRetireTrace: Boolean =
    SDBEnable || DiffTestEnable || MTraceEnable || ITraceEnable ||
      FTraceEnable || IRingBufEnable || PerfStatsEnable
  // 性能统计也从注册后的 trap 事件计数，不能只保留 retire 事件。
  final val EnableTrapTrace: Boolean =
    SDBEnable || DiffTestEnable || PerfStatsEnable
  final val EnableCSRTrace: Boolean = DiffTestEnable
  final val EnableMemoryTrace: Boolean =
    SDBEnable || DiffTestEnable || MTraceEnable
  final val EnablePerfTrace: Boolean = PerfStatsEnable
  // 性能计数器分组总开关。
  private val PerfCounterGroupFlags: Map[String, Boolean] = Map(
    "basic" -> PerfCounterBasic,
    "opclass" -> PerfCounterOpClass,
    "mdu" -> PerfCounterMDU,
    "ifu_stall" -> PerfCounterIfuStall,
    "icache" -> PerfCounterICache,
    "dcache" -> PerfCounterDCache,
    "exu_stall" -> PerfCounterExuStall,
    "lsu" -> PerfCounterLSU,
    "idu_stall" -> PerfCounterIduStall,
    "trap" -> PerfCounterTrap
  )
  // 单计数器实际生效集合 = 主开关 ∩ 组总开关 ∩ 单计数器开关。
  final val EnabledPerfCounters: Set[String] = {
    if (!PerfStatsEnable) { Set.empty }
    else {
      ysyx_26030103_PerfCounterCatalog.PerfCounterGroups.flatMap {
        case (group, counters) =>
          if (PerfCounterGroupFlags(group)) {
            counters.filter(PerfCounters.contains(_))
          } else { Seq.empty }
      }.toSet
    }
  }
  final def EnablePerfCounter(Key: String): Boolean =
    EnabledPerfCounters.contains(Key)
  // 事件分类码与执行活跃电平是多个单计数器的公共信号：消费方任一开启
  // 就生成，否则绑常量以便综合裁剪。
  final val EnablePerfEventKind: Boolean = Seq(
    "alu_op",
    "mem_op",
    "csr_op",
    "branch_op",
    "jal_op",
    "jalr_op"
  ).exists(EnablePerfCounter)
  final val EnablePerfExecutionActive: Boolean =
    EnablePerfCounter("execution_active") ||
      Seq("alu_op", "mem_op", "csr_op", "branch_op").exists(EnablePerfCounter)
  final val EnableAnyObservability: Boolean = EnableRetireTrace
  private def OnOff(Flag: Boolean): String = {
    if (Flag) { "on" }
    else { "off" }
  }
  def ISAString: String = {
    val MExt = if (UseM) { "M" }
    else { "" }
    val AExt = if (UseA) { "A" }
    else { "" }
    val CExt = if (UseC) { "C" }
    else { "" }
    "RV" + XLEN.toString + "I" + MExt + AExt + CExt
  }
  private def PerfCounterList: String = {
    if (!PerfStatsEnable) { "off" }
    else {
      val EnabledGroups =
        ysyx_26030103_PerfCounterCatalog.PerfCounterGroups.collect {
          case (group, _) if PerfCounterGroupFlags(group) => group
        }
      val GroupText =
        if (EnabledGroups.isEmpty) { "none" }
        else { EnabledGroups.mkString("+") }
      s"$GroupText(${EnabledPerfCounters.size}/${ysyx_26030103_PerfCounterCatalog.AllPerfCounters.size})"
    }
  }
  def Describe: String = {
    val ISA = ISAString
    val MOff = OnOff(UseM)
    val AOff = OnOff(UseA)
    val COff = OnOff(UseC)
    val ICacheDesc =
      if (ICacheEnable) {
        s"${1 << ICacheBlockSizeLog2}B/${1 << ICacheIndexBits}sets"
      } else { "off" }
    val DCacheDesc =
      if (DCacheEnable) {
        s"${1 << DCacheBlockSizeLog2}B/${1 << DCacheIndexBits}sets"
      } else { "off" }
    s"ISA=$ISA, M=$MOff, A=$AOff, C=$COff, " +
      s"mul=${MULImpl.Name}/${MULEncoding.Name}, " +
      s"mul_width=$MULWidth, mul_gpc=${MULCompressorConfig.Describe}, mul_radix=$MULRadix, " +
      s"div=${DIVImpl.Name}, div_radix=$DIVRadix, div_iter=$DIVIterBits, " +
      s"iter=$MULIterBits, pipeline=$MULPipeline, split=$MULSplit, " +
      s"mul_early_out=${OnOff(MULEarlyOut)}, div_early_out=${OnOff(DIVEarlyOut)}, " +
      s"ICache=$ICacheDesc, DCache=$DCacheDesc, icache_storage=${ICacheStorage.Name}, dcache_storage=${DCacheStorage.Name}, WBuf=$WBufDepth, " +
      s"BTB=${1 << BTBBits}x$BTBWays, JalBTB=${1 << JalBTBBits}x$JalBTBWays, " +
      s"RAS=${1 << RASBits}, observe=${OnOff(EnableAnyObservability)}, " +
      s"gpr_debug=${OnOff(EnableDynamicGPRDebug)}, perf_debug=${OnOff(EnablePerfTrace)}, " +
      s"perf_counters=${PerfCounterList}, " +
      s"mmu=${MMU.describe}, " +
      s"reset=0x${ResetAddr.toHexString}"
  }
}
