package ysyx_26030103.config

object ysyx_26030103_NameEnum {
  def Select[A](
      value: String,
      table: Seq[(String, A)],
      message: String => String
  ): A =
    table
      .collectFirst { case (name, item) if name == value => item }
      .getOrElse(throw new IllegalArgumentException(message(value)))

  def Names[A](table: Seq[(String, A)]): String =
    table.map(_._1).mkString("/")
}
