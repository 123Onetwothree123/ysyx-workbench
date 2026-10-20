package ysyx_26030103.config

/** MMU/TLB 的 elaboration 期参数。
  *
  * 这个类目前只描述配置，不被 RTL 实例化；后续 MMU/TLB 接入时由
  * Elaborate 从 Kconfig 生成的环境变量构造。默认值就是标准 Sv32 balanced
  * profile，非标准参数通过 Kconfig 和生成器校验。
  */
case class ysyx_26030103_MMUProfile(
    enabled: Boolean = false,
    pageSizeBytes: Int = 4096,
    levels: Int = 2,
    pteSizeBytes: Int = 4,
    pageTableSizeBytes: Int = 4096,
    pteFormat: String = "riscv32",
    superpage: Boolean = false,
    adMode: String = "fault",
    cacheMode: String = "pipt",
    ptwPath: String = "dedicated",
    ptwArch: String = "independent",
    ptwOutstanding: Int = 2,
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
    tlbSuperpage: Boolean = false,
    tlbMissQueueMode: String = "follow_tlb",
    itlbMissQueueEntries: Int = 4,
    dtlbMissQueueEntries: Int = 4,
    utlbMissQueueEntries: Int = 8,
    globalMissQueueEntries: Int = 8,
    refillPorts: Int = 1
) {
  private def isPowerOfTwo(value: Int): Boolean =
    value > 0 && (value & (value - 1)) == 0

  private def requirePowerOfTwo(name: String, value: Int): Unit =
    require(isPowerOfTwo(value), s"$name=$value 必须是正的 2 的幂")

  requirePowerOfTwo("pageSizeBytes", pageSizeBytes)
  requirePowerOfTwo("pteSizeBytes", pteSizeBytes)
  requirePowerOfTwo("pageTableSizeBytes", pageTableSizeBytes)
  require(levels >= 1, s"levels=$levels 必须 >= 1")
  require(
    pageTableSizeBytes % pteSizeBytes == 0,
    s"pageTableSizeBytes=$pageTableSizeBytes 必须能被 pteSizeBytes=$pteSizeBytes 整除"
  )
  requirePowerOfTwo("每级页表项数", pageTableSizeBytes / pteSizeBytes)
  require(ptwOutstanding >= 1, s"ptwOutstanding=$ptwOutstanding 必须 >= 1")
  require(tlbAsidBits >= 0 && tlbAsidBits <= 9, "tlbAsidBits 必须在 0..9")
  require(refillPorts >= 1, "refillPorts 必须 >= 1")

  if (tlbEnabled) {
    requirePowerOfTwo("itlbEntries", itlbEntries)
    requirePowerOfTwo("dtlbEntries", dtlbEntries)
    requirePowerOfTwo("utlbEntries", utlbEntries)
    require(itlbEntries % itlbWays == 0, "itlbEntries 必须能被 itlbWays 整除")
    require(dtlbEntries % dtlbWays == 0, "dtlbEntries 必须能被 dtlbWays 整除")
    require(utlbEntries % utlbWays == 0, "utlbEntries 必须能被 utlbWays 整除")
  }

  final val pageBits: Int = Integer.numberOfTrailingZeros(pageSizeBytes)
  final val tableEntries: Int = pageTableSizeBytes / pteSizeBytes
  final val indexBitsPerLevel: Int = Integer.numberOfTrailingZeros(tableEntries)
  final val computedAddressBits: Int = pageBits + levels * indexBitsPerLevel
  final val wellFormed: Boolean = computedAddressBits == 32
  final val standardSv32: Boolean =
    pageSizeBytes == 4096 && levels == 2 && pteSizeBytes == 4 &&
      pageTableSizeBytes == 4096 && pteFormat == "riscv32"

  def describe: String =
    s"MMU(enabled=$enabled profile=$pteFormat page=$pageSizeBytes levels=$levels " +
      s"pte=$pteSizeBytes table=$pageTableSizeBytes ptw=$ptwPath/$ptwArch/$ptwOutstanding) " +
      s"TLB(enabled=$tlbEnabled org=$tlbOrganization storage=$tlbStorage " +
      s"repl=$tlbReplacement asid=$tlbAsidBits)"
}

