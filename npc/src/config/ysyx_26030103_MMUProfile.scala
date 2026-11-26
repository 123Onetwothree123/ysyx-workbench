package ysyx_26030103.config

import _root_.ysyx_26030103.common.{ysyx_26030103_KconfigDefaults => KconfigDefaults}
import _root_.ysyx_26030103.common.ysyx_26030103_PhysicalMemoryMap
import _root_.ysyx_26030103.common.ysyx_26030103_PMUCSR

/** PTE 字段位置；默认就是标准 RISC-V 32-bit PTE。
  *
  * PTEBits 是 PTE 位宽（Sv32=32，Sv39/Sv48=64）；64 位 PTE 的 PPN 扩展到
  * bit 53（标准 44 位 PPN），自定义编码仍可按位配置。
  */
case class ysyx_26030103_PTEConfig(
    PTEBits: Int = KconfigDefaults.MMUPTEBits,
    validBit: Int = KconfigDefaults.MMUPTEValidBit,
    readBit: Int = KconfigDefaults.MMUPTEReadBit,
    writeBit: Int = KconfigDefaults.MMUPTEWriteBit,
    executeBit: Int = KconfigDefaults.MMUPTEExecBit,
    userBit: Int = KconfigDefaults.MMUPTEUserBit,
    globalBit: Int = KconfigDefaults.MMUPTEGlobalBit,
    aBit: Int = KconfigDefaults.MMUPTEABit,
    dBit: Int = KconfigDefaults.MMUPTEDBit,
    PPNLow: Int = KconfigDefaults.MMUPTEPPNLow,
    PPNHigh: Int = KconfigDefaults.MMUPTEPPNHigh
) {
  require(PTEBits == 32 || PTEBits == 64, s"PTE 位宽只允许 32/64，收到 $PTEBits")
  require(PPNLow >= 8 && PPNHigh < PTEBits && PPNLow <= PPNHigh,
    "PTE PPN 位范围非法（至少为 8，且必须低于 PTE 位宽）")
  private val flagBits = Seq(validBit, readBit, writeBit, executeBit,
    userBit, globalBit, aBit, dBit)
  require(flagBits.forall(x => x >= 0 && x < PTEBits), "PTE 标志位非法")
  // 归一化后的内部 PTE 固定把 8 个标志放在低 8 位。字段位置可以任意，
  // 但标志之间不能重叠，也不能落进 PPN 字段，否则同一个 PTE 位会被
  // 同时解释成两种语义。无法正确表达的编码必须在 elaboration 期拒绝。
  require(flagBits.distinct.size == flagBits.size, "PTE 标志位不能重复")
  require(flagBits.forall(b => b < PPNLow || b > PPNHigh),
    s"PTE 标志位不能与 PPN 字段 [$PPNHigh:$PPNLow] 重叠")
  // PPN 高位以上的位统一按保留位检查；标志位若放在该区域会和保留位
  // 语义冲突，无法用当前归一化/保留位规则正确表达。
  require(flagBits.forall(_ <= PPNHigh),
    s"PTE 标志位必须位于 PPN 高位($PPNHigh)以下，不能占用保留位区域")
}

/** MMU/TLB 的 elaboration 期参数，由 Elaborate 从 Kconfig 环境变量构造。
  *
  * 默认是标准 Sv32；RV64 默认标准 Sv39（3 级/8B PTE）或 Sv48（4 级/8B PTE）。
  * RV64 的非标准页几何只能显式 `geometryMode = "custom"`，并用 non-standard
  * satp MODE 10..15，绝不伪装成标准 Sv39/Sv48；RV32 既有自定义几何保持兼容。
  */
