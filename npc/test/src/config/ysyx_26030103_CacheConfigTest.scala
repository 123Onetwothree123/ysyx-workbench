package ysyx_26030103.config

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Cache/MMU 组合的 elaboration 期合法性检查。
  *
  * VIPT 用虚拟地址提前选 Cache index。index+块内偏移超出页内偏移时，
  * 不同虚拟页会把同一物理行放进不同 set：默认必须拒绝；显式打开
  * MMU_CACHE_VIPT_ALIAS_SAFE 后才允许，并由 Cache 硬件维护 alias。
  * 无论是否打开该选项，cache line 都不允许跨页。
  */
class ysyx_26030103_CacheConfigTest extends AnyFlatSpec with Matchers {
  "NPCConfig" should "拒绝 index+block 超出页内偏移的 VIPT ICache" in {
    val badMmu = ysyx_26030103_MMUProfile(
      enabled = true,
      cacheMode = "vipt",
      pageSizeBytes = 4096
    )
    val error = intercept[IllegalArgumentException] {
      ysyx_26030103_NPCConfig(
        ICacheEnable = true,
        ICacheBlockSizeLog2 = 6,
        ICacheIndexBits = 10,
        MMU = badMmu
      )
    }
    error.getMessage should include("VIPT ICache 非法")
    error.getMessage should include("ICacheIndexBits(10)")
    error.getMessage should include("ICacheBlockSizeLog2(6)")
    error.getMessage should include("(12)")
    error.getMessage should include("MMU_CACHE_VIPT_ALIAS_SAFE")
    error.getMessage should include("MMU_CACHE_MODE=pipt")
  }

  it should "拒绝 index+block 超出页内偏移的 VIPT DCache" in {
    val badMmu = ysyx_26030103_MMUProfile(
      enabled = true,
      cacheMode = "vipt",
      pageSizeBytes = 4096
    )
    val error = intercept[IllegalArgumentException] {
      ysyx_26030103_NPCConfig(
        ICacheEnable = false,
        DCacheEnable = true,
        DCacheBlockSizeLog2 = 5,
        DCacheIndexBits = 10,
        MMU = badMmu
      )
    }
    error.getMessage should include("VIPT DCache 非法")
    error.getMessage should include("DCacheIndexBits(10)")
    error.getMessage should include("DCacheBlockSizeLog2(5)")
  }

  it should "接受 index+block 落在页内的 VIPT 组合" in {
    val goodMmu = ysyx_26030103_MMUProfile(
      enabled = true,
      cacheMode = "vipt",
      pageSizeBytes = 4096
    )
    val config = ysyx_26030103_NPCConfig(
      ICacheEnable = true,
      ICacheBlockSizeLog2 = 4,
      ICacheIndexBits = 8,
      DCacheEnable = true,
      DCacheBlockSizeLog2 = 4,
      DCacheIndexBits = 8,
      MMU = goodMmu
    )
    config.MMU.pageBits should be(12)
    (config.ICacheIndexBits + config.ICacheBlockSizeLog2) should be <= 12
    (config.DCacheIndexBits + config.DCacheBlockSizeLog2) should be <= 12
  }

  it should "显式 alias 安全模式接受非标准页大小和 index 越过页内偏移" in {
    // pageBits=10, levels=2, 每级 11 位 -> 10+22=32，满足 RV32 自定义几何。
    val mmu = ysyx_26030103_MMUProfile(
      enabled = true,
      cacheMode = "vipt",
      cacheViptAliasSafe = true,
      pageSizeBytes = 1024,
      levels = 2,
      pageTableSizeBytes = 8192
    )
    mmu.pageBits should be(10)
    mmu.computedAddressBits should be(32)
    val config = ysyx_26030103_NPCConfig(
      ICacheEnable = true,
      ICacheBlockSizeLog2 = 4,
      ICacheIndexBits = 8, // 8+4=12 > 10，index 已包含 VPN 位
      DCacheEnable = true,
      DCacheBlockSizeLog2 = 4,
      DCacheIndexBits = 8,
      MMU = mmu
    )
    config.ICacheViptAliasHardware should be(true)
    config.DCacheViptAliasHardware should be(true)
  }

