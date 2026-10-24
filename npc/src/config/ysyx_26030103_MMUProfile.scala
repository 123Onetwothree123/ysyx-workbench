package ysyx_26030103.config

/** PTE 字段位置；默认就是标准 RISC-V 32-bit PTE。
  *
  * pteBits 表示 PTE 自身的位宽（Sv32=32，Sv39/Sv48=64）。PPN 字段在
  * 64 位 PTE 中扩展到 bit 53（标准 44 位 PPN），自定义编码仍可按位配置。
  */
case class ysyx_26030103_PteConfig(
    pteBits: Int = 32,
    validBit: Int = 0,
    readBit: Int = 1,
    writeBit: Int = 2,
    executeBit: Int = 3,
    userBit: Int = 4,
    globalBit: Int = 5,
    aBit: Int = 6,
    dBit: Int = 7,
    ppnLow: Int = 10,
    ppnHigh: Int = 31
) {
  require(pteBits == 32 || pteBits == 64, s"PTE 位宽只允许 32/64，收到 $pteBits")
  require(ppnLow >= 8 && ppnHigh < pteBits && ppnLow <= ppnHigh,
    "PTE PPN 位范围非法（至少为 8，且必须低于 PTE 位宽）")
  private val flagBits = Seq(validBit, readBit, writeBit, executeBit,
    userBit, globalBit, aBit, dBit)
  require(flagBits.forall(x => x >= 0 && x < pteBits), "PTE 标志位非法")
  // 归一化后的内部 PTE 固定把 8 个标志放在低 8 位。字段位置可以任意，
  // 但标志之间不能重叠，也不能落进 PPN 字段，否则同一个 PTE 位会被
  // 同时解释成两种语义。无法正确表达的编码必须在 elaboration 期拒绝。
  require(flagBits.distinct.size == flagBits.size, "PTE 标志位不能重复")
  require(flagBits.forall(b => b < ppnLow || b > ppnHigh),
    s"PTE 标志位不能与 PPN 字段 [$ppnHigh:$ppnLow] 重叠")
  // PPN 高位以上的位统一按保留位检查；标志位若放在该区域会和保留位
  // 语义冲突，无法用当前归一化/保留位规则正确表达。
  require(flagBits.forall(_ <= ppnHigh),
    s"PTE 标志位必须位于 PPN 高位($ppnHigh)以下，不能占用保留位区域")
}

/** MMU/TLB 的 elaboration 期参数。
  *
  * 这个类由 Elaborate 从 Kconfig 生成的环境变量构造，并驱动 MMU/TLB RTL。
  * 默认值就是标准 Sv32 balanced profile；RV64 默认使用 xlen=64 的标准
  * Sv39（3 级/8B PTE）或 Sv48（4 级/8B PTE）。RV64 的非标准页几何只能
  * 通过显式的 `geometryMode = "custom"` 打开，并使用一个非标准的 satp
  * MODE 编码（10..15）作为扩展，绝不会伪装成标准 Sv39/Sv48。
  * RV32 的既有自定义 32 位几何保持兼容。
  */
