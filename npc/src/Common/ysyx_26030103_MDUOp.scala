package ysyx_26030103.common
import chisel3._
object ysyx_26030103_MDUOp {
  final val Width: Int = 4
  val MUL = 0.U(Width.W)
  val MULH = 1.U(Width.W)
  val MULHSU = 2.U(Width.W)
  val MULHU = 3.U(Width.W)
  val DIV = 4.U(Width.W)
  val DIVU = 5.U(Width.W)
  val REM = 6.U(Width.W)
  val REMU = 7.U(Width.W)
  // RV64M 的 32 位“字”运算。执行时映射到上面的 64 位基础运算，
  // 再把低 32 位结果符号扩展回 XLEN。
  val MULW = 8.U(Width.W)
  val DIVW = 9.U(Width.W)
  val DIVUW = 10.U(Width.W)
  val REMW = 11.U(Width.W)
  val REMUW = 12.U(Width.W)
  def IsWordOp(Op: UInt): Bool =
    Op === MULW || Op === DIVW || Op === DIVUW || Op === REMW || Op === REMUW
}
