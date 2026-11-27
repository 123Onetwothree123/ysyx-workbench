package ysyx_26030103.config

//由Kconfig选择的除法器结构。
sealed trait ysyx_26030103_DIVImpl {
  def Name: String
}
object ysyx_26030103_DIVImpl {
  case object Restoring extends ysyx_26030103_DIVImpl {
    val Name = "restoring"
  }
  case object NonRestoring extends ysyx_26030103_DIVImpl {
    val Name = "nonrestoring"
  }
  case object SRT extends ysyx_26030103_DIVImpl {
    val Name = "srt"
  }
  def FromString(value: String): ysyx_26030103_DIVImpl = {
    val table: Seq[(String, ysyx_26030103_DIVImpl)] =
      Seq("restoring" -> Restoring, "nonrestoring" -> NonRestoring, "srt" -> SRT)
    ysyx_26030103_NameEnum.Select(
      value,
      table,
      other => s"这个除法器没有实现: $other"
    )
  }
}
