package ysyx_26030103.config

import _root_.ysyx_26030103.common.{ysyx_26030103_KconfigDefaults => KconfigDefaults}

// 由 Kconfig 选择的 FPU 后端；HardFloat/Fudian 已按源码接入，External 只是
// 预留，必须在 elaboration 前拒绝，不能静默生成没有浮点数据通路的核。
sealed trait ysyx_26030103_FPUImpl {
  def Name: String
  def Implemented: Boolean
}
object ysyx_26030103_FPUImpl {
  case object None extends ysyx_26030103_FPUImpl {
    val Name = "none"
    // none 不需要 RTL 后端，属于有效的“未启用 FPU”配置。
    val Implemented = true
  }
  case object HardFloat extends ysyx_26030103_FPUImpl {
    val Name = "hardfloat"
    val Implemented = true
  }
  case object Fudian extends ysyx_26030103_FPUImpl {
    val Name = "fudian"
    val Implemented = true
  }
  case object External extends ysyx_26030103_FPUImpl {
    val Name = "external"
    val Implemented = false
  }

  val All: Seq[ysyx_26030103_FPUImpl] =
    Seq(None, HardFloat, Fudian, External)

  def FromString(s: String): ysyx_26030103_FPUImpl = s.trim.toLowerCase match {
    case "none"      => None
    case "hardfloat" => HardFloat
    case "fudian"    => Fudian
    case "external"  => External
    case other =>
      throw new IllegalArgumentException(
        s"未知 FPU_IMPL=$other；只允许 ${All.map(_.Name).mkString("/")}"
      )
  }
}

// Berkeley HardFloat 公开集成参数；只放 vendor 顶层参数，不暴露 recode 管线/CSA 细节。
case class ysyx_26030103_HardFloatParams(
    Recode: Boolean = true,
    DetectTininessBeforeRounding: Boolean = false
)

/** Fudian 的公开集成参数。
  *
  * `SharedDivSqrt=true` 且启用 D 时，F/D 共用一套 D 精度 `FDIV`：F 输入精确
  * 提升到 D，结果按请求舍入模式回舍到 S；`false` 时各保留一套独立 `FDIV`。
  */
case class ysyx_26030103_FudianParams(
    SharedDivSqrt: Boolean = true
)

/** FPU 的通用公开参数 profile。
  *
  * `Pipeline` 是响应边界真实有效的流水级数；`FMA` 选择 vendor 融合乘加或
  * 基于 vendor 基础模块的单次舍入非融合数据通路。`FPUImpl`/`FPULen` 放在
  * NPCConfig 顶层，本 profile 只承载实现相关的公开调优参数。
  */
case class ysyx_26030103_FPUProfile(
    Pipeline: Int = KconfigDefaults.FpuPipeline,
    FMA: Boolean = true,
    DivSqrt: Boolean = true,
    Rounding: Boolean = true,
    Flags: Boolean = true,
    HardFloat: ysyx_26030103_HardFloatParams =
      ysyx_26030103_HardFloatParams(),
    Fudian: ysyx_26030103_FudianParams =
      ysyx_26030103_FudianParams()
) {
  private def OnOff(Flag: Boolean): String = if (Flag) "on" else "off"

  // 配置摘要只打印选中后端的参数，避免把未选后端的值误认为生效。
  def Describe(Impl: ysyx_26030103_FPUImpl): String = Impl match {
    case ysyx_26030103_FPUImpl.None => "none"
    case ysyx_26030103_FPUImpl.HardFloat =>
      s"pipeline=$Pipeline,fma=${if (FMA) "fused" else "exact"}," +
        s"divsqrt=${OnOff(DivSqrt)}," +
        s"rounding=${OnOff(Rounding)},flags=${OnOff(Flags)}," +
        s"recode=${OnOff(HardFloat.Recode)}," +
        s"tininess=${if (HardFloat.DetectTininessBeforeRounding) "before" else "after"}"
    case ysyx_26030103_FPUImpl.Fudian =>
      s"pipeline=$Pipeline,fma=${if (FMA) "fused" else "exact"}," +
        s"divsqrt=${OnOff(DivSqrt)}," +
        s"rounding=${OnOff(Rounding)},flags=${OnOff(Flags)}," +
        s"shared_divsqrt=${OnOff(Fudian.SharedDivSqrt)}"
    case ysyx_26030103_FPUImpl.External => "external(reserved)"
  }
}