  it should "RV64 标准页 + 大 index 的 VIPT alias 配置也可显式启用" in {
    val mmu = ysyx_26030103_MMUProfile(
      enabled = true,
      xlen = 64,
      levels = 3,
      pteSizeBytes = 8,
      pteFormat = "riscv64",
      pteConfig = ysyx_26030103_PteConfig(pteBits = 64, ppnHigh = 53),
      cacheMode = "vipt",
      cacheViptAliasSafe = true
    )
    val config = ysyx_26030103_NPCConfig(
      XLEN = 64,
      AddressWidth = 64,
      ICacheEnable = true,
      ICacheBlockSizeLog2 = 4,
      ICacheIndexBits = 10, // 10+4=14 > 12
      DCacheEnable = true,
      DCacheBlockSizeLog2 = 4,
      DCacheIndexBits = 10,
      MMU = mmu
    )
    config.ICacheViptAliasHardware should be(true)
    config.DCacheViptAliasHardware should be(true)
  }

  it should "alias 安全模式仍拒绝大于页的 cache line" in {
    val mmu = ysyx_26030103_MMUProfile(
      enabled = true,
      cacheMode = "vipt",
      cacheViptAliasSafe = true,
      pageSizeBytes = 4096
    )
    val error = intercept[IllegalArgumentException] {
      ysyx_26030103_NPCConfig(
        ICacheEnable = true,
        ICacheBlockSizeLog2 = 13,
        ICacheIndexBits = 1,
        MMU = mmu
      )
    }
    error.getMessage should include("跨越虚拟页")
    error.getMessage should include("ICacheBlockSizeLog2(13)")

    val dcacheError = intercept[IllegalArgumentException] {
      ysyx_26030103_NPCConfig(
        ICacheEnable = false,
        DCacheEnable = true,
        DCacheBlockSizeLog2 = 13,
        DCacheIndexBits = 1,
        MMU = mmu
      )
    }
    dcacheError.getMessage should include("跨越虚拟页")
    dcacheError.getMessage should include("DCacheBlockSizeLog2(13)")
  }

  it should "Cache 关闭时不受 VIPT 页内几何限制" in {
    val mmu = ysyx_26030103_MMUProfile(
      enabled = true,
      cacheMode = "vipt",
      pageSizeBytes = 4096
    )
    // Cache 不接在翻译路径上时不会产生 alias，允许保留较大的几何参数。
    val config = ysyx_26030103_NPCConfig(
      ICacheEnable = false,
      ICacheBlockSizeLog2 = 6,
      ICacheIndexBits = 10,
      DCacheEnable = false,
      MMU = mmu
    )
    config.ICacheBlockSizeLog2 should be(6)
  }

  "MMUProfile" should "从环境变量读取 VIPT alias 安全开关" in {
    val env = Map(
      "MMU_ENABLED" -> "1",
      "MMU_CACHE_MODE" -> "vipt",
      "MMU_CACHE_VIPT_ALIAS_SAFE" -> "1"
    )
    val profile = ysyx_26030103_MMUProfile.fromEnv(env.get, xlen = 32)
    profile.cacheMode should be("vipt")
    profile.cacheViptAliasSafe should be(true)
  }

  it should "拒绝深度为 0 的 TLB miss queue" in {
    val globalError = intercept[IllegalArgumentException] {
      ysyx_26030103_MMUProfile(
        tlbEnabled = true,
        tlbMissQueueMode = "global",
        globalMissQueueEntries = 0
      )
    }
    globalError.getMessage should include("MMU_GLOBAL_MISS_QUEUE_ENTRIES")

    val followError = intercept[IllegalArgumentException] {
      ysyx_26030103_MMUProfile(
        tlbEnabled = true,
        tlbOrganization = "split",
        tlbMissQueueMode = "follow_tlb",
        itlbMissQueueEntries = 0
      )
    }
    followError.getMessage should include("MMU_ITLB_MISS_QUEUE_ENTRIES")
  }
}
