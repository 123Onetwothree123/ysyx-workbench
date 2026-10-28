package ysyx_26030103.config

/** ALU 加法器的公开参数。
  *
  * `CarrySelectGroup` 只在 `ALU_ADDER_IMPL=carry_select` 时生效；必须是
  * 能整除 XLEN 的 2 的幂。其它加法器后端不使用该字段，但保留它可以让
  * 同一份配置在切换后端时保持可复现。
  */
case class ysyx_26030103_ALUAdderParams(
    CarrySelectGroup: Int = 8
)

/** ALU 的通用公开参数 profile。
  *
  * `ALUImpl` 单独放在 NPCConfig 顶层，便于上层快速判断后端；本 profile
  * 只承载所有 ALU 后端共享的加法器实现选择。后端不得把内部 mux 结构、
  * 中间信号或 vendor 私有参数暴露到 Kconfig。
  */
case class ysyx_26030103_ALUProfile(
    Adder: ysyx_26030103_ALUAdderImpl = ysyx_26030103_ALUAdderImpl.Native,
    AdderParams: ysyx_26030103_ALUAdderParams =
      ysyx_26030103_ALUAdderParams()
) {
  def Describe(Impl: ysyx_26030103_ALUImpl): String = {
    val backend = Impl match {
      case ysyx_26030103_ALUImpl.Standard => "parallel"
      case ysyx_26030103_ALUImpl.Compact  => "shared_arithmetic"
      case ysyx_26030103_ALUImpl.External => "external(reserved)"
    }
    val adder = Adder match {
      case ysyx_26030103_ALUAdderImpl.CarrySelect =>
        s"${Adder.Name}(group=${AdderParams.CarrySelectGroup})"
      case _ => Adder.Name
    }
    s"backend=$backend,adder=$adder"
  }
}
