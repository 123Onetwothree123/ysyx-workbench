package ysyx_26030103.config

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import _root_.ysyx_26030103.fpu.ysyx_26030103_FPU

/** F/D 与 FPU 后端配置规则。
  *
  * Fudian 已接入 vendor RTL：配置结构可以表达，NPCConfig 也允许
  * RISCV_F/D + FPU_IMPL=fudian。HardFloat/External 仍未接入，必须在
  * elaboration 前拒绝，避免顶层没有 FPU 实例却宣称支持 F/D。
  */
class ysyx_26030103_FPUConfigTest extends AnyFlatSpec with Matchers {
  "NPCConfig" should "默认关闭 F/D 且后端为 none" in {
    val config = ysyx_26030103_NPCConfig()
    config.UseF should be(false)
    config.UseD should be(false)
    config.FPUImpl should be(ysyx_26030103_FPUImpl.None)
    config.FPULen should be(64)
    config.FPUProfile.Pipeline should be(0)
    config.FPUProfile.FMA should be(true)
    config.FPUProfile.DivSqrt should be(true)
    config.FPUProfile.Rounding should be(true)
    config.FPUProfile.Flags should be(true)
    config.FPUBackendImplemented should be(true)
    config.Describe should include("fpu=none/flen=64")
  }

  it should "拒绝只启用 D 而不启用 F" in {
    val error = intercept[IllegalArgumentException] {
      ysyx_26030103_NPCConfig(UseD = true)
    }
    error.getMessage should include("RISCV_D=y 依赖 RISCV_F=y")
  }

  it should "拒绝 D 配 FLEN=32" in {
    val error = intercept[IllegalArgumentException] {
      ysyx_26030103_NPCConfig(
        UseF = true,
        UseD = true,
        FPUImpl = ysyx_26030103_FPUImpl.HardFloat,
        FPULen = 32
      )
    }
    error.getMessage should include("FPU_FLEN=64")
  }

  it should "拒绝 F 但后端为 none" in {
    val error = intercept[IllegalArgumentException] {
      ysyx_26030103_NPCConfig(UseF = true, FPUImpl = ysyx_26030103_FPUImpl.None)
    }
    error.getMessage should include("FPU_IMPL=none")
  }

  it should "拒绝未接入的 HardFloat 后端，不能假支持" in {
    val error = intercept[IllegalArgumentException] {
      ysyx_26030103_NPCConfig(
        UseF = true,
        FPUImpl = ysyx_26030103_FPUImpl.HardFloat
      )
    }
    error.getMessage should include("hardfloat")
    error.getMessage should include("尚未接入 RTL")
  }

  it should "接受已接入的 Fudian 后端" in {
    val config = ysyx_26030103_NPCConfig(
      UseF = true,
      UseD = true,
      FPUImpl = ysyx_26030103_FPUImpl.Fudian
    )
    config.FPUBackendImplemented should be(true)
    config.Describe should include("fpu=fudian")
    config.Describe should include("shared_divsqrt=on")
  }

  it should "拒绝预留的 external 后端" in {
    val error = intercept[IllegalArgumentException] {
      ysyx_26030103_NPCConfig(
        UseF = true,
        FPUImpl = ysyx_26030103_FPUImpl.External
      )
    }
    error.getMessage should include("external")
    error.getMessage should include("预留")
  }

  it should "拒绝裁掉 FDIV/FSQRT 的 F/D 配置" in {
    val error = intercept[IllegalArgumentException] {
      ysyx_26030103_NPCConfig(
        UseF = true,
        FPUImpl = ysyx_26030103_FPUImpl.Fudian,
        FPUProfile = ysyx_26030103_FPUProfile(DivSqrt = false)
      )
    }
    error.getMessage should include("FPU_DIVSQRT=y")
  }

  it should "拒绝裁掉完整舍入模式的 F/D 配置" in {
    val error = intercept[IllegalArgumentException] {
      ysyx_26030103_NPCConfig(
        UseF = true,
        FPUImpl = ysyx_26030103_FPUImpl.Fudian,
        FPUProfile = ysyx_26030103_FPUProfile(Rounding = false)
      )
    }
    error.getMessage should include("FPU_ROUNDING=y")
  }

  it should "拒绝裁掉 fflags 的 F/D 配置" in {
    val error = intercept[IllegalArgumentException] {
      ysyx_26030103_NPCConfig(
        UseF = true,
        FPUImpl = ysyx_26030103_FPUImpl.Fudian,
        FPUProfile = ysyx_26030103_FPUProfile(Flags = false)
      )
    }
    error.getMessage should include("FPU_FLAGS=y")
  }

  it should "在 profile 层拒绝非法流水级数" in {
    val error = intercept[IllegalArgumentException] {
      ysyx_26030103_NPCConfig(
        UseF = true,
        FPUImpl = ysyx_26030103_FPUImpl.Fudian,
        FPUProfile = ysyx_26030103_FPUProfile(Pipeline = 9)
      )
    }
    error.getMessage should include("FPU_PIPELINE")
  }

  "FPUImpl" should "解析全部预留后端名称" in {
    ysyx_26030103_FPUImpl.FromString("none") should be(
      ysyx_26030103_FPUImpl.None
    )
    ysyx_26030103_FPUImpl.FromString("hardfloat") should be(
      ysyx_26030103_FPUImpl.HardFloat
    )
    ysyx_26030103_FPUImpl.FromString("fudian") should be(
      ysyx_26030103_FPUImpl.Fudian
    )
    ysyx_26030103_FPUImpl.FromString("external") should be(
      ysyx_26030103_FPUImpl.External
    )
    intercept[IllegalArgumentException] {
      ysyx_26030103_FPUImpl.FromString("bogus")
    }.getMessage should include("未知 FPU_IMPL")
  }

  "FPUProfile" should "只摘要选中后端的公开参数" in {
    val profile = ysyx_26030103_FPUProfile(
      Pipeline = 2,
      HardFloat = ysyx_26030103_HardFloatParams(
        Recode = false,
        DetectTininessBeforeRounding = true
      ),
      Fudian = ysyx_26030103_FudianParams(SharedDivSqrt = false)
    )
    profile.Describe(ysyx_26030103_FPUImpl.HardFloat) should include(
      "pipeline=2"
    )
    profile.Describe(ysyx_26030103_FPUImpl.HardFloat) should include(
      "recode=off"
    )
    profile.Describe(ysyx_26030103_FPUImpl.HardFloat) should include(
      "tininess=before"
    )
    profile.Describe(ysyx_26030103_FPUImpl.HardFloat) should not include
      "shared_divsqrt"
    profile.Describe(ysyx_26030103_FPUImpl.Fudian) should include(
      "shared_divsqrt=off"
    )
  }

  "ysyx_26030103_FPU 工厂" should "对未接入后端明确报错" in {
    val config = ysyx_26030103_NPCConfig()
    // 工厂自身会先检查 RISCV_F，不允许在软浮点配置下静默创建。
    intercept[IllegalArgumentException] {
      ysyx_26030103_FPU(config)
    }.getMessage should include("RISCV_F=y")
  }
}
