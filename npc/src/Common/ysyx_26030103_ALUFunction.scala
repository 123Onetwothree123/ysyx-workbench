package ysyx_26030103.common
import chisel3._
object ysyx_26030103_ALUFunction {
  final val CtrlWidth: Int = 4
  val ADD: UInt = 0.U(CtrlWidth.W)
  val SUB: UInt = 1.U(CtrlWidth.W)
  val SLL: UInt = 2.U(CtrlWidth.W)
  val SLT: UInt = 3.U(CtrlWidth.W)
  val SLTU: UInt = 4.U(CtrlWidth.W)
  val XOR: UInt = 5.U(CtrlWidth.W)
  val SRL: UInt = 6.U(CtrlWidth.W)
  val SRA: UInt = 7.U(CtrlWidth.W)
  val OR: UInt = 8.U(CtrlWidth.W)
  val AND: UInt = 9.U(CtrlWidth.W)
  // RV64 字操作：结果按 32 位有符号扩展到 XLEN。
  val ADDW: UInt = 10.U(CtrlWidth.W)
  val SUBW: UInt = 11.U(CtrlWidth.W)
  val SLLW: UInt = 12.U(CtrlWidth.W)
  val SRLW: UInt = 13.U(CtrlWidth.W)
  val SRAW: UInt = 14.U(CtrlWidth.W)
  val NOP: UInt = 15.U(CtrlWidth.W)
  private def Matches(function: UInt, choices: Seq[UInt]): Bool =
    choices.map(function === _).reduceOption(_ || _).getOrElse(false.B)
  // 妈的，傻卵deepseek写的什么垃圾，操作数居然没有隔离，等于说选RV32，ALU那边64的还在，还每个周期也在算，凭空增加面积和功耗
  // 现在我才新加上选择哪些控制码需要对应子块真正参与运算
  def IsAddLike(function: UInt): Bool = Matches(function, Seq(ADD, ADDW))
  def IsSubLike(function: UInt): Bool =
    Matches(function, Seq(SUB, SLT, SLTU, SUBW))
  def IsShiftLike(function: UInt): Bool =
    Matches(function, Seq(SLL, SRL, SRA, SLLW, SRLW, SRAW))
  def IsLogicLike(function: UInt): Bool = Matches(function, Seq(XOR, OR, AND))
}
