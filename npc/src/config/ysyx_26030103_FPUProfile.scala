package ysyx_26030103.config

/** 由 Kconfig 选择的 FPU 后端。
  *
  * `Implemented` 表示当前仓库是否已经接入该后端的 RTL。HardFloat 已通过
  * vendor/berkeley-hardfloat、Fudian 已通过 vendor/fudian 的 source-level
  * 编译接入；External 仍只是预留，必须在 elaboration 前拒绝，不能静默
  * 生成一个没有浮点数据通路的 F/D 核。
  */
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

/** Berkeley HardFloat 的公开集成参数。
  *
  * 只放 vendor 顶层公开的参数，不暴露 recoded 管线内部寄存器、CSA
  * 结构等实现细节。
  */
case class ysyx_26030103_HardFloatParams(
    Recode: Boolean = true,
    DetectTininessBeforeRounding: Boolean = false
)

/** Fudian 的公开集成参数。
  *
  * `SharedDivSqrt=true` 且启用 D 时，F/D 共用一套 D 精度 `FDIV` 迭代
  * 硬件：F 输入精确提升到 D，结果按请求舍入模式回舍到 S；`false` 时
  * F 与 D 各保留一套独立 `FDIV`。
  */
case class ysyx_26030103_FudianParams(
    SharedDivSqrt: Boolean = true
)

/** FPU 的通用公开参数 profile。
  *
  * `FPUImpl`/`FPULen` 单独放在 NPCConfig 顶层，便于上层快速判断是否
  * 启用以及寄存器宽度；本 profile 只承载实现相关的公开调优参数。
  *
  * `Pipeline` 是响应边界真实有效的流水级数；`FMA` 选择 vendor 融合
  * 乘加（on）或基于 vendor 基础模块的单次舍入非融合数据通路（off）。
  */
case class ysyx_26030103_FPUProfile(
    Pipeline: Int = 0,
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

  /** 配置摘要只打印选中后端的参数，避免把未选后端的值误认为生效。 */
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