case class ysyx_26030103_MMUProfile(
    enabled: Boolean = false,
    // MMU 所属的架构位宽。RV32 默认 32；RV64/Sv39 由 elaborator 设置为 64。
    xlen: Int = KconfigDefaults.MMUXlen,
    // RV64 页几何模式：standard 只接受标准 Sv39/Sv48；custom 显式启用
    // 非标准页大小/级数/PTE 编码，并要求 satp MODE 使用 10..15 的扩展值。
    // RV32 不接受该字段的额外限制，既有的自定义 32 位几何保持不变。
    geometryMode: String = "standard",
    // CUSTOM 模式专用的 satp MODE。8/9 是标准 Sv39/Sv48，1 是 Sv32，
    // 都不能被非标准几何复用，因此 CUSTOM 只允许保留编码 10..15。
    customSatpMode: Int = KconfigDefaults.MMUCustomSatpMode,
    pageSizeBytes: Int = KconfigDefaults.MMUPageSizeBytes,
    levels: Int = KconfigDefaults.MMULevels,
    PTESizeBytes: Int = KconfigDefaults.MMUPTESizeBytes,
    pageTableSizeBytes: Int = KconfigDefaults.MMUPageTableSizeBytes,
    PTEFormat: String = "riscv32",
    PTEConfig: ysyx_26030103_PTEConfig = ysyx_26030103_PTEConfig(),
    superpage: Boolean = false,
    adMode: String = "fault",
    cacheMode: String = "pipt",
    // VIPT alias 安全模式：允许 cache index/块偏移越过页内偏移（即 index
    // 含 VPN 位）。此时同一物理行可能被不同 VA 映射到不同 set，Cache 在
    // refill/store 时按物理 tag 清除其他 set 的别名副本。false 保持原有
    // elaboration 期拒绝策略，绝不静默生成会 alias 的硬件。
    cacheVIPTAliasSafe: Boolean = false,
    // alias 失效扫描方式：all_sets 单拍全扫描（默认，refill 无额外等待）；
    // serial 每周期扫描一个 set；parallel_n 每周期扫描 N 个 set；
    // directory 维护物理行 -> set 目录。只有 cacheViptAliasSafe 真正生成
    // alias 维护硬件时才影响 RTL。
    cacheVIPTAliasScanMode: String = "all_sets",
    // parallel_n 的每周期扫描宽度（2..1024）。超过实际 set 数时在该
    // Cache 内截断为 sets；Kconfig 默认 4，对应 8/16/32 set 的 2/4/8 拍。
    cacheVIPTAliasScanWidth: Int = KconfigDefaults.MMUCacheVIPTAliasScanWidth,
    // directory 的目录项数。0 表示跟随实际 set 数（每个 set 独占目录桶，
    // 无冲突）；显式值必须是 2 的幂且不超过 set 数。
    cacheVIPTAliasDirectoryEntries: Int = KconfigDefaults.MMUCacheVIPTAliasDirectoryEntries,
    // DCache alias 事件队列深度；队列满时清空整个 DCache。仅 DCache 使用。
    cacheVIPTAliasEventQueueDepth: Int =
      KconfigDefaults.MMUCacheVIPTAliasEventQueueDepth,
    PTWPath: String = "dedicated",
    PTWArch: String = "independent",
    PTWOutstanding: Int = KconfigDefaults.MMUPTWOutstanding,
    // 页表总线可同时保持的页表读/写数。0 表示跟随 PTWOutstanding；
    // 默认 1 保持原有的单笔总线行为，配置 >1 才启用总线级重叠。
    PTWBusOutstanding: Int = KconfigDefaults.MMUPTWBusOutstanding,
    // 被 PMP 拒绝的页表访问返回 fault 响应的队列深度。
    PTWPMPFaultQueueDepth: Int = KconfigDefaults.MMUPTWPMPFaultQueueDepth,
    PTWOrder: String = "in_order",
    PTWCache: Boolean = false,
    PTWCacheEntries: Int = KconfigDefaults.MMUPTWCacheEntries,
    PTWCacheWays: Int = KconfigDefaults.MMUPTWCacheWays,
    TLBEnabled: Boolean = false,
    TLBOrganization: String = "split",
    TLBStorage: String = "reg",
    TLBReplacement: String = "plru",
    TLBASIDBits: Int = KconfigDefaults.MMUTLBASIDBits,
    TLBGlobal: Boolean = true,
    itlbEntries: Int = KconfigDefaults.MMUItlbEntries,
    itlbWays: Int = KconfigDefaults.MMUItlbWays,
    dtlbEntries: Int = KconfigDefaults.MMUDtlbEntries,
    dtlbWays: Int = KconfigDefaults.MMUDtlbWays,
    utlbEntries: Int = KconfigDefaults.MMUUtlbEntries,
    utlbWays: Int = KconfigDefaults.MMUUtlbWays,
    utlbLookupPorts: Int = KconfigDefaults.MMUUtlbLookupPorts,
    TLBSuperpage: Boolean = false,
    TLBSuperpageEntries: Int = KconfigDefaults.MMUTLBSuperpageEntries,
    TLBMissQueueMode: String = "follow_tlb",
    itlbMissQueueEntries: Int = KconfigDefaults.MMUItlbMissQueueEntries,
    dtlbMissQueueEntries: Int = KconfigDefaults.MMUDtlbMissQueueEntries,
    utlbMissQueueEntries: Int = KconfigDefaults.MMUUtlbMissQueueEntries,
    globalMissQueueEntries: Int = KconfigDefaults.MMUGlobalMissQueueEntries,
    refillPorts: Int = KconfigDefaults.MMURefillPorts,
    CSRMstatusReadMask: Int = KconfigDefaults.MMUMstatusReadMask.toInt,
    CSRMstatusReadSet: Int = KconfigDefaults.MMUMstatusReadSet.toInt,
    CSRMstatusWriteMask: Int = KconfigDefaults.MMUMstatusWriteMask.toInt,
    // satp 在 RV64 下是 64 位 CSR，因此掩码使用 Long；RV32 只用低 32 位。
    CSRSatpReadMask: Long = KconfigDefaults.MMUSatpReadMask,
    CSRSatpReadSet: Long = KconfigDefaults.MMUSatpReadSet,
    CSRSatpWriteMask: Long = KconfigDefaults.MMUSatpWriteMask,
    hasS: Boolean = true,
    hasU: Boolean = true,
    HasSTimerIRQ: Boolean = true,
    HasSExternalIRQ: Boolean = true,
    timerPath: String = "clint_mtip",
    PLICSources: Int = KconfigDefaults.MMUPLICSources,
    PLICPriorityBits: Int = KconfigDefaults.MMUPLICPriorityBits,
    PLICMaxPriority: Int = KconfigDefaults.MMUPLICMaxPriority,
    PLICBase: Long = ysyx_26030103_PhysicalMemoryMap.DefaultPLICBase,
    PLICSize: Long = ysyx_26030103_PhysicalMemoryMap.DefaultPLICSize,
    CSRViewMode: String = "bound",
    CSRMissingMode: String = "illegal",
    PMPEnabled: Boolean = false,
    PMPEntries: Int = KconfigDefaults.MMUPMPEntries,
    // PMP 粒度 G：pmpaddr 低 G-2 位读回为 0，NAPOT 最小区域为 2^G 字节
    PMPGranularityLog2: Int = 6,
    CSREnabled: Set[Int] =
      ysyx_26030103_MMUProfile.DefaultCSRAddresses
) {
  // 标准 Sv32/Sv39/Sv48 必须接受非末级叶子 PTE
  def superpageSupported: Boolean =
    superpage || (geometryMode == "standard" && levels > 1)

  private def isPowerOfTwo(value: Int): Boolean =
    value > 0 && (value & (value - 1)) == 0

  private def requirePowerOfTwo(name: String, value: Int): Unit =
    require(isPowerOfTwo(value), s"$name=$value 必须是正的 2 的幂")

  require(xlen == 32 || xlen == 64, s"MMU XLEN 只允许 32/64，收到 $xlen")
  requirePowerOfTwo("pageSizeBytes", pageSizeBytes)
  requirePowerOfTwo("PTESizeBytes", PTESizeBytes)
  requirePowerOfTwo("pageTableSizeBytes", pageTableSizeBytes)
  require(PTESizeBytes == 4 || PTESizeBytes == 8, "PTE 大小只支持 4/8 字节")
  require(xlen == 32 || PTEConfig.PTEBits == PTESizeBytes * 8,
    s"RV64 PTE 字段配置位宽(${PTEConfig.PTEBits})必须等于 PTESizeBytes*8(${PTESizeBytes * 8})")
  require(adMode != "hardware" || PTESizeBytes <= 4 ||
    (PTEConfig.aBit < 32 && PTEConfig.dBit < 32),
    "64 位 PTE 的 A/D 硬件写回要求 A/D 位位于低 32 位字内（32 位页表写端口只更新低字）")
  // fillLevel/页表层级字段统一为 3 位，因此 RTL 最多能表达 8 级；
  // 更深的几何必须在 elaboration 期明确拒绝，不能截断层级编号。
  require(levels >= 1 && levels <= ysyx_26030103_MMUProfile.MaxLevels,
    s"levels=$levels 必须在 1..${ysyx_26030103_MMUProfile.MaxLevels}" +
      s"（fillLevel/层级字段为 ${ysyx_26030103_MMUProfile.LevelBits} 位）")
  require(
    pageTableSizeBytes % PTESizeBytes == 0,
    s"pageTableSizeBytes=$pageTableSizeBytes 必须能被 PTESizeBytes=$PTESizeBytes 整除"
  )
  requirePowerOfTwo("每级页表项数", pageTableSizeBytes / PTESizeBytes)
  require(pageTableSizeBytes / PTESizeBytes >= 2,
    s"每级页表项数(${pageTableSizeBytes / PTESizeBytes})必须至少为 2，否则索引位宽为 0")
  require(PTWOutstanding >= 1 && PTWOutstanding <= 16,
    s"PTWOutstanding=$PTWOutstanding 必须在 1..16（PTW 事务 ID 为 4 位）")
  require(PTWBusOutstanding >= 0 && PTWBusOutstanding <= 16,
    s"PTWBusOutstanding=$PTWBusOutstanding 必须在 0..16（0 表示跟随 PTWOutstanding）")
  require(PTWPMPFaultQueueDepth >= 1 && PTWPMPFaultQueueDepth <= 16,
    s"ptwPmpFaultQueueDepth=$PTWPMPFaultQueueDepth 必须在 1..16")
  require(Set("independent", "pipelined").contains(PTWArch),
    "MMU_PTW_ARCH 必须是 independent/pipelined")
  require(Set("in_order", "out_of_order").contains(PTWOrder),
    "MMU_PTW_ORDER 必须是 in_order/out_of_order")
  require(
    Set("all_sets", "serial", "parallel_n", "directory")
      .contains(cacheVIPTAliasScanMode),
    "MMU_CACHE_VIPT_ALIAS_SCAN_MODE 必须是 all_sets/serial/parallel_n/directory"
  )
  // parallel_n 的宽度在真正生成硬件的 Cache 内按 sets 截断；这里只校验
  // 配置值本身可实现，避免 Kconfig 的静态 range 与实际 sets 不一致。
  require(cacheVIPTAliasScanWidth == 0 ||
    (cacheVIPTAliasScanWidth >= 2 && cacheVIPTAliasScanWidth <= 1024),
    s"MMU_CACHE_VIPT_ALIAS_SCAN_WIDTH=$cacheVIPTAliasScanWidth 必须是 0 或 2..1024")
  require(cacheVIPTAliasDirectoryEntries >= 0 &&
    cacheVIPTAliasDirectoryEntries <= 1024,
    s"MMU_CACHE_VIPT_ALIAS_DIRECTORY_ENTRIES=$cacheVIPTAliasDirectoryEntries 必须在 0..1024")
  require(cacheVIPTAliasDirectoryEntries == 0 ||
    (cacheVIPTAliasDirectoryEntries & (cacheVIPTAliasDirectoryEntries - 1)) == 0,
    s"MMU_CACHE_VIPT_ALIAS_DIRECTORY_ENTRIES=$cacheVIPTAliasDirectoryEntries 必须是 2 的幂或 0")
  require(cacheVIPTAliasEventQueueDepth >= 2 &&
    cacheVIPTAliasEventQueueDepth <= 16 &&
    (cacheVIPTAliasEventQueueDepth &
      (cacheVIPTAliasEventQueueDepth - 1)) == 0,
    s"MMU_CACHE_VIPT_ALIAS_EVENT_QUEUE_DEPTH=$cacheVIPTAliasEventQueueDepth " +
      "必须是 2/4/8/16")
  require(TLBASIDBits >= 0 && TLBASIDBits <= 16, "tlbAsidBits 必须在 0..16")
  require(utlbLookupPorts >= 1 && utlbLookupPorts <= 2, "utlbLookupPorts 必须在 1..2")
  require(refillPorts >= 1, "refillPorts 必须 >= 1")
  // RISC-V 允许 M+U：没有 S 时 U 的陷阱直接进入 M，用 mepc/mcause/
  // mtval/mstatus.MPP 保存状态，MRET 按 MPP 返回 U。只有 MMU 必须依赖 S。
  // Sv32/Sv39/Sv48 页表翻译、satp、页错误入口和异常委托都属于 S 模式。
  // HAS_S=n 时必须显式关闭 MMU，而不是静默保留完整 S 逻辑。
  require(!enabled || hasS,
    "HAS_MMU=y 需要 HAS_S=y：MMU/Sv32/Sv39/Sv48 依赖 S 模式 satp/页错误入口；请设置 HAS_S=y 或 HAS_MMU=n")
  // source 0 保留，UART 固定使用 source 10，因此至少需要 11 个 source。
  require(PLICSources > 10 && PLICSources <= 1024,
    s"PLIC_SOURCES=$PLICSources 必须大于 10（UART source 10）且不超过 1024")
  require(PLICPriorityBits >= 1 && PLICPriorityBits <= 8, "PLIC 优先级位宽非法")
  require(PLICMaxPriority >= 0 && PLICMaxPriority < (1 << PLICPriorityBits),
    "PLIC_MAX_PRIORITY 超出优先级位宽")
  require(PLICBase >= 0 && PLICBase < (1L << 32), "PLIC_BASE 超出 32 位地址空间")
  // S 上下文 claim 寄存器在 0x201004，窗口必须至少覆盖到 0x202000。
  // 当前实现同时支持 0x200000 的 M 上下文别名和 0x201000 的 S 上下文。
  require(PLICSize >= 0x202000L,
    s"PLIC_SIZE=0x${PLICSize.toHexString} 必须至少为 0x202000 才能覆盖 S 上下文 claim 窗口")
  // 使用 4KiB 页粒度与 PMA/Xbar/DTB 对齐，避免同一窗口在不同组件里
  // 出现部分重叠或非页对齐的边界。
  require(PLICBase % 4096 == 0,
    s"PLIC_BASE=0x${PLICBase.toHexString} 必须 4KiB 对齐")
  require(PLICSize % 4096 == 0,
    s"PLIC_SIZE=0x${PLICSize.toHexString} 必须为 4KiB 的整数倍")
  require(PLICBase + PLICSize <= (1L << 32),
    "PLIC_BASE + PLIC_SIZE 超出 32 位地址空间")
  require(Set("clint_mtip", "direct_stip").contains(timerPath), "TIMER_PATH 必须是 clint_mtip/direct_stip")
  require(Set("bound", "independent").contains(CSRViewMode), "CSR_VIEW_MODE 必须是 bound/independent")
  require(Set("illegal", "zero").contains(CSRMissingMode), "CSR_MISSING_MODE 必须是 illegal/zero")
  require(PMPEntries >= 0 && PMPEntries <= 16, "PMP_ENTRIES 必须在 0..16")
  require(!PMPEnabled || PMPEntries > 0, "HAS_PMP=y 时 PMP_ENTRIES 必须 > 0")
  require(PMPGranularityLog2 >= 2 && PMPGranularityLog2 <= 30,
    s"PMP_GRANULARITY_LOG2=$PMPGranularityLog2 必须在 2..30")
  // PMP 不再依赖 MMU：无 MMU 时直接检查 IFU 物理取指地址和 LSU 的 VA==PA
  // 访存地址；有 MMU 时仍检查翻译后的物理地址。

  def csrSupported(address: Int): Boolean = {
    // CSR 地址位 [9:8] 编码最低特权级：00=U，01=S，11=M。
    // U 级不只是 0x000--0x0ff，还包括 0xc00 这类用户只读计数器。
    val CSRPrivilege = (address >> 8) & 0x3
    val uLevel = CSRPrivilege == 0
    val sLevel = CSRPrivilege == 1
    // medeleg/mideleg 是 M 级地址，但只控制不存在的 S 状态，HAS_S=n
    // 时从实现集合裁剪。mcounteren 仍是 M 级寄存器：M+U 下它门控 U
    // 访问用户计数器，因此只有连 U 都不存在时才裁剪。
    val sControl =
      address == 0x302 || address == 0x303 || (address == 0x306 && !hasU)
    // RV64 的计数器是完整 64 位；RV32 的高半字别名在 RV64 中未实现。
    val highHalfCounter =
      xlen == 64 && (address == 0xb80 || address == 0xb82 ||
        address == 0xc80 || address == 0xc81 || address == 0xc82)
    // HAS_U=n 时 U 级 CSR 一并从实现集合裁剪；HAS_S=n 时裁剪 S 级 CSR。
    // envcfg 中 menvcfg/menvcfgh/senvcfg 属于 S 模式扩展，M-only 时不暴露；
    // mstatush 是 RV32 的 M 级寄存器，不受 HAS_S 影响。
    val EnvcfgAddressRequiresS =
      address == 0x30a || address == 0x31a || address == 0x10a
    !highHalfCounter &&
      (!EnvcfgAddressRequiresS || hasS) &&
      ((hasU || !uLevel) && (hasS || (!sLevel && !sControl))) &&
      (CSREnabled.contains(address) ||
        ysyx_26030103_MMUProfile.EnvcfgFamilyCSRAddresses(xlen).contains(address) ||
        ysyx_26030103_MMUProfile.IdentityCSRAddresses.contains(address) ||
        // RV64只有pmpcfg0/pmpcfg2，0x3a1/0x3a3是RV32专用编码
        ysyx_26030103_MMUProfile.PMPAddresses(xlen, PMPEnabled).contains(address))
  }

  // 当前配置允许的最低特权级：有 U 为 U；否则有 S 为 S；M-only 为 M。
  final val lowestPrivilege: Int = if (hasU) 0 else if (hasS) 1 else 3

  require(Set("split", "unified").contains(TLBOrganization),
    "MMU_TLB_ORGANIZATION 必须是 split/unified")
  require(Set("follow_tlb", "global").contains(TLBMissQueueMode),
    "MMU_TLB_MISS_QUEUE_MODE 必须是 follow_tlb/global")
  if (TLBEnabled) {
    requirePowerOfTwo("itlbEntries", itlbEntries)
    requirePowerOfTwo("dtlbEntries", dtlbEntries)
    requirePowerOfTwo("utlbEntries", utlbEntries)
    require(itlbEntries % itlbWays == 0, "itlbEntries 必须能被 itlbWays 整除")
    require(dtlbEntries % dtlbWays == 0, "dtlbEntries 必须能被 dtlbWays 整除")
    require(utlbEntries % utlbWays == 0, "utlbEntries 必须能被 utlbWays 整除")
    // miss queue 是 TLB 缺失请求进入 PTW 前的真实缓冲；深度为 0 会让
    // 对应 TLB 的 miss 永远无法入队，必须在 elaboration 期拒绝。
    if (TLBMissQueueMode == "global") {
      require(globalMissQueueEntries >= 1,
        "MMU_GLOBAL_MISS_QUEUE_ENTRIES 必须 >= 1")
    } else if (TLBOrganization == "split") {
      require(itlbMissQueueEntries >= 1,
        "MMU_ITLB_MISS_QUEUE_ENTRIES 必须 >= 1")
      require(dtlbMissQueueEntries >= 1,
        "MMU_DTLB_MISS_QUEUE_ENTRIES 必须 >= 1")
    } else {
      require(utlbMissQueueEntries >= 1,
        "MMU_UTLB_MISS_QUEUE_ENTRIES 必须 >= 1")
    }
  }

  require(Set("standard", "custom").contains(geometryMode),
    s"MMU_GEOMETRY_MODE 必须是 standard/custom，收到 $geometryMode")
  // CUSTOM 是 RV64 的显式扩展；RV32 的 satp MODE 只有 1 位（Sv32=1），
  // 无法表达独立的非标准模式，因此既有的 32 位自定义几何继续走
  // standard 路径，而不是假装成一个新的 satp 模式。
  require(xlen == 64 || geometryMode != "custom",
    "MMU_GEOMETRY_MODE=custom 只用于 RV64；RV32 既有自定义几何保持 Sv32 模式")

  final val pageBits: Int = Integer.numberOfTrailingZeros(pageSizeBytes)
  final val tableEntries: Int = pageTableSizeBytes / PTESizeBytes
  final val indexBitsPerLevel: Int = Integer.numberOfTrailingZeros(tableEntries)
  final val computedAddressBits: Int = pageBits + levels * indexBitsPerLevel
  // 标准 PTE 字段布局：低 8 位标志，PPN 从 bit10 开始。显式 CUSTOM
  // 模式也允许该布局，但若把 `PTEFormat` 写成 riscv32/riscv64，字段位置
  // 必须与对应标准完全一致，避免“名字是标准、编码却是私有”的伪装。
  final val StandardPTELayout32: Boolean =
    PTEConfig.PTEBits == 32 && PTEConfig.validBit == 0 &&
      PTEConfig.readBit == 1 && PTEConfig.writeBit == 2 &&
      PTEConfig.executeBit == 3 && PTEConfig.userBit == 4 &&
      PTEConfig.globalBit == 5 && PTEConfig.aBit == 6 &&
      PTEConfig.dBit == 7 && PTEConfig.PPNLow == 10 && PTEConfig.PPNHigh == 31
  final val StandardPTELayout64: Boolean =
    PTEConfig.PTEBits == 64 && PTEConfig.validBit == 0 &&
      PTEConfig.readBit == 1 && PTEConfig.writeBit == 2 &&
      PTEConfig.executeBit == 3 && PTEConfig.userBit == 4 &&
      PTEConfig.globalBit == 5 && PTEConfig.aBit == 6 &&
      PTEConfig.dBit == 7 && PTEConfig.PPNLow == 10 && PTEConfig.PPNHigh == 53
  final val standardSv39: Boolean =
    xlen == 64 && pageSizeBytes == 4096 && levels == 3 && PTESizeBytes == 8 &&
      pageTableSizeBytes == 4096 && PTEFormat == "riscv64" &&
      StandardPTELayout64 && computedAddressBits == 39
  final val standardSv48: Boolean =
    xlen == 64 && pageSizeBytes == 4096 && levels == 4 && PTESizeBytes == 8 &&
      pageTableSizeBytes == 4096 && PTEFormat == "riscv64" &&
      StandardPTELayout64 && computedAddressBits == 48
  // CUSTOM 只改页大小/级数/索引/PTE 宽度与字段位置；虚拟地址仍必须在
  // XLEN 内可表示，且 PPN+页内偏移不能超过 64 位物理地址。
  final val customGeometry: Boolean = xlen == 64 && geometryMode == "custom"
  // 显式标记当前 profile 使用非标准 RV64 页几何。
  final val nonStandard: Boolean = customGeometry
  final val customSatpModeLegal: Boolean =
    customSatpMode >= 10 && customSatpMode <= 15
  final val addressBitsWithinXlen: Boolean =
    computedAddressBits >= 2 && computedAddressBits <= xlen
  final val PTEPPNBits: Int = PTEConfig.PPNHigh - PTEConfig.PPNLow + 1
  // PTE PPN 字段可以比物理地址空间更宽。例如 2MiB 页(pageBits=21) 配
  // 标准 44 位 PPN 时，PPN<<21 需要 65 位；64 位物理地址只能容纳低
  // 43 位 PPN，其余高位必须为 0，由 PTW 在 walk 时按保留位检查并报
  // page fault，绝不能静默截断。satp.PPN 同理：根表基址 =
  // satp.PPN << pageBits，超出物理地址宽度的高位不可用。
  final val PaddrPPNBits: Int = math.min(PTEPPNBits, xlen - pageBits)
  final val SatpPPNBits: Int = math.min(44, xlen - pageBits)
  final val customPageFits: Boolean =
    pageBits < computedAddressBits && PaddrPPNBits >= 1 && SatpPPNBits >= 1
  // Sv32 的虚拟地址是 32 位；Sv39/Sv48 分别使用 39/48 位有效虚拟地址，
  // 其余高位必须是指示位的符号扩展（由 PTW/MMU 检查）。CUSTOM 模式
  // 允许任意 2..XLEN 位的有效 VA，只要高位仍按符号扩展检查。
  final val wellFormed: Boolean =
    if (xlen == 32) computedAddressBits == 32
    else if (customGeometry) addressBitsWithinXlen && customPageFits
    else computedAddressBits == 39 || computedAddressBits == 48
  // RV32 既有自定义 32 位几何保持不变；RV64 只有在 STANDARD 下要求
  // 完整标准 Sv39/Sv48，或在显式 CUSTOM 下才接受非标准几何。
  require(xlen == 32 || !enabled || geometryMode == "custom" ||
    standardSv39 || standardSv48,
    "RV64 HAS_MMU=y 只支持标准 Sv39/Sv48，或显式设置 MMU_GEOMETRY_MODE=custom：" +
      s"page=$pageSizeBytes levels=$levels PTE=$PTESizeBytes " +
      s"table=$pageTableSizeBytes fmt=$PTEFormat va=$computedAddressBits")
  require(!customGeometry || (addressBitsWithinXlen && customPageFits),
    s"RV64 CUSTOM 几何无法表达：pageBits($pageBits)+levels($levels)*" +
      s"indexBits($indexBitsPerLevel)=$computedAddressBits 位 VA 必须在 2..$xlen；" +
      s"PTE PPN 字段($PTEPPNBits 位)只有低 $PaddrPPNBits 位能装入 " +
      s"$xlen 位物理地址，其余高位在 walk 时按保留位必须为 0；" +
      s"satp.PPN 只有低 $SatpPPNBits 位可用（根表基址 = PPN << pageBits）")
  require(!customGeometry || customSatpModeLegal,
    s"RV64 CUSTOM 必须使用非标准 satp MODE 10..15（8/9 是标准 Sv39/Sv48），" +
      s"收到 $customSatpMode")
  // 超级页要求 PPN 的低 (levels-1)*indexBits 位为 0。PPN 字段比这个
  // 对齐宽度还窄时，对齐检查本身无法表达，必须在 elaboration 期拒绝。
  require(!superpage || PTEPPNBits >= (levels - 1) * indexBitsPerLevel,
    s"超级页对齐位(${(levels - 1) * indexBitsPerLevel})超过 PTE PPN 位宽" +
      s"($PTEPPNBits)，无法正确检查")
  require(!customGeometry ||
    (PTESizeBytes == 8 && (PTEFormat == "custom" ||
      (PTEFormat == "riscv64" && StandardPTELayout64))) ||
    (PTESizeBytes == 4 && (PTEFormat == "custom" ||
      (PTEFormat == "riscv32" && StandardPTELayout32))),
    s"RV64 CUSTOM 的 PTE 宽度/编码不一致：PTE=$PTESizeBytes fmt=$PTEFormat；" +
      "私有字段位置必须使用 MMU_PTE_FORMAT=custom")
  require(xlen == 64 || PTEFormat != "riscv64",
    "RV32 配置不能使用 riscv64 PTE 编码")
  // satp.MODE 编码：Sv32=1, Sv39=8, Sv48=9。0 始终表示关闭翻译。
  // CUSTOM 使用显式保留编码 10..15；标准 8/9 的语义绝不改变。
  final val satpMode: Int =
    if (xlen == 32) 1
    else if (customGeometry) customSatpMode
    else if (standardSv39) 8
    else if (standardSv48) 9
    else 0

  def describe: String = {
    val aliasScanDetail =
      cacheVIPTAliasScanMode match {
        case "parallel_n" => s"parallel_n(width=$cacheVIPTAliasScanWidth)"
        case "directory" =>
          s"directory(entries=$cacheVIPTAliasDirectoryEntries)"
        case other => other
      }
    s"MMU(enabled=$enabled xlen=$xlen geometry=$geometryMode va=$computedAddressBits " +
      s"satpMode=$satpMode${if (customGeometry) "(non-standard)" else ""} " +
      s"profile=$PTEFormat page=$pageSizeBytes levels=$levels " +
      s"PTE=$PTESizeBytes table=$pageTableSizeBytes " +
      s"cache=$cacheMode vipt_alias_safe=$cacheVIPTAliasSafe " +
      s"vipt_alias_scan=$aliasScanDetail " +
      s"vipt_alias_queue=$cacheVIPTAliasEventQueueDepth " +
      s"PTW=$PTWPath/$PTWArch/$PTWOutstanding/bus$PTWBusOutstanding/" +
      s"pmpq$PTWPMPFaultQueueDepth) " +
      s"TLB(enabled=$TLBEnabled org=$TLBOrganization storage=$TLBStorage " +
      s"repl=$TLBReplacement ASID=$TLBASIDBits)"
  }
}

