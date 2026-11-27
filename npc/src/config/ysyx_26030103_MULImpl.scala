package ysyx_26030103.config

//由Kconfig选择的乘法器结构。
sealed trait ysyx_26030103_MULImpl {
  def Name: String
}
object ysyx_26030103_MULImpl {
  case object ShiftAdd extends ysyx_26030103_MULImpl {
    val Name = "shift_add"
  }
  case object Wallace extends ysyx_26030103_MULImpl {
    val Name = "wallace"
  }
  case object Dadda extends ysyx_26030103_MULImpl {
    val Name = "dadda"
  }
  def FromString(value: String): ysyx_26030103_MULImpl = {
    val table: Seq[(String, ysyx_26030103_MULImpl)] =
      Seq("shift_add" -> ShiftAdd, "wallace" -> Wallace, "dadda" -> Dadda)
    ysyx_26030103_NameEnum.Select(
      value,
      table,
      other => s"这个乘法器没有实现: $other"
    )
  }
}
