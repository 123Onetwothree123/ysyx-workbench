package ysyx_26030103.config

// IDU 译码真值表使用的最小化算法。
sealed trait ysyx_26030103_IDUDecodeMinimizer {
  def Name: String
}

object ysyx_26030103_IDUDecodeMinimizer {
  case object QMC extends ysyx_26030103_IDUDecodeMinimizer {
    val Name = "qmc"
  }
  case object Espresso extends ysyx_26030103_IDUDecodeMinimizer {
    val Name = "espresso"
  }
  case object Auto extends ysyx_26030103_IDUDecodeMinimizer {
    val Name = "auto"
  }
  case object None extends ysyx_26030103_IDUDecodeMinimizer {
    val Name = "none"
  }
  case object Bdd extends ysyx_26030103_IDUDecodeMinimizer {
    val Name = "bdd"
  }
  case object Sat extends ysyx_26030103_IDUDecodeMinimizer {
    val Name = "sat"
  }

  val All: Seq[ysyx_26030103_IDUDecodeMinimizer] =
    Seq(QMC, Espresso, Auto, None, Bdd, Sat)

  def FromString(value: String): ysyx_26030103_IDUDecodeMinimizer = {
    val table: Seq[(String, ysyx_26030103_IDUDecodeMinimizer)] =
      Seq(
        "qmc" -> QMC,
        "espresso" -> Espresso,
        "auto" -> Auto,
        "none" -> None,
        "bdd" -> Bdd,
        "sat" -> Sat
      )
    ysyx_26030103_NameEnum.Select(
      value.trim.toLowerCase,
      table,
      other =>
        s"未知 IDU_DECODE_MINIMIZER=$other；只允许 " +
          ysyx_26030103_NameEnum.Names(table)
    )
  }
}