object ysyx_26030103_MMUProfile {
  /** fillLevel 以及 PTW/TLB 内部层级字段的统一位宽。
    *
    * 3 位可表示 0..7，因此 RTL 最多支持 8 级页表；超过 8 级的几何在
    * profile/PTW/TLB elaboration 期明确拒绝，避免层级编号被截断。
    */
  val LevelBits: Int = 3
  val MaxLevels: Int = 1 << LevelBits

  /** 机器 identity CSR：标准只读寄存器，始终随核实现。
    *
    * mvendorid/marchid/mimpid 可以合法读 0，mhartid 在单 hart 核中固定为 0。
    * 它们不参与可裁剪配置，否则 CSR_ENABLED 漏列后会在译码阶段被判非法指令。
    */
  val IdentityCSRAddresses: Set[Int] = Set(0xf11, 0xf12, 0xf13, 0xf14)

  /** 环境配置 CSR 固定实现为 WARL 0。
    *
    * menvcfg(0x30a)/senvcfg(0x10a) 属于 S 模式扩展，随 HAS_S 存在；
    * mstatush(0x310)/menvcfgh(0x31a) 只在 RV32 存在。
    * csrSupported 会再按地址编码裁剪 U/S 特权级。
    */
  def EnvcfgFamilyCSRAddresses(xlen: Int): Set[Int] =
    if (xlen == 32) Set(0x30a, 0x310, 0x31a, 0x10a) else Set(0x30a, 0x10a)

