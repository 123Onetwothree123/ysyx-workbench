package ysyx_26030103.config

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import _root_.ysyx_26030103.fpu.ysyx_26030103_FPU

/** F/D 与 FPU 后端配置规则。
  *
  * HardFloat 与 Fudian 均已接入 vendor RTL：NPCConfig 同时允许
  * RISCV_F/D + FPU_IMPL=hardfloat/fudian。external 仍必须在 elaboration
  * 前明确拒绝，避免顶层没有 FPU 实例却宣称支持 F/D。
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

  it should "接受 HardFloat F/D 配置" in {
    val f32 = ysyx_26030103_NPCConfig(
      UseF = true,
      FPUImpl = ysyx_26030103_FPUImpl.HardFloat,
      FPULen = 32
    )
    f32.FPUBackendImplemented should be(true)
    f32.FPUProfile.HardFloat.Recode should be(true)
    val fd = ysyx_26030103_NPCConfig(
      XLEN = 64,
      AddressWidth = 64,
      UseF = true,
      UseD = true,
      FPUImpl = ysyx_26030103_FPUImpl.HardFloat,
      FPULen = 64
    )
    fd.ISAString should include("FD")
  }

  it should "拒绝 HardFloat 的 recode=off" in {
    val error = intercept[IllegalArgumentException] {
      ysyx_26030103_NPCConfig(
        UseF = true,
        FPUImpl = ysyx_26030103_FPUImpl.HardFloat,
        FPUProfile = ysyx_26030103_FPUProfile(
          HardFloat = ysyx_26030103_HardFloatParams(Recode = false)
        )
      )
    }
    error.getMessage should include("recoded")
  }

  it should "接受 RV32D + FLEN=64 并生成 8 字节访存配置" in {
    val config = ysyx_26030103_NPCConfig(
      XLEN = 32,
      UseF = true,
      UseD = true,
      FPUImpl = ysyx_26030103_FPUImpl.HardFloat,
      FPULen = 64
    )
    config.XLEN should be(32)
    config.HasWideMemAccess should be(true)
    config.ISAString should include("RV32")
    config.ISAString should include("FD")
  }

  it should "拒绝 RV32D 使用不足两个 word 的 DCache line" in {
    val error = intercept[IllegalArgumentException] {
      ysyx_26030103_NPCConfig(
        XLEN = 32,
        UseF = true,
        UseD = true,
        FPUImpl = ysyx_26030103_FPUImpl.HardFloat,
        FPULen = 64,
        DCacheEnable = true,
        DCacheBlockSizeLog2 = 2
      )
    }
    error.getMessage should include("cache line")
  }

  it should "接受已接入的 Fudian 后端" in {
    val config = ysyx_26030103_NPCConfig(
      XLEN = 64,
      AddressWidth = 64,
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

  it should "在摘要中区分 fused/exact FMA 与流水深度" in {
    val fused = ysyx_26030103_FPUProfile(Pipeline = 0, FMA = true)
    fused.Describe(ysyx_26030103_FPUImpl.Fudian) should include("fma=fused")
    fused.Describe(ysyx_26030103_FPUImpl.HardFloat) should include("fma=fused")
    val exact = ysyx_26030103_FPUProfile(Pipeline = 4, FMA = false)
    exact.Describe(ysyx_26030103_FPUImpl.Fudian) should include("fma=exact")
    exact.Describe(ysyx_26030103_FPUImpl.Fudian) should include("pipeline=4")
    exact.Describe(ysyx_26030103_FPUImpl.HardFloat) should include("fma=exact")
    exact.Describe(ysyx_26030103_FPUImpl.HardFloat) should include("pipeline=4")
  }

  it should "接受 FMA=n 与 Fudian 独立 DIV/SQRT 配置" in {
    val config = ysyx_26030103_NPCConfig(
      XLEN = 64,
      AddressWidth = 64,
      UseF = true,
      UseD = true,
      FPUImpl = ysyx_26030103_FPUImpl.Fudian,
      FPUProfile = ysyx_26030103_FPUProfile(
        Pipeline = 3,
        FMA = false,
        Fudian = ysyx_26030103_FudianParams(SharedDivSqrt = false)
      )
    )
    config.FPUProfile.FMA should be(false)
    config.FPUProfile.Pipeline should be(3)
    config.Describe should include("fma=exact")
    config.Describe should include("pipeline=3")
    config.Describe should include("shared_divsqrt=off")
  }

  "ysyx_26030103_FPU 工厂" should "对未接入后端明确报错" in {
    val config = ysyx_26030103_NPCConfig()
    // 工厂自身会先检查 RISCV_F，不允许在软浮点配置下静默创建。
    intercept[IllegalArgumentException] {
      ysyx_26030103_FPU(config)
    }.getMessage should include("RISCV_F=y")
  }
}
