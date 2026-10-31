package ysyx_26030103.config

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** FPU 配置契约回归。
  *
  * RV64F 的浮点寄存器宽度是 64 位（FLEN=64）；FLEN=32 只适用于 RV32F。
  * NPCConfig 必须在构造期拒绝 RV64 + UseF + FPULen=32，而不是留到
  * elaboration 才生成寄存器宽度与 XLEN 不匹配的浮点数据通路。
  */
class ysyx_26030103_FpuConfigRegressionTest
    extends AnyFlatSpec with Matchers {
  "NPCConfig" should "拒绝 RV64 + FPULen=32 + UseF=true" in {
    an[IllegalArgumentException] should be thrownBy {
      ysyx_26030103_NPCConfig(
        XLEN = 64,
        AddressWidth = 64,
        UseF = true,
        UseD = false,
        FPUImpl = ysyx_26030103_FPUImpl.HardFloat,
        FPULen = 32
      )
    }
  }
}