  def UserCounterAddresses(xlen: Int, zihpm: Boolean, count: Int): Seq[Int] = {
    val baseAddresses =
      if (xlen == 32) Seq(0xc00, 0xc01, 0xc02, 0xc80, 0xc81, 0xc82)
      else Seq(0xc00, 0xc01, 0xc02)
    baseAddresses ++ ysyx_26030103_PMUCSR.UserAddresses(xlen, zihpm, count)
  }

  def FPCSRAddresses(useF: Boolean): Seq[Int] =
    if (useF) Seq(0x001, 0x002, 0x003) else Seq.empty

  def PMPAddresses(xlen: Int, enabled: Boolean): Seq[Int] =
    if (enabled)
      (if (xlen == 64) Seq(0x3a0, 0x3a2) else (0x3a0 to 0x3a3)) ++
        (0x3b0 to 0x3bf)
    else Seq.empty

  def ImplementedCSRAddresses(
      xlen: Int,
      profile: ysyx_26030103_MMUProfile,
      useF: Boolean,
      pmuZihpm: Boolean,
      pmuSscofpmf: Boolean,
      counterCount: Int
  ): Seq[Int] = {
    val envcfgFamilyAddresses = EnvcfgFamilyCSRAddresses(xlen).toSeq
    val userCounterAddresses =
      UserCounterAddresses(xlen, pmuZihpm, counterCount)
    val fpcsrAddresses = FPCSRAddresses(useF)
    val pmuMachineAddresses =
      ysyx_26030103_PMUCSR.MachineAddresses(
        xlen, pmuZihpm, pmuSscofpmf, counterCount)
    val envcfgAddresses =
      envcfgFamilyAddresses.filter(a => profile.csrSupported(a))
    (profile.CSREnabled.toSeq.filterNot(envcfgFamilyAddresses.contains) ++
      IdentityCSRAddresses.toSeq ++
      userCounterAddresses ++
      fpcsrAddresses ++
      pmuMachineAddresses ++
      PMPAddresses(xlen, profile.PMPEnabled)
    ).filter(a => profile.csrSupported(a) || fpcsrAddresses.contains(a) ||
      userCounterAddresses.contains(a) || pmuMachineAddresses.contains(a))
      .distinct.sorted ++
      envcfgAddresses ++
      (0x7a0 to 0x7af)
  }

