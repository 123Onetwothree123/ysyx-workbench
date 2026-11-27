package ysyx_26030103.config

//部分积编码与乘法器结构正交。
sealed trait ysyx_26030103_MULEncoding {
  def Name: String
}
object ysyx_26030103_MULEncoding {
  case object Plain extends ysyx_26030103_MULEncoding {
    val Name = "plain"
  }
  case object Booth extends ysyx_26030103_MULEncoding {
    val Name = "booth"
  }
  def FromString(value: String): ysyx_26030103_MULEncoding = {
    val table: Seq[(String, ysyx_26030103_MULEncoding)] =
      Seq("plain" -> Plain, "booth" -> Booth)
    ysyx_26030103_NameEnum.Select(
      value,
      table,
      other => s"这个乘法编码没有实现: $other"
    )
  }
}
