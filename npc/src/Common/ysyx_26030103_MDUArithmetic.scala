package ysyx_26030103.common
import chisel3._
import chisel3.util._
//MUL/DIV适配层共用的幅值与符号恢复。
object ysyx_26030103_MDUArithmetic {
  def Magnitude(Value: UInt, Signed: Bool): UInt = {
    val Width = Value.getWidth
    val Negated = (~Value + 1.U(Width.W))(Width - 1, 0)
    Mux(Signed && Value(Width - 1), Negated, Value)
  }
  // 保持输入的位宽：MULH 仍需从完整乘积中选择高半部。
  def RestoreSign(Value: UInt, Negative: Bool): UInt = {
    val Width = Value.getWidth
    val Negated = (~Value + 1.U(Width.W))(Width - 1, 0)
    Mux(Negative, Negated, Value)
  }
}
