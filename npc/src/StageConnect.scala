package RV32I
import chisel3._
import chisel3.util._

object StageConnect {
  private val Arch = "multi"
  def apply[T <: Data](Left: DecoupledIO[T], Right: DecoupledIO[T]): Unit = {
    if (Arch == "single") {
      Right.bits := Left.bits
      Right.valid := true.B
      Left.ready := true.B
    } else if (Arch == "multi") {
      Right <> Left
    } else if (Arch == "pipeline") {
      val BitsReg = Reg(chiselTypeOf(Left.bits)) // 实际传输的数据，
      val ValidReg = RegInit(false.B) // 看寄存器是不是空的，0是空的
      val ReadyForInput =
        !ValidReg || Right.ready // 先标记一下，！ValidReg是寄存器空的，R.r是现在这级准备可以接收了
      Left.ready := ReadyForInput
      Right.valid := ValidReg // 给下一级的，看看数据是否有效
      Right.bits := BitsReg // 输出寄存器里面的数据
      when(ReadyForInput) {
        ValidReg := Left.valid
        when(Left.valid) { // 如果上级有有效数据，就把数据放进寄存器里面
          BitsReg := Left.bits
        }
      }
    } else if (Arch == "ooo") {
      Right <> Queue(Left, 16)
    } else {
      Right <> Left
    }
  }
}
