package ysyx_26030103.config

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ysyx_26030103_MDUConfigTest extends AnyFlatSpec with Matchers {
  "NPCConfig" should "default to the standard MDU backend" in {
    val config = ysyx_26030103_NPCConfig()
    config.MDUImpl should be(ysyx_26030103_MDUImpl.Standard)
    config.MULImpl should be(ysyx_26030103_MULImpl.ShiftAdd)
    config.DIVImpl should be(ysyx_26030103_DIVImpl.Restoring)
    config.MDUProfile.MUL.Width should be(32)
    config.MDUProfile.MUL.ShiftAdd.IterBits should be(1)
    config.MDUProfile.DIV.For(config.DIVImpl).Radix should be(2)
  }

  it should "keep MUL and DIV backend parameters independent" in {
    val profile = ysyx_26030103_MDUProfile(
      MUL = ysyx_26030103_MULProfile(
        Width = 64,
        Encoding = ysyx_26030103_MULEncoding.Booth,
        Radix = 8,
        ShiftAdd = ysyx_26030103_MULShiftAddParams(
          IterBits = 4,
          Split = 2,
          EarlyOut = true
        ),
        Compression = ysyx_26030103_MULCompressionParams(Pipeline = 2)
      ),
      DIV = ysyx_26030103_DIVProfile(
        Restoring = ysyx_26030103_DIVParams(2, 1, false),
        NonRestoring = ysyx_26030103_DIVParams(4, 2, true),
        SRT = ysyx_26030103_DIVParams(8, 3, true)
      )
    )
    val config = ysyx_26030103_NPCConfig(
      UseM = true,
      MULImpl = ysyx_26030103_MULImpl.ShiftAdd,
      DIVImpl = ysyx_26030103_DIVImpl.SRT,
      MDUProfile = profile
    )
    config.MDUProfile.MUL.Width should be(64)
    config.MDUProfile.MUL.Encoding should be(ysyx_26030103_MULEncoding.Booth)
    config.MDUProfile.MUL.ShiftAdd.Split should be(2)
    config.MDUProfile.DIV.For(ysyx_26030103_DIVImpl.Restoring).Radix should be(2)
    config.MDUProfile.DIV.For(ysyx_26030103_DIVImpl.NonRestoring).Radix should be(4)
    config.MDUProfile.DIV.For(ysyx_26030103_DIVImpl.SRT).Radix should be(8)
    config.MDUProfile.DIV.For(config.DIVImpl).IterBits should be(3)
  }

  it should "reject the reserved external MDU backend" in {
    val error = intercept[IllegalArgumentException] {
      ysyx_26030103_NPCConfig(
        UseM = true,
        MDUImpl = ysyx_26030103_MDUImpl.External
      )
    }
    error.getMessage should include("external")
  }

  it should "reject invalid MUL and DIV key parameters" in {
    val mulError = intercept[IllegalArgumentException] {
      ysyx_26030103_NPCConfig(
        UseM = true,
        MDUProfile = ysyx_26030103_MDUProfile(
          MUL = ysyx_26030103_MULProfile(
            ShiftAdd = ysyx_26030103_MULShiftAddParams(IterBits = 9)
          )
        )
      )
    }
    mulError.getMessage should include("MULIterBits")

    val divError = intercept[IllegalArgumentException] {
      ysyx_26030103_NPCConfig(
        UseM = true,
        MDUProfile = ysyx_26030103_MDUProfile(
          DIV = ysyx_26030103_DIVProfile(
            SRT = ysyx_26030103_DIVParams(Radix = 3)
          )
        )
      )
    }
    divError.getMessage should include("DIVRadix")
  }
}
