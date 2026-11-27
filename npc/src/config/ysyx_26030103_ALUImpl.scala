package ysyx_26030103.config

// 由 Kconfig 选择的 ALU 后端；External 只是预留，必须在 elaboration 前拒绝。
sealed trait ysyx_26030103_ALUImpl {
  def Name: String
  def Implemented: Boolean
  def SupportsXLen(xlen: Int): Boolean = true
}

object ysyx_26030103_ALUImpl {
  case object Standard extends ysyx_26030103_ALUImpl {
    val Name = "standard"
    val Implemented = true
  }
  case object Compact extends ysyx_26030103_ALUImpl {
    val Name = "compact"
    val Implemented = true
  }
  case object External extends ysyx_26030103_ALUImpl {
    val Name = "external"
    val Implemented = false
  }

  val All: Seq[ysyx_26030103_ALUImpl] = Seq(Standard, Compact, External)

  def FromString(value: String): ysyx_26030103_ALUImpl = {
    val table: Seq[(String, ysyx_26030103_ALUImpl)] =
      Seq("standard" -> Standard, "compact" -> Compact, "external" -> External)
    ysyx_26030103_NameEnum.Select(
      value.trim.toLowerCase,
      table,
      other => s"未知 ALU_IMPL=$other；只允许 ${ysyx_26030103_NameEnum.Names(table)}"
    )
  }
}

// 加法器实现可选 native/ripple/carry_select/carry_lookahead/elau(PULP AddC)，结果语义一致。
sealed trait ysyx_26030103_ALUAdderImpl {
  def Name: String
}

object ysyx_26030103_ALUAdderImpl {
  case object Native extends ysyx_26030103_ALUAdderImpl {
    val Name = "native"
  }
  case object Ripple extends ysyx_26030103_ALUAdderImpl {
    val Name = "ripple"
  }
  case object CarrySelect extends ysyx_26030103_ALUAdderImpl {
    val Name = "carry_select"
  }
  case object CarryLookahead extends ysyx_26030103_ALUAdderImpl {
    val Name = "carry_lookahead"
  }
  case object Elau extends ysyx_26030103_ALUAdderImpl {
    val Name = "elau"
  }

  val All: Seq[ysyx_26030103_ALUAdderImpl] =
    Seq(Native, Ripple, CarrySelect, CarryLookahead, Elau)

  def FromString(value: String): ysyx_26030103_ALUAdderImpl = {
    val table: Seq[(String, ysyx_26030103_ALUAdderImpl)] =
      Seq(
        "native" -> Native,
        "ripple" -> Ripple,
        "carry_select" -> CarrySelect,
        "carry_lookahead" -> CarryLookahead,
        "elau" -> Elau
      )
    ysyx_26030103_NameEnum.Select(
      value.trim.toLowerCase,
      table,
      other =>
        s"未知 ALU_ADDER_IMPL=$other；只允许 " +
          ysyx_26030103_NameEnum.Names(table)
    )
  }
}

// ELAU AddC 的 speed：上游 lau_pkg::speed_e 为 SLOW=0/MEDIUM=1/FAST=2；
// 这里固定整数编码，避免把 SV enum 暴露到 Chisel 或 typed JSON。
sealed trait ysyx_26030103_ElauSpeed {
  def Name: String
  def Code: Int
}

object ysyx_26030103_ElauSpeed {
  case object Slow extends ysyx_26030103_ElauSpeed {
    val Name = "slow"
    val Code = 0
  }
  case object Medium extends ysyx_26030103_ElauSpeed {
    val Name = "medium"
    val Code = 1
  }
  case object Fast extends ysyx_26030103_ElauSpeed {
    val Name = "fast"
    val Code = 2
  }

  val All: Seq[ysyx_26030103_ElauSpeed] = Seq(Slow, Medium, Fast)

  def FromString(value: String): ysyx_26030103_ElauSpeed = {
    val table: Seq[(String, ysyx_26030103_ElauSpeed)] =
      Seq("slow" -> Slow, "medium" -> Medium, "fast" -> Fast)
    ysyx_26030103_NameEnum.Select(
      value.trim.toLowerCase,
      table,
      other =>
        s"未知 ALU_ELAU_SPEED=$other；只允许 " +
          ysyx_26030103_NameEnum.Names(table)
    )
  }
}
