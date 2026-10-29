package ysyx_26030103.audit

import _root_.ysyx_26030103.config._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** MMU/Cache 模式组合的 elaboration 期校验。
  *
  * `MMU_CACHE_MODE=vipt` 只有在 MMU 真正存在时才有意义：VIPT 依赖
  * IFU/LSU 的翻译通路把 VA 标签和 PA 一起送到 Cache。关闭 MMU 却保留
  * vipt 配置时，顶层 `parallelDataVipt` 仍会按 vipt 生成 LSU 端口，但
  * 这个配置没有 MMU 驱动，LSU/DCache 会永远等不到物理地址。必须由
  * NPCConfig 在 elaboration 期明确拒绝。
  */
class ysyx_26030103_MMUViptConfigAuditTest
    extends AnyFlatSpec with Matchers {

  "NPCConfig" should "拒绝无 MMU 但 MMU_CACHE_MODE=vipt 的配置" in {
    val badMmu = ysyx_26030103_MMUProfile(
      enabled = false,
      cacheMode = "vipt"
    )
    val error = intercept[IllegalArgumentException] {
      ysyx_26030103_NPCConfig(MMU = badMmu)
    }
    error.getMessage should include("MMU_CACHE_MODE=vipt")
    error.getMessage should include("HAS_MMU")
  }

  it should "接受有 MMU 的 vipt 配置" in {
    val mmu = ysyx_26030103_MMUProfile(
      enabled = true,
      cacheMode = "vipt",
      pageSizeBytes = 4096
    )
    val config = ysyx_26030103_NPCConfig(MMU = mmu)
    config.MMU.cacheMode should be("vipt")
  }

  it should "接受无 MMU 的默认 pipt 配置" in {
    val config = ysyx_26030103_NPCConfig(
      MMU = ysyx_26030103_MMUProfile(enabled = false)
    )
    config.MMU.enabled should be(false)
    config.MMU.cacheMode should be("pipt")
  }
}
