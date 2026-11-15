package ysyx_26030103.config

import _root_.ysyx_26030103.common.{ysyx_26030103_KconfigDefaults => KconfigDefaults}

/** ALU 加法器的公开参数。
  *
  * `CarrySelectGroup` 只在 `ALU_ADDER_IMPL=carry_select` 时生效，必须是能整除
  * XLEN 的 2 的幂；其它后端保留该字段只是为了让切换后端时配置可复现。
  */
case class ysyx_26030103_ALUAdderParams(
    CarrySelectGroup: Int = KconfigDefaults.ALUCarrySelectGroup
)

// ELAU 加法器的公开参数。
case class ysyx_26030103_ElauParams(
    Speed: ysyx_26030103_ElauSpeed = ysyx_26030103_ElauSpeed.Fast
)

// ALU 后端共享的加法器选择；只放公开调优参数，不把内部 mux 或 vendor 私有参数暴露到 Kconfig。
case class ysyx_26030103_ALUProfile(
    Adder: ysyx_26030103_ALUAdderImpl = ysyx_26030103_ALUAdderImpl.Native,
    AdderParams: ysyx_26030103_ALUAdderParams =
      ysyx_26030103_ALUAdderParams(),
    Elau: ysyx_26030103_ElauParams = ysyx_26030103_ElauParams()
) {
  def Describe(impl: ysyx_26030103_ALUImpl): String = {
    val backend = impl match {
      case ysyx_26030103_ALUImpl.Standard => "parallel"
      case ysyx_26030103_ALUImpl.Compact  => "shared_arithmetic"
      case ysyx_26030103_ALUImpl.External => "external(reserved)"
    }
    val adder = Adder match {
      case ysyx_26030103_ALUAdderImpl.CarrySelect =>
        s"${Adder.Name}(group=${AdderParams.CarrySelectGroup})"
      case ysyx_26030103_ALUAdderImpl.Elau =>
        s"elau(speed=${Elau.Speed.Name})"
      case _ => Adder.Name
    }
    s"backend=$backend,adder=$adder"
  }
}