case class ysyx_26030103_MMUProfile(
    enabled: Boolean = false,
    // MMU 所属的架构位宽。RV32 默认 32；RV64/Sv39 由 elaborator 设置为 64。
    xlen: Int = 32,
    // RV64 页几何模式：standard 只接受标准 Sv39/Sv48；custom 显式启用
    // 非标准页大小/级数/PTE 编码，并要求 satp MODE 使用 10..15 的扩展值。
    // RV32 不接受该字段的额外限制，既有的自定义 32 位几何保持不变。
    geometryMode: String = "standard",
    // CUSTOM 模式专用的 satp MODE。8/9 是标准 Sv39/Sv48，1 是 Sv32，
    // 都不能被非标准几何复用，因此 CUSTOM 只允许保留编码 10..15。
    customSatpMode: Int = 10,
    pageSizeBytes: Int = 4096,
    levels: Int = 2,
    pteSizeBytes: Int = 4,
    pageTableSizeBytes: Int = 4096,
    pteFormat: String = "riscv32",
    pteConfig: ysyx_26030103_PteConfig = ysyx_26030103_PteConfig(),
    superpage: Boolean = false,
    adMode: String = "fault",
    cacheMode: String = "pipt",
    // VIPT alias 安全模式：允许 cache index/块偏移越过页内偏移（即 index
    // 含 VPN 位）。此时同一物理行可能被不同 VA 映射到不同 set，Cache 在
    // refill/store 时按物理 tag 清除其他 set 的别名副本。false 保持原有
    // elaboration 期拒绝策略，绝不静默生成会 alias 的硬件。
    cacheViptAliasSafe: Boolean = false,
    // alias 失效扫描方式：all_sets 单拍全扫描（默认，refill 无额外等待）；
    // serial 每周期扫描一个 set；parallel_n 每周期扫描 N 个 set；
    // directory 维护物理行 -> set 目录。只有 cacheViptAliasSafe 真正生成
    // alias 维护硬件时才影响 RTL。
    cacheViptAliasScanMode: String = "all_sets",
    // parallel_n 的每周期扫描宽度（2..1024）。超过实际 set 数时在该
    // Cache 内截断为 sets；Kconfig 默认 4，对应 8/16/32 set 的 2/4/8 拍。
    cacheViptAliasScanWidth: Int = 4,
    // directory 的目录项数。0 表示跟随实际 set 数（每个 set 独占目录桶，
    // 无冲突）；显式值必须是 2 的幂且不超过 set 数。
    cacheViptAliasDirectoryEntries: Int = 0,
    ptwPath: String = "dedicated",
    ptwArch: String = "independent",
    ptwOutstanding: Int = 2,
    // 页表总线可同时保持的页表读/写数。0 表示跟随 ptwOutstanding；
    // 默认 1 保持原有的单笔总线行为，配置 >1 才启用总线级重叠。
    ptwBusOutstanding: Int = 1,
    ptwOrder: String = "in_order",
    ptwCache: Boolean = false,
    ptwCacheEntries: Int = 16,
    ptwCacheWays: Int = 2,
    tlbEnabled: Boolean = false,
    tlbOrganization: String = "split",
    tlbStorage: String = "reg",
    tlbReplacement: String = "plru",
    tlbAsidBits: Int = 9,
    tlbGlobal: Boolean = true,
    itlbEntries: Int = 16,
    itlbWays: Int = 2,
    dtlbEntries: Int = 32,
    dtlbWays: Int = 4,
    utlbEntries: Int = 32,
    utlbWays: Int = 4,
    utlbLookupPorts: Int = 1,
    tlbSuperpage: Boolean = false,
    tlbSuperpageEntries: Int = 4,
    tlbMissQueueMode: String = "follow_tlb",
    itlbMissQueueEntries: Int = 4,
    dtlbMissQueueEntries: Int = 4,
    utlbMissQueueEntries: Int = 8,
    globalMissQueueEntries: Int = 8,
    refillPorts: Int = 1,
    csrMstatusReadMask: Int = 0x007e19aa,
    csrMstatusReadSet: Int = 0,
    csrMstatusWriteMask: Int = 0x007e19aa,
    // satp 在 RV64 下是 64 位 CSR，因此掩码使用 Long；RV32 只用低 32 位。
    csrSatpReadMask: Long = 0xffffffffffffffffL,
    csrSatpReadSet: Long = 0,
    csrSatpWriteMask: Long = 0xffffffffffffffffL,
    hasS: Boolean = true,
    hasU: Boolean = true,
    hasSTimerIrq: Boolean = true,
    hasSExternalIrq: Boolean = true,
    timerPath: String = "clint_mtip",
    plicSources: Int = 32,
    plicPriorityBits: Int = 3,
    plicMaxPriority: Int = 7,
    plicBase: Long = 0x0c000000L,
    csrViewMode: String = "bound",
    csrMissingMode: String = "illegal",
    pmpEnabled: Boolean = false,
    pmpEntries: Int = 16,
    csrEnabled: Set[Int] = ysyx_26030103_MMUProfile.DefaultCsrAddresses
) {
  private def isPowerOfTwo(value: Int): Boolean =
    value > 0 && (value & (value - 1)) == 0

  private def requirePowerOfTwo(name: String, value: Int): Unit =
    require(isPowerOfTwo(value), s"$name=$value 必须是正的 2 的幂")

  require(xlen == 32 || xlen == 64, s"MMU XLEN 只允许 32/64，收到 $xlen")
  requirePowerOfTwo("pageSizeBytes", pageSizeBytes)
  requirePowerOfTwo("pteSizeBytes", pteSizeBytes)
  requirePowerOfTwo("pageTableSizeBytes", pageTableSizeBytes)
  require(pteSizeBytes == 4 || pteSizeBytes == 8, "PTE 大小只支持 4/8 字节")
  require(xlen == 32 || pteConfig.pteBits == pteSizeBytes * 8,
    s"RV64 PTE 字段配置位宽(${pteConfig.pteBits})必须等于 pteSizeBytes*8(${pteSizeBytes * 8})")
  require(adMode != "hardware" || pteSizeBytes <= 4 ||
    (pteConfig.aBit < 32 && pteConfig.dBit < 32),
    "64 位 PTE 的 A/D 硬件写回要求 A/D 位位于低 32 位字内（32 位页表写端口只更新低字）")
  // fillLevel/页表层级字段统一为 3 位，因此 RTL 最多能表达 8 级；
  // 更深的几何必须在 elaboration 期明确拒绝，不能截断层级编号。
  require(levels >= 1 && levels <= ysyx_26030103_MMUProfile.MaxLevels,
    s"levels=$levels 必须在 1..${ysyx_26030103_MMUProfile.MaxLevels}" +
      s"（fillLevel/层级字段为 ${ysyx_26030103_MMUProfile.LevelBits} 位）")
  require(
    pageTableSizeBytes % pteSizeBytes == 0,
    s"pageTableSizeBytes=$pageTableSizeBytes 必须能被 pteSizeBytes=$pteSizeBytes 整除"
  )
  requirePowerOfTwo("每级页表项数", pageTableSizeBytes / pteSizeBytes)
  require(pageTableSizeBytes / pteSizeBytes >= 2,
    s"每级页表项数(${pageTableSizeBytes / pteSizeBytes})必须至少为 2，否则索引位宽为 0")
  require(ptwOutstanding >= 1 && ptwOutstanding <= 16,
    s"ptwOutstanding=$ptwOutstanding 必须在 1..16（PTW 事务 ID 为 4 位）")
  require(ptwBusOutstanding >= 0 && ptwBusOutstanding <= 16,
    s"ptwBusOutstanding=$ptwBusOutstanding 必须在 0..16（0 表示跟随 ptwOutstanding）")
  require(Set("independent", "pipelined").contains(ptwArch),
    "MMU_PTW_ARCH 必须是 independent/pipelined")
  require(Set("in_order", "out_of_order").contains(ptwOrder),
    "MMU_PTW_ORDER 必须是 in_order/out_of_order")
  require(
    Set("all_sets", "serial", "parallel_n", "directory")
      .contains(cacheViptAliasScanMode),
    "MMU_CACHE_VIPT_ALIAS_SCAN_MODE 必须是 all_sets/serial/parallel_n/directory"
  )
  // parallel_n 的宽度在真正生成硬件的 Cache 内按 sets 截断；这里只校验
  // 配置值本身可实现，避免 Kconfig 的静态 range 与实际 sets 不一致。
  require(cacheViptAliasScanWidth >= 2 && cacheViptAliasScanWidth <= 1024,
    s"MMU_CACHE_VIPT_ALIAS_SCAN_WIDTH=$cacheViptAliasScanWidth 必须在 2..1024")
  require(cacheViptAliasDirectoryEntries >= 0 &&
    cacheViptAliasDirectoryEntries <= 1024,
    s"MMU_CACHE_VIPT_ALIAS_DIRECTORY_ENTRIES=$cacheViptAliasDirectoryEntries 必须在 0..1024")
  require(cacheViptAliasDirectoryEntries == 0 ||
    (cacheViptAliasDirectoryEntries & (cacheViptAliasDirectoryEntries - 1)) == 0,
    s"MMU_CACHE_VIPT_ALIAS_DIRECTORY_ENTRIES=$cacheViptAliasDirectoryEntries 必须是 2 的幂或 0")
  require(tlbAsidBits >= 0 && tlbAsidBits <= 16, "tlbAsidBits 必须在 0..16")
  require(utlbLookupPorts >= 1 && utlbLookupPorts <= 2, "utlbLookupPorts 必须在 1..2")
  require(refillPorts >= 1, "refillPorts 必须 >= 1")
  // RISC-V 允许 M+U：没有 S 时 U 的陷阱直接进入 M，用 mepc/mcause/
  // mtval/mstatus.MPP 保存状态，MRET 按 MPP 返回 U。只有 MMU 必须依赖 S。
  // Sv32/Sv39/Sv48 页表翻译、satp、页错误入口和异常委托都属于 S 模式。
  // HAS_S=n 时必须显式关闭 MMU，而不是静默保留完整 S 逻辑。
  require(!enabled || hasS,
    "HAS_MMU=y 需要 HAS_S=y：MMU/Sv32/Sv39/Sv48 依赖 S 模式 satp/页错误入口；请设置 HAS_S=y 或 HAS_MMU=n")
  require(plicSources >= 2 && plicSources <= 1024, "PLIC source 数必须在 2..1024")
  require(plicPriorityBits >= 1 && plicPriorityBits <= 8, "PLIC 优先级位宽非法")
  require(plicMaxPriority >= 0 && plicMaxPriority < (1 << plicPriorityBits),
    "PLIC_MAX_PRIORITY 超出优先级位宽")
  require(plicBase >= 0 && plicBase < (1L << 32), "PLIC_BASE 超出 32 位地址空间")
  require(Set("clint_mtip", "direct_stip").contains(timerPath), "TIMER_PATH 必须是 clint_mtip/direct_stip")
  require(Set("bound", "independent").contains(csrViewMode), "CSR_VIEW_MODE 必须是 bound/independent")
  require(Set("illegal", "zero").contains(csrMissingMode), "CSR_MISSING_MODE 必须是 illegal/zero")
  require(pmpEntries >= 0 && pmpEntries <= 16, "PMP_ENTRIES 必须在 0..16")
  require(!pmpEnabled || pmpEntries > 0, "HAS_PMP=y 时 PMP_ENTRIES 必须 > 0")
  // PMP 不再依赖 MMU：无 MMU 时直接检查 IFU 物理取指地址和 LSU 的 VA==PA
  // 访存地址；有 MMU 时仍检查翻译后的物理地址。

  def csrSupported(addr: Int): Boolean = {
    // CSR 地址位 [9:8] 编码最低特权级：00=U，01=S，11=M。
    // U 级不只是 0x000--0x0ff，还包括 0xc00 这类用户只读计数器。
    val csrPrivilege = (addr >> 8) & 0x3
    val uLevel = csrPrivilege == 0
    val sLevel = csrPrivilege == 1
    // medeleg/mideleg 是 M 级地址，但只控制不存在的 S 状态，HAS_S=n
    // 时从实现集合裁剪。mcounteren 仍是 M 级寄存器：M+U 下它门控 U
    // 访问用户计数器，因此只有连 U 都不存在时才裁剪。
    val sControl =
      addr == 0x302 || addr == 0x303 || (addr == 0x306 && !hasU)
    // HAS_U=n 时 U 级 CSR 一并从实现集合裁剪；HAS_S=n 时裁剪 S 级 CSR。
    ((hasU || !uLevel) && (hasS || (!sLevel && !sControl))) &&
      (csrEnabled.contains(addr) ||
        ysyx_26030103_MMUProfile.IdentityCsrAddresses.contains(addr) ||
        (pmpEnabled && ((addr >= 0x3a0 && addr <= 0x3a3) ||
          (addr >= 0x3b0 && addr <= 0x3bf))))
  }

  /** 当前配置允许的最低特权级：有 U 为 U；否则有 S 为 S；M-only 为 M。 */
  final val lowestPrivilege: Int = if (hasU) 0 else if (hasS) 1 else 3

  require(Set("split", "unified").contains(tlbOrganization),
    "MMU_TLB_ORGANIZATION 必须是 split/unified")
  require(Set("follow_tlb", "global").contains(tlbMissQueueMode),
    "MMU_TLB_MISS_QUEUE_MODE 必须是 follow_tlb/global")
  if (tlbEnabled) {
    requirePowerOfTwo("itlbEntries", itlbEntries)
    requirePowerOfTwo("dtlbEntries", dtlbEntries)
    requirePowerOfTwo("utlbEntries", utlbEntries)
    require(itlbEntries % itlbWays == 0, "itlbEntries 必须能被 itlbWays 整除")
    require(dtlbEntries % dtlbWays == 0, "dtlbEntries 必须能被 dtlbWays 整除")
    require(utlbEntries % utlbWays == 0, "utlbEntries 必须能被 utlbWays 整除")
    // miss queue 是 TLB 缺失请求进入 PTW 前的真实缓冲；深度为 0 会让
    // 对应 TLB 的 miss 永远无法入队，必须在 elaboration 期拒绝。
    if (tlbMissQueueMode == "global") {
      require(globalMissQueueEntries >= 1,
        "MMU_GLOBAL_MISS_QUEUE_ENTRIES 必须 >= 1")
    } else if (tlbOrganization == "split") {
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
  final val tableEntries: Int = pageTableSizeBytes / pteSizeBytes
  final val indexBitsPerLevel: Int = Integer.numberOfTrailingZeros(tableEntries)
  final val computedAddressBits: Int = pageBits + levels * indexBitsPerLevel
  // 标准 PTE 字段布局：低 8 位标志，PPN 从 bit10 开始。显式 CUSTOM
  // 模式也允许该布局，但若把 `pteFormat` 写成 riscv32/riscv64，字段位置
  // 必须与对应标准完全一致，避免“名字是标准、编码却是私有”的伪装。
  final val standardPteLayout32: Boolean =
    pteConfig.pteBits == 32 && pteConfig.validBit == 0 &&
      pteConfig.readBit == 1 && pteConfig.writeBit == 2 &&
      pteConfig.executeBit == 3 && pteConfig.userBit == 4 &&
      pteConfig.globalBit == 5 && pteConfig.aBit == 6 &&
      pteConfig.dBit == 7 && pteConfig.ppnLow == 10 && pteConfig.ppnHigh == 31
  final val standardPteLayout64: Boolean =
    pteConfig.pteBits == 64 && pteConfig.validBit == 0 &&
      pteConfig.readBit == 1 && pteConfig.writeBit == 2 &&
      pteConfig.executeBit == 3 && pteConfig.userBit == 4 &&
      pteConfig.globalBit == 5 && pteConfig.aBit == 6 &&
      pteConfig.dBit == 7 && pteConfig.ppnLow == 10 && pteConfig.ppnHigh == 53
  final val standardSv32: Boolean =
    xlen == 32 && pageSizeBytes == 4096 && levels == 2 && pteSizeBytes == 4 &&
      pageTableSizeBytes == 4096 && pteFormat == "riscv32"
  final val standardSv39: Boolean =
    xlen == 64 && pageSizeBytes == 4096 && levels == 3 && pteSizeBytes == 8 &&
      pageTableSizeBytes == 4096 && pteFormat == "riscv64" &&
      standardPteLayout64 && computedAddressBits == 39
  final val standardSv48: Boolean =
    xlen == 64 && pageSizeBytes == 4096 && levels == 4 && pteSizeBytes == 8 &&
      pageTableSizeBytes == 4096 && pteFormat == "riscv64" &&
      standardPteLayout64 && computedAddressBits == 48
  // CUSTOM 只改页大小/级数/索引/PTE 宽度与字段位置；虚拟地址仍必须在
  // XLEN 内可表示，且 PPN+页内偏移不能超过 64 位物理地址。
  final val customGeometry: Boolean = xlen == 64 && geometryMode == "custom"
  /** 显式标记当前 profile 使用非标准 RV64 页几何。 */
  final val nonStandard: Boolean = customGeometry
  final val customSatpModeLegal: Boolean =
    customSatpMode >= 10 && customSatpMode <= 15
  final val addressBitsWithinXlen: Boolean =
    computedAddressBits >= 2 && computedAddressBits <= xlen
  final val ptePpnBits: Int = pteConfig.ppnHigh - pteConfig.ppnLow + 1
  // PTE PPN 字段可以比物理地址空间更宽。例如 2MiB 页(pageBits=21) 配
  // 标准 44 位 PPN 时，PPN<<21 需要 65 位；64 位物理地址只能容纳低
  // 43 位 PPN，其余高位必须为 0，由 PTW 在 walk 时按保留位检查并报
  // page fault，绝不能静默截断。satp.PPN 同理：根表基址 =
  // satp.PPN << pageBits，超出物理地址宽度的高位不可用。
  final val paddrPpnBits: Int = math.min(ptePpnBits, xlen - pageBits)
  final val satpPpnBits: Int = math.min(44, xlen - pageBits)
  final val customPageFits: Boolean =
    pageBits < computedAddressBits && paddrPpnBits >= 1 && satpPpnBits >= 1
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
      s"page=$pageSizeBytes levels=$levels pte=$pteSizeBytes " +
      s"table=$pageTableSizeBytes fmt=$pteFormat va=$computedAddressBits")
  require(!customGeometry || (addressBitsWithinXlen && customPageFits),
    s"RV64 CUSTOM 几何无法表达：pageBits($pageBits)+levels($levels)*" +
      s"indexBits($indexBitsPerLevel)=$computedAddressBits 位 VA 必须在 2..$xlen；" +
      s"PTE PPN 字段($ptePpnBits 位)只有低 $paddrPpnBits 位能装入 " +
      s"$xlen 位物理地址，其余高位在 walk 时按保留位必须为 0；" +
      s"satp.PPN 只有低 $satpPpnBits 位可用（根表基址 = PPN << pageBits）")
  require(!customGeometry || customSatpModeLegal,
    s"RV64 CUSTOM 必须使用非标准 satp MODE 10..15（8/9 是标准 Sv39/Sv48），" +
      s"收到 $customSatpMode")
  // 超级页要求 PPN 的低 (levels-1)*indexBits 位为 0。PPN 字段比这个
  // 对齐宽度还窄时，对齐检查本身无法表达，必须在 elaboration 期拒绝。
  require(!superpage || ptePpnBits >= (levels - 1) * indexBitsPerLevel,
    s"超级页对齐位(${(levels - 1) * indexBitsPerLevel})超过 PTE PPN 位宽" +
      s"($ptePpnBits)，无法正确检查")
  require(!customGeometry ||
    (pteSizeBytes == 8 && (pteFormat == "custom" ||
      (pteFormat == "riscv64" && standardPteLayout64))) ||
    (pteSizeBytes == 4 && (pteFormat == "custom" ||
      (pteFormat == "riscv32" && standardPteLayout32))),
    s"RV64 CUSTOM 的 PTE 宽度/编码不一致：pte=$pteSizeBytes fmt=$pteFormat；" +
      "私有字段位置必须使用 MMU_PTE_FORMAT=custom")
  require(xlen == 64 || pteFormat != "riscv64",
    "RV32 配置不能使用 riscv64 PTE 编码")
  // satp.MODE 编码：Sv32=1, Sv39=8, Sv48=9。0 始终表示关闭翻译。
  // CUSTOM 使用显式保留编码 10..15；标准 8/9 的语义绝不改变。
  final val satpMode: Int =
    if (xlen == 32) 1
    else if (customGeometry) customSatpMode
    else if (standardSv39) 8
    else if (standardSv48) 9
    else 0
  final val standardSatpMode: Boolean =
    satpMode == 1 || satpMode == 8 || satpMode == 9

  def describe: String = {
    val aliasScanDetail =
      cacheViptAliasScanMode match {
        case "parallel_n" => s"parallel_n(width=$cacheViptAliasScanWidth)"
        case "directory" =>
          s"directory(entries=$cacheViptAliasDirectoryEntries)"
        case other => other
      }
    s"MMU(enabled=$enabled xlen=$xlen geometry=$geometryMode va=$computedAddressBits " +
      s"satpMode=$satpMode${if (customGeometry) "(non-standard)" else ""} " +
      s"profile=$pteFormat page=$pageSizeBytes levels=$levels " +
      s"pte=$pteSizeBytes table=$pageTableSizeBytes " +
      s"cache=$cacheMode vipt_alias_safe=$cacheViptAliasSafe " +
      s"vipt_alias_scan=$aliasScanDetail " +
      s"ptw=$ptwPath/$ptwArch/$ptwOutstanding/bus$ptwBusOutstanding) " +
      s"TLB(enabled=$tlbEnabled org=$tlbOrganization storage=$tlbStorage " +
      s"repl=$tlbReplacement asid=$tlbAsidBits)"
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
    * mvendorid/marchid/mimpid 可以合法读 0，mhartid 在单 hart 核中固定
    * 为 0。它们不参与可裁剪的功能配置，避免 CSR_ENABLED 漏列后
    * mhartid/mimpid 在译码阶段被误判为非法指令。
    */
  val IdentityCsrAddresses: Set[Int] = Set(0xf11, 0xf12, 0xf13, 0xf14)

  /** 配置生成器未显式给出 CSR 集合时的标准实现集合。 */
  val DefaultCsrAddresses: Set[Int] = Set(
    0x300, 0x301, 0x302, 0x303, 0x304, 0x305, 0x306,
    0x320, 0x340, 0x341, 0x342, 0x343, 0x344,
    0x100, 0x104, 0x105, 0x106, 0x140, 0x141, 0x142,
    0x143, 0x144, 0x180,
    0xb00, 0xb02, 0xb80, 0xb82
  ) ++ IdentityCsrAddresses

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

  /** 根据逐字段 read/write 模式生成 CSR 读掩码、读置位和写掩码。 */
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

  private val MstatusFields = Seq(
    "mie" -> BigInt("00000008", 16), "mpie" -> BigInt("00000080", 16),
    "mpp" -> BigInt("00001800", 16), "sie" -> BigInt("00000002", 16),
    "spie" -> BigInt("00000020", 16), "spp" -> BigInt("00000100", 16),
    "mprv" -> BigInt("00020000", 16), "sum" -> BigInt("00040000", 16),
    "mxr" -> BigInt("00080000", 16), "tvm" -> BigInt("00100000", 16),
    "tw" -> BigInt("00200000", 16), "tsr" -> BigInt("00400000", 16)
  )
  private val SatpFields = Seq(
    "mode" -> BigInt("80000000", 16), "asid" -> BigInt("7fc00000", 16),
    "ppn" -> BigInt("003fffff", 16)
  )
  // RV64 satp：MODE[63:60]，ASID[59:44]，PPN[43:0]。
  private val SatpFields64 = Seq(
    "mode" -> (BigInt(0xf) << 60), "asid" -> (BigInt(0xffff) << 44),
    "ppn" -> ((BigInt(1) << 44) - 1)
  )

  /** 从 Kconfig 生成的环境变量构造；RV32 默认标准 Sv32，RV64 默认 Sv39。 */
  def fromEnv(env: String => Option[String], xlen: Int = 32): ysyx_26030103_MMUProfile = {
    require(xlen == 32 || xlen == 64, s"MMUProfile.fromEnv XLEN 非法: $xlen")
    val fieldDefaultRead = envString(env, "CSR_FIELD_DEFAULT_READ", "normal")
    val fieldDefaultWrite = envString(env, "CSR_FIELD_DEFAULT_WRITE", "normal")
    val hasS = envBool(env, "HAS_S", true)
    // 未显式给出 HAS_U 时跟随 HAS_S：HAS_S=n 的默认组合是 M-only，
    // 而不是悄悄保留一个没有 S 入口的 U 状态。
    val hasU = envBool(env, "HAS_U", hasS)
    val (mstatusReadMask, mstatusReadSet, mstatusWriteMask) =
      fieldMasks(MstatusFields, env, "CSR_MSTATUS", fieldDefaultRead, fieldDefaultWrite)
    val (satpReadMask, satpReadSet, satpWriteMask) =
      fieldMasks(if (xlen == 64) SatpFields64 else SatpFields,
        env, "CSR_SATP", fieldDefaultRead, fieldDefaultWrite)
    val defaultPteBytes = if (xlen == 64) 8 else 4
    val pteBytes = envInt(env, "MMU_PTE_SIZE_BYTES", defaultPteBytes)
    val defaultPpnHigh = if (pteBytes * 8 == 64) 53 else 31
    ysyx_26030103_MMUProfile(
      // 生成器输出 MMU_ENABLED；直接使用 Kconfig 风格环境时也接受 HAS_MMU。
      enabled = envBool(env, "MMU_ENABLED",
        envBool(env, "HAS_MMU", false)),
      xlen = xlen,
      geometryMode = envString(env, "MMU_GEOMETRY_MODE", "standard")
        .stripPrefix("\"").stripSuffix("\"").toLowerCase,
      customSatpMode = envInt(env, "MMU_CUSTOM_SATP_MODE", 10),
      pageSizeBytes = envInt(env, "MMU_PAGE_SIZE_BYTES", 4096),
      levels = envInt(env, "MMU_LEVELS", if (xlen == 64) 3 else 2),
      pteSizeBytes = pteBytes,
      pageTableSizeBytes = envInt(env, "MMU_PAGE_TABLE_SIZE_BYTES", 4096),
      pteFormat = envString(env, "MMU_PTE_FORMAT",
        if (xlen == 64) "riscv64" else "riscv32"),
      pteConfig = ysyx_26030103_PteConfig(
        pteBits = pteBytes * 8,
        validBit = envInt(env, "MMU_PTE_VALID_BIT", 0),
        readBit = envInt(env, "MMU_PTE_READ_BIT", 1),
        writeBit = envInt(env, "MMU_PTE_WRITE_BIT", 2),
        executeBit = envInt(env, "MMU_PTE_EXEC_BIT", 3),
        userBit = envInt(env, "MMU_PTE_USER_BIT", 4),
        globalBit = envInt(env, "MMU_PTE_GLOBAL_BIT", 5),
        aBit = envInt(env, "MMU_PTE_A_BIT", 6),
        dBit = envInt(env, "MMU_PTE_D_BIT", 7),
        ppnLow = envInt(env, "MMU_PTE_PPN_LOW", 10),
        ppnHigh = envInt(env, "MMU_PTE_PPN_HIGH", defaultPpnHigh)
      ),
      superpage = envBool(env, "MMU_SUPERPAGE", false),
      adMode = envString(env, "MMU_AD_MODE", "fault"),
      cacheMode = envString(env, "MMU_CACHE_MODE", "pipt"),
      cacheViptAliasSafe =
        envBool(env, "MMU_CACHE_VIPT_ALIAS_SAFE", false),
      cacheViptAliasScanMode =
        envString(env, "MMU_CACHE_VIPT_ALIAS_SCAN_MODE", "all_sets"),
      cacheViptAliasScanWidth =
        envInt(env, "MMU_CACHE_VIPT_ALIAS_SCAN_WIDTH", 4),
      cacheViptAliasDirectoryEntries =
        envInt(env, "MMU_CACHE_VIPT_ALIAS_DIRECTORY_ENTRIES", 0),
      ptwPath = envString(env, "MMU_PTW_PATH", "dedicated"),
      ptwArch = envString(env, "MMU_PTW_ARCH", "independent"),
      ptwOutstanding = envInt(env, "MMU_PTW_OUTSTANDING", 2),
      ptwBusOutstanding = envInt(env, "MMU_PTW_BUS_OUTSTANDING", 1),
      ptwOrder = envString(env, "MMU_PTW_ORDER", "in_order"),
      ptwCache = envBool(env, "MMU_PTW_CACHE", false),
      ptwCacheEntries = envInt(env, "MMU_PTW_CACHE_ENTRIES", 16),
      ptwCacheWays = envInt(env, "MMU_PTW_CACHE_WAYS", 2),
      tlbEnabled = envBool(env, "MMU_TLB_ENABLED", false),
      tlbOrganization = envString(env, "MMU_TLB_ORGANIZATION", "split"),
      tlbStorage = envString(env, "MMU_TLB_STORAGE", "reg"),
      tlbReplacement = envString(env, "MMU_TLB_REPLACEMENT", "plru"),
      // RV64 satp 的 ASID 为 16 位；未显式配置时 TLB 保留完整 ASID。
      tlbAsidBits = envInt(env, "MMU_TLB_ASID_BITS", if (xlen == 64) 16 else 9),
      tlbGlobal = envBool(env, "MMU_TLB_GLOBAL", true),
      itlbEntries = envInt(env, "MMU_ITLB_ENTRIES", 16),
      itlbWays = envInt(env, "MMU_ITLB_WAYS", 2),
      dtlbEntries = envInt(env, "MMU_DTLB_ENTRIES", 32),
      dtlbWays = envInt(env, "MMU_DTLB_WAYS", 4),
      utlbEntries = envInt(env, "MMU_UTLB_ENTRIES", 32),
      utlbWays = envInt(env, "MMU_UTLB_WAYS", 4),
      utlbLookupPorts = envInt(env, "UTLB_LOOKUP_PORTS", 1),
      tlbSuperpage = envBool(env, "MMU_TLB_SUPERPAGE", false),
      tlbSuperpageEntries = envInt(env, "MMU_TLB_SUPERPAGE_ENTRIES", 4),
      tlbMissQueueMode = envString(env, "MMU_TLB_MISS_QUEUE_MODE", "follow_tlb"),
      itlbMissQueueEntries = envInt(env, "MMU_ITLB_MISS_QUEUE_ENTRIES", 4),
      dtlbMissQueueEntries = envInt(env, "MMU_DTLB_MISS_QUEUE_ENTRIES", 4),
      utlbMissQueueEntries = envInt(env, "MMU_UTLB_MISS_QUEUE_ENTRIES", 8),
      globalMissQueueEntries = envInt(env, "MMU_GLOBAL_MISS_QUEUE_ENTRIES", 8),
      refillPorts = envInt(env, "MMU_TLB_REFILL_PORTS", 1),
      csrMstatusReadMask = envInt(env, "CSR_MSTATUS_READ_MASK", mstatusReadMask.toInt),
      csrMstatusReadSet = envInt(env, "CSR_MSTATUS_READ_SET", mstatusReadSet.toInt),
      csrMstatusWriteMask = envInt(env, "CSR_MSTATUS_WRITE_MASK", mstatusWriteMask.toInt),
      csrSatpReadMask = envLong(env, "CSR_SATP_READ_MASK", satpReadMask.toLong),
      csrSatpReadSet = envLong(env, "CSR_SATP_READ_SET", satpReadSet.toLong),
      csrSatpWriteMask = envLong(env, "CSR_SATP_WRITE_MASK", satpWriteMask.toLong),
      hasS = hasS,
      hasU = hasU,
      hasSTimerIrq = envBool(env, "HAS_S_TIMER_IRQ", true),
      hasSExternalIrq = envBool(env, "HAS_S_EXTERNAL_IRQ", true),
      timerPath = envString(env, "TIMER_PATH", "clint_mtip"),
      plicSources = envInt(env, "PLIC_SOURCES", 32),
      plicPriorityBits = envInt(env, "PLIC_PRIORITY_BITS", 3),
      plicMaxPriority = envInt(env, "PLIC_MAX_PRIORITY", 7),
      plicBase = envLong(env, "PLIC_BASE", 0x0c000000L),
      csrViewMode = envString(env, "CSR_VIEW_MODE", "bound"),
      csrMissingMode = envString(env, "CSR_MISSING_MODE", "illegal"),
      pmpEnabled = envBool(env, "HAS_PMP", false),
      pmpEntries = envInt(env, "PMP_ENTRIES", 16),
      csrEnabled = envAddressSet(env, "CSR_ENABLED", DefaultCsrAddresses) ++
        IdentityCsrAddresses
    )
  }
}
