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
      Left.ready := Right.ready                     // prevOut.ready := thisIn.ready
      Right.bits := RegEnable(Left.bits,            // thisIn.bits := RegEnable(prevOut.bits,
                              Left.valid && Right.ready) //   prevOut.valid && thisIn.ready)
      Right.valid := Mux(flush, false.B,                 // flush 优先清空
                         RegEnable(Left.valid, Right.ready, false.B))
    } else if (arch == "ooo") {
      Right <> Queue(Left, 16)
    } else {
      Right <> Left
    }
  }
}