  // 配置生成器未显式给出 CSR 集合时的标准实现集合。
  val DefaultCSRAddresses: Set[Int] = Set(
    0x300, 0x301, 0x302, 0x303, 0x304, 0x305, 0x306,
    0x320, 0x340, 0x341, 0x342, 0x343, 0x344,
    0x100, 0x104, 0x105, 0x106, 0x140, 0x141, 0x142,
    0x143, 0x144, 0x180,
    0xb00, 0xb02, 0xb80, 0xb82
  ) ++ IdentityCSRAddresses

  private def envBool(env: String => Option[String], key: String, fallback: Boolean): Boolean =
    env(key).filter(_.nonEmpty).map(v => v == "y" || v == "1").getOrElse(fallback)

  private def envInt(env: String => Option[String], key: String, fallback: Int): Int =
    env(key).filter(_.nonEmpty).map(_.toInt).getOrElse(fallback)

  private def envString(env: String => Option[String], key: String, fallback: String): String =
    env(key).filter(_.nonEmpty).getOrElse(fallback)

  private def envLong(env: String => Option[String], key: String, fallback: Long): Long =
    env(key).filter(_.nonEmpty).map { value =>
      val trimmed = value.trim
      if (trimmed.startsWith("0x") || trimmed.startsWith("0X"))
        java.lang.Long.parseLong(trimmed.substring(2), 16)
      else trimmed.toLong
    }.getOrElse(fallback)

