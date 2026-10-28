package ysyx_26030103.config

sealed trait ysyx_26030103_MDUImpl {
  def Name: String
  def Implemented: Boolean
}

object ysyx_26030103_MDUImpl {
  case object Standard extends ysyx_26030103_MDUImpl {
    val Name = "standard"
    val Implemented = true
  }
  case object External extends ysyx_26030103_MDUImpl {
    val Name = "external"
    val Implemented = false
  }

  val All: Seq[ysyx_26030103_MDUImpl] = Seq(Standard, External)

  def FromString(s: String): ysyx_26030103_MDUImpl =
    s.trim.toLowerCase match {
      case "standard" => Standard
      case "external" => External
      case other =>
        throw new IllegalArgumentException(
          s"未知 MDU_IMPL=$other；只允许 ${All.map(_.Name).mkString("/")}"
        )
    }
}
