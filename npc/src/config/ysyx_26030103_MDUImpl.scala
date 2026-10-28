package ysyx_26030103.config

sealed trait ysyx_26030103_MDUImpl {
  def Name: String
  def Implemented: Boolean
  def SupportsXLen(xlen: Int): Boolean = true
}

object ysyx_26030103_MDUImpl {
  case object Standard extends ysyx_26030103_MDUImpl {
    val Name = "standard"
    val Implemented = true
  }
  case object Rocket extends ysyx_26030103_MDUImpl {
    val Name = "rocket"
    val Implemented = true
  }
  case object NutShell extends ysyx_26030103_MDUImpl {
    val Name = "nutshell"
    val Implemented = true
  }
  case object Ibex extends ysyx_26030103_MDUImpl {
    val Name = "ibex"
    val Implemented = true
    override def SupportsXLen(xlen: Int): Boolean = xlen == 32
  }
  case object Cve2 extends ysyx_26030103_MDUImpl {
    val Name = "cve2"
    val Implemented = true
    override def SupportsXLen(xlen: Int): Boolean = xlen == 32
  }
  case object Cv32e40p extends ysyx_26030103_MDUImpl {
    val Name = "cv32e40p"
    val Implemented = true
    override def SupportsXLen(xlen: Int): Boolean = xlen == 32
  }
  case object PicoRV32 extends ysyx_26030103_MDUImpl {
    val Name = "picorv32"
    val Implemented = true
    override def SupportsXLen(xlen: Int): Boolean = xlen == 32
  }
  case object External extends ysyx_26030103_MDUImpl {
    val Name = "external"
    val Implemented = false
  }

  val All: Seq[ysyx_26030103_MDUImpl] =
    Seq(Standard, Rocket, NutShell, Ibex, Cve2, Cv32e40p, PicoRV32, External)

  def FromString(s: String): ysyx_26030103_MDUImpl =
    s.trim.toLowerCase match {
      case "standard" => Standard
      case "rocket" => Rocket
      case "nutshell" => NutShell
      case "ibex" => Ibex
      case "cve2" => Cve2
      case "cv32e40p" => Cv32e40p
      case "picorv32" => PicoRV32
      case "external" => External
      case other =>
        throw new IllegalArgumentException(
          s"未知 MDU_IMPL=$other；只允许 ${All.map(_.Name).mkString("/")}"
        )
    }
}