  private def envAddressSet(env: String => Option[String], key: String,
      fallback: Set[Int]): Set[Int] =
    env(key).filter(_.nonEmpty).map { value =>
      value.split(',').iterator.map(_.trim).filter(_.nonEmpty).map { item =>
        val text = if (item.startsWith("0x") || item.startsWith("0X"))
          item.substring(2) else item
        Integer.parseInt(text, if (item.startsWith("0x") || item.startsWith("0X")) 16 else 10)
      }.toSet
    }.getOrElse(fallback)

  // 根据逐字段 read/write 模式生成 CSR 读掩码、读置位和写掩码。
  private def fieldMasks(
      fields: Seq[(String, BigInt)],
      env: String => Option[String],
      prefix: String,
      defaultRead: String,
      defaultWrite: String
  ): (BigInt, BigInt, BigInt) = {
    var readMask = BigInt(0)
    var readSet = BigInt(0)
    var writeMask = BigInt(0)
    fields.foreach { case (name, bits) =>
      val upper = name.toUpperCase
      val readMode = env(s"${prefix}_${upper}_READ").filter(_.nonEmpty).getOrElse(defaultRead)
      val writeMode = env(s"${prefix}_${upper}_WRITE").filter(_.nonEmpty).getOrElse(defaultWrite)
      readMode match {
        case "normal" => readMask |= bits
        case "zero"   => ()
        case "one"    => readMask |= bits; readSet |= bits
        case other    => throw new IllegalArgumentException(s"未知 CSR 读模式: $other")
      }
      writeMode match {
        case "normal" => writeMask |= bits
        case "ignore" => ()
        case other    => throw new IllegalArgumentException(s"未知 CSR 写模式: $other")
      }
    }
    (readMask, readSet, writeMask)
  }

