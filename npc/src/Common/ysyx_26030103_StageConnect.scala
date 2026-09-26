package ysyx_26030103.common
import chisel3._
import chisel3.util._

object ysyx_26030103_StageConnect {
  def apply[T <: Data](
      Left: DecoupledIO[T],
      Right: DecoupledIO[T]
  ): Unit = apply(Left, Right, false.B, false.B)

  def apply[T <: Data](
      Left: DecoupledIO[T],
      Right: DecoupledIO[T],
      flush: Bool
  ): Unit = apply(Left, Right, flush, flush)

  def apply[T <: Data](
      Left: DecoupledIO[T],
      Right: DecoupledIO[T],
      flush: Bool,
      flushCurrent: Bool
  ): Unit = {
    val BitsReg = Reg(chiselTypeOf(Left.bits))
    val ValidReg = RegInit(false.B)
    val CanAdvance = !ValidReg || Right.ready
    Left.ready := CanAdvance || flush
    // 大多数边界在冲刷期间都会屏蔽当前输出。如果某个边界的冲刷由当前输出
    // 自身产生（EXU 重定向），则必须让当前指令保持可见，直到完成握手。
    // flushCurrent 表示显式杀死当前项，并且必须蕴含 flush；让它与自身产生的
    // 重定向相互独立，也可以避免 valid 组合环路。
    Right.valid := ValidReg && !flushCurrent
    Right.bits := BitsReg
    assert(!flushCurrent || flush, "StageConnect flushCurrent must imply flush")
    when(flush) {
      // 冲刷时绝不装入更年轻的 Left 项；被保留的当前项只有在下游真正接收后
      // 才会被清除。
      when(flushCurrent || Right.fire) {
        ValidReg := false.B
      }
    }.elsewhen(CanAdvance) {
      ValidReg := Left.valid
      when(Left.valid) { BitsReg := Left.bits }
    }
  }
}
