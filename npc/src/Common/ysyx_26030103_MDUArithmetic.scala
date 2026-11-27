package ysyx_26030103.common
import chisel3._
import chisel3.util._
//MUL/DIV适配层共用的幅值与符号恢复。
object ysyx_26030103_MDUArithmetic {
  private def TwosComplement(Value: UInt): UInt = {
    val Width = Value.getWidth
    (~Value + 1.U(Width.W))(Width - 1, 0)
  }
  def Magnitude(Value: UInt, Signed: Bool): UInt = {
    val Width = Value.getWidth
    Mux(Signed && Value(Width - 1), TwosComplement(Value), Value)
  }
  // 保持输入位宽，MULH仍需从完整乘积取高半部
  def RestoreSign(Value: UInt, Negative: Bool): UInt =
    Mux(Negative, TwosComplement(Value), Value)
}
