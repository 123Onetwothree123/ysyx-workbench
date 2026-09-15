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
  val NOP: UInt = 15.U(CtrlWidth.W)
  // 已实现的数据运算操作，特意不包含NOP
  val Implemented: Seq[UInt] =
    Seq(ADD, SUB, SLL, SLT, SLTU, XOR, SRL, SRA, OR, AND)
  private def Matches(function: UInt, choices: Seq[UInt]): Bool =
    choices.map(function === _).reduceOption(_ || _).getOrElse(false.B)
  // 判断操作码ALU这边有没有实现
  def IsValid(function: UInt): Bool = Matches(function, Implemented :+ NOP)
  def IsArithmetic(function: UInt): Bool = Matches(function, Seq(ADD, SUB))
  def IsShift(function: UInt): Bool = Matches(function, Seq(SLL, SRL, SRA))
  def IsCompare(function: UInt): Bool = Matches(function, Seq(SLT, SLTU))
  def IsLogic(function: UInt): Bool = Matches(function, Seq(XOR, OR, AND))
  def IsNop(function: UInt): Bool = function === NOP
}
