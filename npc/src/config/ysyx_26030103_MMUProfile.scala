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
  require((0 to 7).forall(b => Seq(validBit, readBit, writeBit, executeBit,
    userBit, globalBit, aBit, dBit).forall(x => x >= 0 && x < pteBits)),
    "PTE 标志位非法")
}

/** MMU/TLB 的 elaboration 期参数。
  *
  * 这个类由 Elaborate 从 Kconfig 生成的环境变量构造，并驱动 MMU/TLB RTL。
  * 默认值就是标准 Sv32 balanced profile；RV64 使用 xlen=64 的标准 Sv39
  * （3 级/8B PTE）或 Sv48（4 级/8B PTE）。非标准参数由生成器和
  * elaboration 校验；RV32 的既有自定义 32 位几何保持兼容。
  */
case class ysyx_26030103_MMUProfile(
    enabled: Boolean = false,
    // MMU 所属的架构位宽。RV32 默认 32；RV64/Sv39 由 elaborator 设置为 64。
    xlen: Int = 32,
    pageSizeBytes: Int = 4096,
    levels: Int = 2,
    pteSizeBytes: Int = 4,
    pageTableSizeBytes: Int = 4096,
    pteFormat: String = "riscv32",
    pteConfig: ysyx_26030103_PteConfig = ysyx_26030103_PteConfig(),
    superpage: Boolean = false,
    adMode: String = "fault",
    cacheMode: String = "pipt",
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
  require(levels >= 1, s"levels=$levels 必须 >= 1")
  require(
    pageTableSizeBytes % pteSizeBytes == 0,
    s"pageTableSizeBytes=$pageTableSizeBytes 必须能被 pteSizeBytes=$pteSizeBytes 整除"
  )
  requirePowerOfTwo("每级页表项数", pageTableSizeBytes / pteSizeBytes)
  require(ptwOutstanding >= 1 && ptwOutstanding <= 16,
    s"ptwOutstanding=$ptwOutstanding 必须在 1..16（PTW 事务 ID 为 4 位）")
  require(ptwBusOutstanding >= 0 && ptwBusOutstanding <= 16,
    s"ptwBusOutstanding=$ptwBusOutstanding 必须在 0..16（0 表示跟随 ptwOutstanding）")
  require(Set("independent", "pipelined").contains(ptwArch),
    "MMU_PTW_ARCH 必须是 independent/pipelined")
  require(Set("in_order", "out_of_order").contains(ptwOrder),
    "MMU_PTW_ORDER 必须是 in_order/out_of_order")
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

  final val pageBits: Int = Integer.numberOfTrailingZeros(pageSizeBytes)
  final val tableEntries: Int = pageTableSizeBytes / pteSizeBytes
  final val indexBitsPerLevel: Int = Integer.numberOfTrailingZeros(tableEntries)
  final val computedAddressBits: Int = pageBits + levels * indexBitsPerLevel
  // Sv32 的虚拟地址是 32 位；Sv39/Sv48 分别使用 39/48 位有效虚拟地址，
  // 其余高位必须是指示位的符号扩展（由 PTW/MMU 检查）。
  final val wellFormed: Boolean =
    if (xlen == 32) computedAddressBits == 32
    else computedAddressBits == 39 || computedAddressBits == 48
  final val standardSv32: Boolean =
    xlen == 32 && pageSizeBytes == 4096 && levels == 2 && pteSizeBytes == 4 &&
      pageTableSizeBytes == 4096 && pteFormat == "riscv32"
  final val standardSv39: Boolean =
    xlen == 64 && pageSizeBytes == 4096 && levels == 3 && pteSizeBytes == 8 &&
      pageTableSizeBytes == 4096 && pteFormat == "riscv64" &&
      computedAddressBits == 39
  final val standardSv48: Boolean =
    xlen == 64 && pageSizeBytes == 4096 && levels == 4 && pteSizeBytes == 8 &&
      pageTableSizeBytes == 4096 && pteFormat == "riscv64" &&
      computedAddressBits == 48
  // RTL 只对标准 Sv32/Sv39/Sv48 提供完整语义；其它 32 位地址几何继续
  // 用于已有自定义 profile 的 elaboration 测试。
  require(xlen == 32 || !enabled || wellFormed,
    if (xlen == 32)
      s"RV32 MMU 要求 pageBits($pageBits)+levels($levels)*indexBits($indexBitsPerLevel)=32，" +
        s"实际为 $computedAddressBits"
    else
      s"RV64 MMU 只支持 Sv39(39) 或 Sv48(48) 地址几何，实际为 $computedAddressBits；" +
        "请设置 MMU_LEVELS=3/4、MMU_PTE_SIZE_BYTES=8、MMU_PAGE_TABLE_SIZE_BYTES=4096")
  require(xlen == 64 || pteFormat != "riscv64",
    "RV32 配置不能使用 riscv64 PTE 编码")
  require(xlen == 32 || !enabled || standardSv39 || standardSv48,
    "RV64 HAS_MMU=y 只支持标准 Sv39/Sv48：page=4096, levels=3/4, PTE=8B, " +
      "page table=4096, pteFormat=riscv64")
  // satp.MODE 编码：Sv32=1, Sv39=8, Sv48=9。0 始终表示关闭翻译。
  final val satpMode: Int =
    if (xlen == 32) 1
    else if (computedAddressBits == 39) 8
    else if (computedAddressBits == 48) 9
    else 0

  def describe: String =
    s"MMU(enabled=$enabled xlen=$xlen va=$computedAddressBits satpMode=$satpMode " +
      s"profile=$pteFormat page=$pageSizeBytes levels=$levels " +
      s"pte=$pteSizeBytes table=$pageTableSizeBytes " +
      s"ptw=$ptwPath/$ptwArch/$ptwOutstanding/bus$ptwBusOutstanding) " +
      s"TLB(enabled=$tlbEnabled org=$tlbOrganization storage=$tlbStorage " +
      s"repl=$tlbReplacement asid=$tlbAsidBits)"
}

object ysyx_26030103_MMUProfile {
  /** 配置生成器未显式给出 CSR 集合时的标准实现集合。 */
  val DefaultCsrAddresses: Set[Int] = Set(
    0x300, 0x301, 0x302, 0x303, 0x304, 0x305, 0x306,
    0x320, 0x340, 0x341, 0x342, 0x343, 0x344,
    0x100, 0x104, 0x105, 0x106, 0x140, 0x141, 0x142,
    0x143, 0x144, 0x180,
    0xb00, 0xb02, 0xb80, 0xb82, 0xf11, 0xf12
  )

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
      csrEnabled = envAddressSet(env, "CSR_ENABLED", DefaultCsrAddresses)
    )
  }
}