  private val mstatusFields = Seq(
    "mie" -> BigInt("00000008", 16), "mpie" -> BigInt("00000080", 16),
    "mpp" -> BigInt("00001800", 16), "sie" -> BigInt("00000002", 16),
    "spie" -> BigInt("00000020", 16), "spp" -> BigInt("00000100", 16),
    "mprv" -> BigInt("00020000", 16), "sum" -> BigInt("00040000", 16),
    "mxr" -> BigInt("00080000", 16), "tvm" -> BigInt("00100000", 16),
    "tw" -> BigInt("00200000", 16), "tsr" -> BigInt("00400000", 16)
  )
  private val satpFields = Seq(
    "mode" -> BigInt("80000000", 16), "ASID" -> BigInt("7fc00000", 16),
    "PPN" -> BigInt("003fffff", 16)
  )
  // RV64 satp：MODE[63:60]，ASID[59:44]，PPN[43:0]。
  private val satpFields64 = Seq(
    "mode" -> (BigInt(0xf) << 60), "ASID" -> (BigInt(0xffff) << 44),
    "PPN" -> ((BigInt(1) << 44) - 1)
  )

  // 从 Kconfig 生成的环境变量构造；RV32 默认标准 Sv32，RV64 默认 Sv39。
  def fromEnv(env: String => Option[String], xlen: Int = 32): ysyx_26030103_MMUProfile = {
    require(xlen == 32 || xlen == 64, s"MMUProfile.fromEnv XLEN 非法: $xlen")
    val fieldDefaultRead = envString(env, "CSR_FIELD_DEFAULT_READ", "normal")
    val fieldDefaultWrite = envString(env, "CSR_FIELD_DEFAULT_WRITE", "normal")
    val hasS = envBool(env, "HAS_S", true)
    // 未显式给出 HAS_U 时跟随 HAS_S：HAS_S=n 的默认组合是 M-only，
    // 而不是悄悄保留一个没有 S 入口的 U 状态。
    val hasU = envBool(env, "HAS_U", hasS)
    val (mstatusReadMask, mstatusReadSet, mstatusWriteMask) =
      fieldMasks(mstatusFields, env, "CSR_MSTATUS", fieldDefaultRead, fieldDefaultWrite)
    val (satpReadMask, satpReadSet, satpWriteMask) =
      fieldMasks(if (xlen == 64) satpFields64 else satpFields,
        env, "CSR_SATP", fieldDefaultRead, fieldDefaultWrite)
    val DefaultPTEBytes =
      if (xlen == 64) KconfigDefaults.MMUPTESizeBytes else 4
    val PTEBytes = envInt(env, "MMU_PTE_SIZE_BYTES", DefaultPTEBytes)
    val DefaultPPNHigh = if (PTEBytes * 8 == 64) 53 else 31
    ysyx_26030103_MMUProfile(
      // 生成器输出 MMU_ENABLED；直接使用 Kconfig 风格环境时也接受 HAS_MMU。
      enabled = envBool(env, "MMU_ENABLED",
        envBool(env, "HAS_MMU", false)),
      xlen = xlen,
      geometryMode = envString(env, "MMU_GEOMETRY_MODE", "standard")
        .stripPrefix("\"").stripSuffix("\"").toLowerCase,
      customSatpMode = envInt(env, "MMU_CUSTOM_SATP_MODE", KconfigDefaults.MMUCustomSatpMode),
      pageSizeBytes = envInt(env, "MMU_PAGE_SIZE_BYTES", KconfigDefaults.MMUPageSizeBytes),
      levels = envInt(env, "MMU_LEVELS",
        if (xlen == 64) KconfigDefaults.MMULevels else 2),
      PTESizeBytes = PTEBytes,
      pageTableSizeBytes = envInt(env, "MMU_PAGE_TABLE_SIZE_BYTES", KconfigDefaults.MMUPageTableSizeBytes),
      PTEFormat = envString(env, "MMU_PTE_FORMAT",
        if (xlen == 64) "riscv64" else "riscv32"),
      PTEConfig = ysyx_26030103_PTEConfig(
        PTEBits = PTEBytes * 8,
        validBit = envInt(env, "MMU_PTE_VALID_BIT", KconfigDefaults.MMUPTEValidBit),
        readBit = envInt(env, "MMU_PTE_READ_BIT", KconfigDefaults.MMUPTEReadBit),
        writeBit = envInt(env, "MMU_PTE_WRITE_BIT", KconfigDefaults.MMUPTEWriteBit),
        executeBit = envInt(env, "MMU_PTE_EXEC_BIT", KconfigDefaults.MMUPTEExecBit),
        userBit = envInt(env, "MMU_PTE_USER_BIT", KconfigDefaults.MMUPTEUserBit),
        globalBit = envInt(env, "MMU_PTE_GLOBAL_BIT", KconfigDefaults.MMUPTEGlobalBit),
        aBit = envInt(env, "MMU_PTE_A_BIT", KconfigDefaults.MMUPTEABit),
        dBit = envInt(env, "MMU_PTE_D_BIT", KconfigDefaults.MMUPTEDBit),
        PPNLow = envInt(env, "MMU_PTE_PPN_LOW", KconfigDefaults.MMUPTEPPNLow),
        PPNHigh = envInt(env, "MMU_PTE_PPN_HIGH", DefaultPPNHigh)
      ),
      superpage = envBool(env, "MMU_SUPERPAGE", false),
      adMode = envString(env, "MMU_AD_MODE", "fault"),
      cacheMode = envString(env, "MMU_CACHE_MODE", "pipt"),
      cacheVIPTAliasSafe =
        envBool(env, "MMU_CACHE_VIPT_ALIAS_SAFE", false),
      cacheVIPTAliasScanMode =
        envString(env, "MMU_CACHE_VIPT_ALIAS_SCAN_MODE", "all_sets"),
      cacheVIPTAliasScanWidth =
        envInt(env, "MMU_CACHE_VIPT_ALIAS_SCAN_WIDTH",
        KconfigDefaults.MMUCacheVIPTAliasScanWidth),
      cacheVIPTAliasDirectoryEntries =
        envInt(env, "MMU_CACHE_VIPT_ALIAS_DIRECTORY_ENTRIES",
        KconfigDefaults.MMUCacheVIPTAliasDirectoryEntries),
      cacheVIPTAliasEventQueueDepth =
        envInt(env, "MMU_CACHE_VIPT_ALIAS_EVENT_QUEUE_DEPTH",
        KconfigDefaults.MMUCacheVIPTAliasEventQueueDepth),
      PTWPath = envString(env, "MMU_PTW_PATH", "dedicated"),
      PTWArch = envString(env, "MMU_PTW_ARCH", "independent"),
      PTWOutstanding = envInt(env, "MMU_PTW_OUTSTANDING", KconfigDefaults.MMUPTWOutstanding),
      PTWBusOutstanding = envInt(env, "MMU_PTW_BUS_OUTSTANDING", KconfigDefaults.MMUPTWBusOutstanding),
      PTWPMPFaultQueueDepth =
        envInt(env, "MMU_PTW_PMP_FAULT_QUEUE_DEPTH",
          KconfigDefaults.MMUPTWPMPFaultQueueDepth),
      PTWOrder = envString(env, "MMU_PTW_ORDER", "in_order"),
      PTWCache = envBool(env, "MMU_PTW_CACHE", false),
      PTWCacheEntries = envInt(env, "MMU_PTW_CACHE_ENTRIES", KconfigDefaults.MMUPTWCacheEntries),
      PTWCacheWays = envInt(env, "MMU_PTW_CACHE_WAYS", KconfigDefaults.MMUPTWCacheWays),
      TLBEnabled = envBool(env, "MMU_TLB_ENABLED", false),
      TLBOrganization = envString(env, "MMU_TLB_ORGANIZATION", "split"),
      TLBStorage = envString(env, "MMU_TLB_STORAGE", "reg"),
      TLBReplacement = envString(env, "MMU_TLB_REPLACEMENT", "plru"),
      // RV64 satp 的 ASID 为 16 位；未显式配置时 TLB 保留完整 ASID。
      TLBASIDBits = envInt(env, "MMU_TLB_ASID_BITS",
        if (xlen == 64) KconfigDefaults.MMUTLBASIDBits else 9),
      TLBGlobal = envBool(env, "MMU_TLB_GLOBAL", true),
      itlbEntries = envInt(env, "MMU_ITLB_ENTRIES", KconfigDefaults.MMUItlbEntries),
      itlbWays = envInt(env, "MMU_ITLB_WAYS", KconfigDefaults.MMUItlbWays),
      dtlbEntries = envInt(env, "MMU_DTLB_ENTRIES", KconfigDefaults.MMUDtlbEntries),
      dtlbWays = envInt(env, "MMU_DTLB_WAYS", KconfigDefaults.MMUDtlbWays),
      utlbEntries = envInt(env, "MMU_UTLB_ENTRIES", KconfigDefaults.MMUUtlbEntries),
      utlbWays = envInt(env, "MMU_UTLB_WAYS", KconfigDefaults.MMUUtlbWays),
      utlbLookupPorts = envInt(env, "UTLB_LOOKUP_PORTS", KconfigDefaults.MMUUtlbLookupPorts),
      TLBSuperpage = envBool(env, "MMU_TLB_SUPERPAGE", false),
      TLBSuperpageEntries = envInt(env, "MMU_TLB_SUPERPAGE_ENTRIES",
        KconfigDefaults.MMUTLBSuperpageEntries),
      TLBMissQueueMode = envString(env, "MMU_TLB_MISS_QUEUE_MODE", "follow_tlb"),
      itlbMissQueueEntries = envInt(env, "MMU_ITLB_MISS_QUEUE_ENTRIES",
        KconfigDefaults.MMUItlbMissQueueEntries),
      dtlbMissQueueEntries = envInt(env, "MMU_DTLB_MISS_QUEUE_ENTRIES",
        KconfigDefaults.MMUDtlbMissQueueEntries),
      utlbMissQueueEntries = envInt(env, "MMU_UTLB_MISS_QUEUE_ENTRIES",
        KconfigDefaults.MMUUtlbMissQueueEntries),
      globalMissQueueEntries = envInt(env, "MMU_GLOBAL_MISS_QUEUE_ENTRIES",
        KconfigDefaults.MMUGlobalMissQueueEntries),
      refillPorts = envInt(env, "MMU_TLB_REFILL_PORTS", KconfigDefaults.MMURefillPorts),
      CSRMstatusReadMask = envInt(env, "CSR_MSTATUS_READ_MASK", mstatusReadMask.toInt),
      CSRMstatusReadSet = envInt(env, "CSR_MSTATUS_READ_SET", mstatusReadSet.toInt),
      CSRMstatusWriteMask = envInt(env, "CSR_MSTATUS_WRITE_MASK", mstatusWriteMask.toInt),
      CSRSatpReadMask = envLong(env, "CSR_SATP_READ_MASK", satpReadMask.toLong),
      CSRSatpReadSet = envLong(env, "CSR_SATP_READ_SET", satpReadSet.toLong),
      CSRSatpWriteMask = envLong(env, "CSR_SATP_WRITE_MASK", satpWriteMask.toLong),
      hasS = hasS,
      hasU = hasU,
      HasSTimerIRQ = envBool(env, "HAS_S_TIMER_IRQ", true),
      HasSExternalIRQ = envBool(env, "HAS_S_EXTERNAL_IRQ", true),
      timerPath = envString(env, "TIMER_PATH", "clint_mtip"),
      PLICSources = envInt(env, "PLIC_SOURCES", KconfigDefaults.MMUPLICSources),
      PLICPriorityBits = envInt(env, "PLIC_PRIORITY_BITS", KconfigDefaults.MMUPLICPriorityBits),
      PLICMaxPriority = envInt(env, "PLIC_MAX_PRIORITY", KconfigDefaults.MMUPLICMaxPriority),
      PLICBase = envLong(
        env,
        "PLIC_BASE",
        ysyx_26030103_PhysicalMemoryMap.DefaultPLICBase
      ),
      PLICSize = envLong(
        env,
        "PLIC_SIZE",
        ysyx_26030103_PhysicalMemoryMap.DefaultPLICSize
      ),
      CSRViewMode = envString(env, "CSR_VIEW_MODE", "bound"),
      CSRMissingMode = envString(env, "CSR_MISSING_MODE", "illegal"),
      PMPEnabled = envBool(env, "HAS_PMP", false),
      PMPEntries = envInt(env, "PMP_ENTRIES", KconfigDefaults.MMUPMPEntries),
      PMPGranularityLog2 =
        envInt(env, "PMP_GRANULARITY_LOG2", KconfigDefaults.MMUPMPGranularityLog2),
      CSREnabled = envAddressSet(env, "CSR_ENABLED", DefaultCSRAddresses) ++
        IdentityCSRAddresses ++ EnvcfgFamilyCSRAddresses(xlen)
    )
  }
}