object ysyx_26030103_MMUProfile {
  private def envBool(env: String => Option[String], key: String, fallback: Boolean): Boolean =
    env(key).filter(_.nonEmpty).map(v => v == "y" || v == "1").getOrElse(fallback)

  private def envInt(env: String => Option[String], key: String, fallback: Int): Int =
    env(key).filter(_.nonEmpty).map(_.toInt).getOrElse(fallback)

  private def envString(env: String => Option[String], key: String, fallback: String): String =
    env(key).filter(_.nonEmpty).getOrElse(fallback)

  /** 从 Kconfig 生成的环境变量构造；默认标准 Sv32 balanced。 */
  def fromEnv(env: String => Option[String]): ysyx_26030103_MMUProfile =
    ysyx_26030103_MMUProfile(
      enabled = envBool(env, "MMU_ENABLED", false),
      pageSizeBytes = envInt(env, "MMU_PAGE_SIZE_BYTES", 4096),
      levels = envInt(env, "MMU_LEVELS", 2),
      pteSizeBytes = envInt(env, "MMU_PTE_SIZE_BYTES", 4),
      pageTableSizeBytes = envInt(env, "MMU_PAGE_TABLE_SIZE_BYTES", 4096),
      pteFormat = envString(env, "MMU_PTE_FORMAT", "riscv32"),
      superpage = envBool(env, "MMU_SUPERPAGE", false),
      adMode = envString(env, "MMU_AD_MODE", "fault"),
      cacheMode = envString(env, "MMU_CACHE_MODE", "pipt"),
      ptwPath = envString(env, "MMU_PTW_PATH", "dedicated"),
      ptwArch = envString(env, "MMU_PTW_ARCH", "independent"),
      ptwOutstanding = envInt(env, "MMU_PTW_OUTSTANDING", 2),
      ptwOrder = envString(env, "MMU_PTW_ORDER", "in_order"),
      ptwCache = envBool(env, "MMU_PTW_CACHE", false),
      ptwCacheEntries = envInt(env, "MMU_PTW_CACHE_ENTRIES", 16),
      ptwCacheWays = envInt(env, "MMU_PTW_CACHE_WAYS", 2),
      tlbEnabled = envBool(env, "MMU_TLB_ENABLED", false),
      tlbOrganization = envString(env, "MMU_TLB_ORGANIZATION", "split"),
      tlbStorage = envString(env, "MMU_TLB_STORAGE", "reg"),
      tlbReplacement = envString(env, "MMU_TLB_REPLACEMENT", "plru"),
      tlbAsidBits = envInt(env, "MMU_TLB_ASID_BITS", 9),
      tlbGlobal = envBool(env, "MMU_TLB_GLOBAL", true),
      itlbEntries = envInt(env, "MMU_ITLB_ENTRIES", 16),
      itlbWays = envInt(env, "MMU_ITLB_WAYS", 2),
      dtlbEntries = envInt(env, "MMU_DTLB_ENTRIES", 32),
      dtlbWays = envInt(env, "MMU_DTLB_WAYS", 4),
      utlbEntries = envInt(env, "MMU_UTLB_ENTRIES", 32),
      utlbWays = envInt(env, "MMU_UTLB_WAYS", 4),
      tlbSuperpage = envBool(env, "MMU_TLB_SUPERPAGE", false),
      tlbMissQueueMode = envString(env, "MMU_TLB_MISS_QUEUE_MODE", "follow_tlb"),
      itlbMissQueueEntries = envInt(env, "MMU_ITLB_MISS_QUEUE_ENTRIES", 4),
      dtlbMissQueueEntries = envInt(env, "MMU_DTLB_MISS_QUEUE_ENTRIES", 4),
      utlbMissQueueEntries = envInt(env, "MMU_UTLB_MISS_QUEUE_ENTRIES", 8),
      globalMissQueueEntries = envInt(env, "MMU_GLOBAL_MISS_QUEUE_ENTRIES", 8),
      refillPorts = envInt(env, "MMU_TLB_REFILL_PORTS", 1)
    )
}
