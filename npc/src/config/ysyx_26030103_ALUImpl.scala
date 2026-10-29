package ysyx_26030103.config

/** 由 Kconfig 选择的 ALU 后端。
  *
  * 所有后端共享 `ysyx_26030103_ALUInterface`。`Standard` 把加法、减法、
  * 移位和位逻辑拆成并行子块；`Compact` 复用一个算术单元完成加减，面积
  * 更小但结果选择路径更深。`External` 只是预留，必须在 elaboration 前
  * 明确拒绝，不能静默生成没有 ALU 数据通路的核。
  */
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

  def FromString(s: String): ysyx_26030103_ALUImpl = s.trim.toLowerCase match {
    case "standard" => Standard
    case "compact"  => Compact
    case "external" => External
    case other =>
      throw new IllegalArgumentException(
        s"未知 ALU_IMPL=$other；只允许 ${All.map(_.Name).mkString("/")}"
      )
  }
}

/** ALU 加法器的结构实现。
  *
  * 加法器是 ALU 最关键的组合路径之一：`Native` 交给 Chisel/Synthesis
  * 映射到工艺库加法器；`Ripple` 展成逐位全加器；`CarrySelect` 用
  * 分组双路加法器选择进位；`CarryLookahead` 使用 4 位块内超前进位；
  * `Elau` 复用 PULP ELAU 的 AddC。所有实现共享相同的端口语义，
  * 切换不会改变 RISC-V 指令结果。
  */
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

  def FromString(s: String): ysyx_26030103_ALUAdderImpl =
    s.trim.toLowerCase match {
      case "native"          => Native
      case "ripple"          => Ripple
      case "carry_select"    => CarrySelect
      case "carry_lookahead" => CarryLookahead
      case "elau"            => Elau
      case other =>
        throw new IllegalArgumentException(
          s"未知 ALU_ADDER_IMPL=$other；只允许 " +
            All.map(_.Name).mkString("/")
        )
    }
}

/** ELAU `AddC` 的 speed 选择。
  *
  * 上游 `lau_pkg::speed_e` 的编码为 SLOW=0、MEDIUM=1、FAST=2；
  * 这里把整数编码固定下来，避免把 SystemVerilog enum 暴露到 Chisel
  * 或 typed JSON 中。
  */
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

  def FromString(s: String): ysyx_26030103_ElauSpeed =
    s.trim.toLowerCase match {
      case "slow"   => Slow
      case "medium" => Medium
      case "fast"   => Fast
      case other =>
        throw new IllegalArgumentException(
          s"未知 ALU_ELAU_SPEED=$other；只允许 " +
            All.map(_.Name).mkString("/")
        )
    }
}
