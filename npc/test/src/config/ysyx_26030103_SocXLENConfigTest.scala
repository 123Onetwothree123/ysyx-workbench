package ysyx_26030103.config

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import _root_.ysyx_26030103.common.ysyx_26030103_PMARegion

/** ysyxSoC 接口 XLEN 与 CPU XLEN 解耦的配置规则测试。
  *
  * 配置层必须允许 32/64 的四种组合；64 位 SoC 由顶层
  * `ysyx_26030103_AXI5WidthBridge` 接入 RTL，PMA 低 4GiB 限制只对
  * 实际 32 位 SoC 地址宽度生效。
  */
class ysyx_26030103_SocXLENConfigTest extends AnyFlatSpec with Matchers {
  private def cpuAddressWidth(xlen: Int): Int = if (xlen == 64) 64 else 32

  "NPCConfig" should "支持 CPU/SoC XLEN 的四种独立组合" in {
    val cases = Seq(
      (32, 32, 32),
      (64, 32, 32),
      (32, 64, 64),
      (64, 64, 64)
    )
    for ((cpuXlen, socXlen, expectedSocXlen) <- cases) {
      val config = ysyx_26030103_NPCConfig(
        XLEN = cpuXlen,
        AddressWidth = cpuAddressWidth(cpuXlen),
        SocXLENFollowCpu = false,
        SocXLEN = socXlen
      )
      withClue(s"CPU XLEN=$cpuXlen, SoC XLEN=$socXlen: ") {
        config.EffectiveSocXLEN should be(expectedSocXlen)
        config.SocXLENNeedsAxiBridge should be(expectedSocXlen == 64)
      }
    }
  }

  it should "FOLLOW_CPU=y 时 SoC XLEN 跟随 CPU XLEN" in {
    for (cpuXlen <- Seq(32, 64)) {
      // 保留值刻意与 CPU 不同，确认 FOLLOW=y 时 SOC_XLEN 不参与生效值。
      val config = ysyx_26030103_NPCConfig(
        XLEN = cpuXlen,
        AddressWidth = cpuAddressWidth(cpuXlen),
        SocXLENFollowCpu = true,
        SocXLEN = if (cpuXlen == 32) 64 else 32
      )
      config.EffectiveSocXLEN should be(cpuXlen)
      config.SocXLENNeedsAxiBridge should be(cpuXlen == 64)
    }
  }

  it should "默认不跟随 CPU，SoC 保持 32 位" in {
    val config = ysyx_26030103_NPCConfig()
    config.SocXLENFollowCpu should be(false)
    config.SocXLEN should be(32)
    config.EffectiveSocXLEN should be(32)
    config.SocXLENNeedsAxiBridge should be(false)
  }

  it should "拒绝 FOLLOW_CPU=n 时 32/64 之外的 SOC_XLEN" in {
    for (bad <- Seq(0, 33, 48, 63, 128)) {
      val error = intercept[IllegalArgumentException] {
        ysyx_26030103_NPCConfig(SocXLENFollowCpu = false, SocXLEN = bad)
      }
      withClue(s"SOC_XLEN=$bad: ") {
        error.getMessage should include("SOC_XLEN")
        error.getMessage should include("32 或 64")
      }
    }
  }

  it should "只在 SoC 地址宽度为 32 时限制 PMA 位于低 4GiB" in {
    val HighBase = 0x180000000L
    val HighRegions = Seq(
      ysyx_26030103_PMARegion(HighBase, 0x10000L, Executable = true)
    )
    val Soc64 = ysyx_26030103_NPCConfig(
      XLEN = 64,
      AddressWidth = 64,
      ResetAddr = HighBase,
      PMARegions = HighRegions,
      SocXLENFollowCpu = false,
      SocXLEN = 64
    )
    Soc64.EffectiveSocXLEN should be(64)
    Soc64.SocXLENNeedsAxiBridge should be(true)

    val error = intercept[IllegalArgumentException] {
      ysyx_26030103_NPCConfig(
        XLEN = 64,
        AddressWidth = 64,
        ResetAddr = HighBase,
        PMARegions = HighRegions,
        SocXLENFollowCpu = false,
        SocXLEN = 32
      )
    }
    error.getMessage should include("低 4GiB")
    error.getMessage should include("EffectiveSocXLEN=32")
  }

  it should "在 Describe 中报告生效的 SoC XLEN" in {
    val fixed = ysyx_26030103_NPCConfig(
      XLEN = 64,
      AddressWidth = 64,
      SocXLENFollowCpu = false,
      SocXLEN = 32
    )
    fixed.Describe should include("soc_xlen=32")

    val followed = ysyx_26030103_NPCConfig(
      XLEN = 64,
      AddressWidth = 64,
      SocXLENFollowCpu = true
    )
    followed.Describe should include("soc_xlen=64(follow)")
  }
}
