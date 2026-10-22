package ysyx_26030103.config

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import _root_.ysyx_26030103.common._

/** RV64 地址宽度与 SoC 边界的 elaboration 规则测试。 */
class ysyx_26030103_RV64AddressConfigTest extends AnyFlatSpec with Matchers {
  "NPCConfig" should "接受 XLEN=64 + AddressWidth=64 + ICache/DCache" in {
    val config = ysyx_26030103_NPCConfig(
      XLEN = 64,
      AddressWidth = 64,
      ICacheEnable = true,
      DCacheEnable = true
    )
    config.AddressWidth should be(64)
    config.DCacheEnable should be(true)
  }

  it should "拒绝 XLEN=64 使用 32 位 AddressWidth" in {
    val error = intercept[IllegalArgumentException] {
      ysyx_26030103_NPCConfig(XLEN = 64, AddressWidth = 32)
    }
    error.getMessage should include("AddressWidth")
  }

  it should "拒绝 XLEN=64 启用 MMU/Sv39" in {
    val error = intercept[IllegalArgumentException] {
      ysyx_26030103_NPCConfig(
        XLEN = 64,
        AddressWidth = 64,
        MMU = ysyx_26030103_MMUProfile(enabled = true)
      )
    }
    error.getMessage should include("MMU")
  }

  it should "拒绝 XLEN=64 且 cache line 放不下两个 word 的 DCache" in {
    val error = intercept[IllegalArgumentException] {
      ysyx_26030103_NPCConfig(
        XLEN = 64,
        AddressWidth = 64,
        ICacheEnable = false,
        DCacheEnable = true,
        DCacheBlockSizeLog2 = 2
      )
    }
    error.getMessage should include("cache line")
  }

  it should "拒绝顶层 64 位地址配置把 PMA 放到 4GiB 以上" in {
    val error = intercept[IllegalArgumentException] {
      ysyx_26030103_NPCConfig(
        XLEN = 64,
        AddressWidth = 64,
        PMARegions = Seq(
          ysyx_26030103_PMARegion(
            0x180000000L,
            0x1000L,
            Cacheable = true,
            Executable = true
          )
        )
      )
    }
    error.getMessage should include("低 4GiB")
  }

  "PMA" should "允许叶模块测试使用高于 4GiB 的物理区域" in {
    val region = ysyx_26030103_PMARegion(
      0x180000000L,
      0x10000L,
      Cacheable = true,
      Executable = true
    )
    region.EndExclusive should be(0x180010000L)
  }
}
