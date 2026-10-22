package ysyx_26030103.config

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Cache/MMU 组合的 elaboration 期合法性检查。
  *
  * VIPT 用虚拟地址提前选 Cache index；只要 index+块内偏移超出页内偏移，
  * 不同虚拟页就可能落到同一组并携带错误的物理 tag，必须拒绝而不是
  * 静默生成会 alias 的 RTL。
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

  "MMUProfile" should "拒绝深度为 0 的 TLB miss queue" in {
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
