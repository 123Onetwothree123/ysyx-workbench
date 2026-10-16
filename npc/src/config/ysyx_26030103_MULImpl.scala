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
  def FromString(s: String): ysyx_26030103_MULImpl = s match {
    case "shift_add" => ShiftAdd
    case "wallace" => Wallace
    case "dadda" => Dadda
    case other => throw new IllegalArgumentException(s"这个乘法器没有实现: $other")
  }
}
