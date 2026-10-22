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
    // 架构/总线物理地址宽度。RV32 保持 32；RV64 由 elaborator 设为 64。
    // 该宽度同时用于 PC/分支目标、IFU/LSU、AXI、PMA/Xbar、Cache tag 与
    // BTB/RAS，避免任何一级再截断到 32 位。
    AddressWidth: Int = 32,
    // ysyxSoC CPU 侧接口的 XLEN/数据宽度，与 CPU XLEN 解耦：
    //   SocXLENFollowCpu=false（默认）时使用 SocXLEN；
    //   SocXLENFollowCpu=true 时 EffectiveSocXLEN=XLEN。
    // 32 位 SoC 可以搭配 64 位 CPU（访问窗口仍限制在低 4GiB）；
    // 64 位 SoC 的 AXI 桥接尚未接入 RTL，配置值先保留给后续任务。
    SocXLENFollowCpu: Boolean = false,
    SocXLEN: Int = 32,
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
    // MMU/TLB 配置；RV32 为 Sv32，RV64 为标准 Sv39/Sv48 的 IFU/LSU 翻译路径。
    MMU: ysyx_26030103_MMUProfile = ysyx_26030103_MMUProfile()
) {
  require(XLEN == 32 || XLEN == 64,
    s"XLEN 只允许 32 或 64，收到 XLEN=$XLEN")
  require(UseA == false, "RISCV_A 还没有做")
  require(UseC == false, "RISCV_C 还没做")
  // 物理地址宽度不能超过架构 XLEN；RV32 使用 32，RV64 必须使用 64，
  // 保证 PC/分支目标/LSU/IFU/AXI 全链路都不会再次退化为 32 位。
  require(AddressWidth == 32 || AddressWidth == 64,
    s"AddressWidth 只允许 32 或 64，收到 AddressWidth=$AddressWidth")
  require(XLEN == 32 || AddressWidth == XLEN,
    s"XLEN=64 的架构地址路径必须使用 64 位 AddressWidth，收到 $AddressWidth")
  // ysyxSoC 接口位宽独立于 CPU XLEN。FOLLOW_CPU=y 时 SOC_XLEN 只是
  // 保留值，不参与生效值计算；FOLLOW_CPU=n 时才要求它是 32 或 64。
  require(SocXLENFollowCpu || SocXLEN == 32 || SocXLEN == 64,
    "SOC_XLEN 只允许 32 或 64（SOC_XLEN_FOLLOW_CPU=n 时生效），" +
      s"收到 SOC_XLEN=$SocXLEN")
  /** ysyxSoC CPU 侧接口实际生效的 XLEN/地址/数据宽度。 */
  final val EffectiveSocXLEN: Int = if (SocXLENFollowCpu) XLEN else SocXLEN
  // SoC64 的 AXI 端口是 64 位地址/数据，NPC 内部 SoC 总线仍是 32 位
  // 数据 beat，因此必须经过 AXI5 宽度桥；SoC32 时数据通道同宽，但
  // RV64 内部地址仍需要 64->32 边界检查（由同一个桥完成）。
  final val SocXLENNeedsAxiBridge: Boolean = EffectiveSocXLEN != 32
  // RV64 MMU 默认接入标准 Sv39/Sv48；显式 CUSTOM 页几何由 MMUProfile
  // 自身完成参数/非标准 satp MODE 校验。这里只检查 profile 与顶层
  // XLEN/AddressWidth 一致，避免 RV64 核例化出 RV32 satp 语义，或
  // RV32 核误用 64 位页表。
  require(!MMU.enabled || MMU.xlen == XLEN,
    s"MMU profile XLEN(${MMU.xlen}) 与 NPC XLEN($XLEN) 不一致")
  require(XLEN == 32 || !MMU.enabled || AddressWidth == 64,
    "XLEN=64 HAS_MMU=y 要求 AddressWidth=64")
  require(XLEN == 32 || !MMU.enabled ||
      MMU.customGeometry || MMU.standardSv39 || MMU.standardSv48,
    "XLEN=64 HAS_MMU=y 只支持标准 Sv39/Sv48，或显式 MMU_GEOMETRY_MODE=custom")
  require(XLEN == 32 || !MMU.enabled || !MMU.customGeometry || MMU.customSatpModeLegal,
    "XLEN=64 CUSTOM MMU 必须使用 satp MODE 10..15，不能复用标准 8/9")
  // RV64 的 LD/SD 是 8 字节访问。LSU 在 32 位 AXI 数据通道上按两拍
  // 窄传输完成；DCache 内部仍以 32 位 word 存储，但一次 load/store
  // 可以跨两个相邻 word，因此数据行必须至少容纳两个 word。
  require(XLEN == 32 || !DCacheEnable || DCacheBlockSizeLog2 >= 3,
    "XLEN=64 且启用 DCache 时，cache line 必须至少容纳两个 32 位 word")
  // VIPT 的页内约束按当前 MMU 页大小检查；RV32 自定义几何和 RV64
  // Sv39/Sv48 的 pageBits 都从这里取。
  val viptCacheEnabled = MMU.enabled && MMU.cacheMode == "vipt"
  // VIPT 用虚拟地址选 index，cache line 必须落在同一个虚拟页内：行按块
  // 大小对齐，只要块不大于页就不会跨页。跨页行的不同字节来自两个可能
  // 不连续的物理页，无法用单个物理行 refill，因此即使打开 alias 安全
  // 模式也必须拒绝，并明确提示改用 PIPT。
  // PIPT 的 index/refill 全部来自物理地址，行跨越虚拟页没有意义，允许
  // line > page；物理地址不会被截断到页内偏移。
  require(
    !viptCacheEnabled || !ICacheEnable ||
      ICacheBlockSizeLog2 <= MMU.pageBits,
    s"VIPT ICache 非法：cache line(${1 << ICacheBlockSizeLog2}B) 大于页大小" +
      s"(${1 << MMU.pageBits}B)，会跨越虚拟页；VIPT 无法用单个物理行回填。" +
      s"请改用 MMU_CACHE_MODE=pipt（PIPT 允许 line > page），或增大 " +
      s"MMU_PAGE_SIZE_BYTES / 减小 ICacheBlockSizeLog2" +
      s"（ICacheBlockSizeLog2($ICacheBlockSizeLog2), MMU_PAGE_SIZE_BYTES(${1 << MMU.pageBits})）"
  )
  require(
    !viptCacheEnabled || !DCacheEnable ||
      DCacheBlockSizeLog2 <= MMU.pageBits,
    s"VIPT DCache 非法：cache line(${1 << DCacheBlockSizeLog2}B) 大于页大小" +
      s"(${1 << MMU.pageBits}B)，会跨越虚拟页；VIPT 无法用单个物理行回填。" +
      s"请改用 MMU_CACHE_MODE=pipt（PIPT 允许 line > page），或增大 " +
      s"MMU_PAGE_SIZE_BYTES / 减小 DCacheBlockSizeLog2" +
      s"（DCacheBlockSizeLog2($DCacheBlockSizeLog2), MMU_PAGE_SIZE_BYTES(${1 << MMU.pageBits})）"
  )
  // index 含 VPN 位时，不同 VA 可把同一物理行映射到不同 set。默认仍按
  // 原策略拒绝；显式打开 MMU_CACHE_VIPT_ALIAS_SAFE 后由 Cache 在 refill
  // 安装和 store 完成时扫描 tag 阵列，失效同物理行的其他 set 副本。
  private val ICacheAliasGeometry =
    ICacheIndexBits + ICacheBlockSizeLog2 > MMU.pageBits
  private val DCacheAliasGeometry =
    DCacheIndexBits + DCacheBlockSizeLog2 > MMU.pageBits
  require(
    !viptCacheEnabled || !ICacheEnable || !ICacheAliasGeometry ||
      MMU.cacheViptAliasSafe,
    s"VIPT ICache 非法：ICacheIndexBits($ICacheIndexBits)+" +
      s"ICacheBlockSizeLog2($ICacheBlockSizeLog2) 超过页内偏移位数" +
      s"(${MMU.pageBits})，同一物理行可能出现在不同 set。请显式设置 " +
      "MMU_CACHE_VIPT_ALIAS_SAFE=y 启用物理行 alias 失效，或增大 " +
      "MMU_PAGE_SIZE_BYTES / 减小 Cache 索引/块大小，或改用 MMU_CACHE_MODE=pipt"
  )
  require(
    !viptCacheEnabled || !DCacheEnable || !DCacheAliasGeometry ||
      MMU.cacheViptAliasSafe,
    s"VIPT DCache 非法：DCacheIndexBits($DCacheIndexBits)+" +
      s"DCacheBlockSizeLog2($DCacheBlockSizeLog2) 超过页内偏移位数" +
      s"(${MMU.pageBits})，同一物理行可能出现在不同 set。请显式设置 " +
      "MMU_CACHE_VIPT_ALIAS_SAFE=y 启用物理行 alias 失效，或增大 " +
      "MMU_PAGE_SIZE_BYTES / 减小 Cache 索引/块大小，或改用 MMU_CACHE_MODE=pipt"
  )
  // 只有几何真的会 alias 时才生成扫描/失效硬件；页内安全几何即使打开
  // 选项也不需要额外逻辑（同物理行必然同 index）。
  final val ICacheViptAliasHardware: Boolean =
    viptCacheEnabled && ICacheEnable && MMU.cacheViptAliasSafe &&
      ICacheAliasGeometry
  final val DCacheViptAliasHardware: Boolean =
    viptCacheEnabled && DCacheEnable && MMU.cacheViptAliasSafe &&
      DCacheAliasGeometry
  // alias 扫描方式只对真正生成 alias 维护硬件的 Cache 生效；all_sets
  // 保持原有时序，serial 每周期扫描一个 set 并反压新请求。
  final val ICacheViptAliasScanMode: String = MMU.cacheViptAliasScanMode
  final val DCacheViptAliasScanMode: String = MMU.cacheViptAliasScanMode
  final val ICacheLineExceedsPage: Boolean =
    ICacheEnable && ICacheBlockSizeLog2 > MMU.pageBits
  final val DCacheLineExceedsPage: Boolean =
    DCacheEnable && DCacheBlockSizeLog2 > MMU.pageBits
  // PIPT 下 line > page 是受支持配置：索引与 refill 都使用物理地址。
  final val PiptCacheLineExceedsPage: Boolean =
    MMU.enabled && MMU.cacheMode == "pipt" &&
      (ICacheLineExceedsPage || DCacheLineExceedsPage)
  // 只有外部 SoC AXI 实际使用 32 位地址时才要求 PMA 完全位于低
  // 4GiB。SoC64 允许 PMA/Xbar 映射更高物理地址；这些地址会完整送到
  // 64 位 AXI 边界，由下游（或 Xbar 未命中 DECERR）决定行为，绝不
  // 在 NPC 顶层被截断。SoC32 时任何高位地址都必须在 Xbar 内变成
  // DECERR，桥在边界再做一次整笔 burst 越界检查作为兜底。
  require(
    EffectiveSocXLEN != 32 || AddressWidth <= 32 ||
      PMARegions.forall(r => BigInt(r.EndExclusive) <= (BigInt(1) << 32)),
    "EffectiveSocXLEN=32 时顶层 PMA 必须位于低 4GiB：" +
      "外部 SoC AXI 只有 32 位地址；更高地址应在 Xbar 内局部 DECERR"
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
    // VIPT alias 模式必须显式出现在 elaboration 日志中，避免出现
    // “配置看起来是 VIPT、实际却静默退化”的情况。
    val ViptAliasDesc =
      if (!viptCacheEnabled) { "" }
      else {
        val Active =
          if (ICacheViptAliasHardware || DCacheViptAliasHardware) { "(hw)" }
          else { "" }
        s",vipt_alias_safe=${OnOff(MMU.cacheViptAliasSafe)}$Active" +
          s",vipt_alias_scan=${MMU.cacheViptAliasScanMode}"
      }
    // PIPT 是唯一允许 line > page 的模式；把它显式打印出来，避免出现
    // “配置成大头行却按页内截断索引”的误解。
    val LargeLineDesc =
      if (!PiptCacheLineExceedsPage) { "" }
      else {
        val Which =
          Seq(
            if (ICacheLineExceedsPage) Some("ICache") else None,
            if (DCacheLineExceedsPage) Some("DCache") else None
          ).flatten.mkString("+")
        s",pipt_line_gt_page=$Which(${1 << MMU.pageBits}B-page)"
      }
    s"ISA=$ISA, M=$MOff, A=$AOff, C=$COff, " +
      s"addr_w=$AddressWidth, " +
      s"soc_xlen=$EffectiveSocXLEN${if (SocXLENFollowCpu) "(follow)" else ""}, " +
      s"mul=${MULImpl.Name}/${MULEncoding.Name}, " +
      s"mul_width=$MULWidth, mul_gpc=${MULCompressorConfig.Describe}, mul_radix=$MULRadix, " +
      s"div=${DIVImpl.Name}, div_radix=$DIVRadix, div_iter=$DIVIterBits, " +
      s"iter=$MULIterBits, pipeline=$MULPipeline, split=$MULSplit, " +
      s"mul_early_out=${OnOff(MULEarlyOut)}, div_early_out=${OnOff(DIVEarlyOut)}, " +
      s"ICache=$ICacheDesc, DCache=$DCacheDesc$ViptAliasDesc$LargeLineDesc, " +
      s"icache_storage=${ICacheStorage.Name}, dcache_storage=${DCacheStorage.Name}, WBuf=$WBufDepth, " +
      s"BTB=${1 << BTBBits}x$BTBWays, JalBTB=${1 << JalBTBBits}x$JalBTBWays, " +
      s"RAS=${1 << RASBits}, observe=${OnOff(EnableAnyObservability)}, " +
      s"gpr_debug=${OnOff(EnableDynamicGPRDebug)}, perf_debug=${OnOff(EnablePerfTrace)}, " +
      s"perf_counters=${PerfCounterList}, " +
      s"mmu=${MMU.describe}, " +
      s"reset=0x${ResetAddr.toHexString}"
  }
}
