package ysyx_26030103.common
import chisel3._
object ysyx_26030103_MDUOp {
  final val Width: Int = 3
  val MUL = 0.U(Width.W)
  val MULH = 1.U(Width.W)
  val MULHSU = 2.U(Width.W)
  val MULHU = 3.U(Width.W)
  val DIV = 4.U(Width.W)
  val DIVU = 5.U(Width.W)
  val REM = 6.U(Width.W)
  val REMU = 7.U(Width.W)
}
