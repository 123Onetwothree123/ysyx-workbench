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

  it should "接受 XLEN=64 标准 Sv39/Sv48 MMU" in {
    def sv39 = ysyx_26030103_MMUProfile(
      enabled = true,
      xlen = 64,
      levels = 3,
      pteSizeBytes = 8,
      pteFormat = "riscv64",
      pteConfig = ysyx_26030103_PteConfig(pteBits = 64, ppnHigh = 53),
      tlbEnabled = true,
      tlbAsidBits = 16
    )
    def sv48 = sv39.copy(levels = 4)
    ysyx_26030103_NPCConfig(XLEN = 64, AddressWidth = 64, MMU = sv39)
      .MMU.standardSv39 should be(true)
    ysyx_26030103_NPCConfig(XLEN = 64, AddressWidth = 64, MMU = sv48)
      .MMU.standardSv48 should be(true)
  }

  it should "拒绝 XLEN=64 的非标准页表几何" in {
    val error = intercept[IllegalArgumentException] {
      ysyx_26030103_MMUProfile(
        enabled = true,
        xlen = 64,
        levels = 2,
        pteSizeBytes = 4,
        pteFormat = "riscv32"
      )
    }
    error.getMessage should include("Sv39")
  }

  it should "fromEnv(XLEN=64) 默认生成标准 Sv39 profile" in {
    val p = ysyx_26030103_MMUProfile.fromEnv(_ => None, xlen = 64)
    p.standardSv39 should be(true)
    p.xlen should be(64)
    p.satpMode should be(8)
    p.pteConfig.ppnHigh should be(53)
  }

  it should "拒绝 XLEN=64 使用 RV32 的 MMU profile" in {
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
