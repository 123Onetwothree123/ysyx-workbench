package ysyx_26030103.common
import chisel3._
import chisel3.util._

object ysyx_26030103_StageConnect {
  def apply[T <: Data](
      Left: DecoupledIO[T],
      Right: DecoupledIO[T],
      flush: Bool = false.B
  ): Unit = {
    val BitsReg = Reg(chiselTypeOf(Left.bits))
    val ValidReg = RegInit(false.B)
    val ReadyForInput = !ValidReg || Right.ready || flush
    Left.ready := ReadyForInput
    Right.valid := Mux(flush, false.B, ValidReg)
    Right.bits := BitsReg
    when(ReadyForInput) {
      ValidReg := Mux(flush, false.B, Left.valid)
      when(!flush && Left.valid) { BitsReg := Left.bits }
    }
  }
}
