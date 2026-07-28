package ysyx_26030103
import chisel3._
import chisel3.util._

object ysyx_26030103_StageConnect {
  private val arch = "pipeline"
  def apply[T <: Data](Left: DecoupledIO[T], Right: DecoupledIO[T], flush: Bool = false.B): Unit = {
    if (arch == "single") {
      Right.bits := Left.bits
      Right.valid := true.B
      Left.ready := true.B
    } else if (arch == "multi") {
      Right <> Left
    } else if (arch == "pipeline") {
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
    } else if (arch == "ooo") {
      Right <> Queue(Left, 16)
    } else {
      Right <> Left
    }
  }
}
