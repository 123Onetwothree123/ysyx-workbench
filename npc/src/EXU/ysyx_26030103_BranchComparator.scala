package ysyx_26030103.exu
import chisel3._
import chisel3.util._
class ysyx_26030103_BranchComparator extends Module {
  val io = IO(new Bundle {
    val A = Input(UInt(32.W))
    val B = Input(UInt(32.W))
    val Funct3 = Input(UInt(3.W))
    val IsBranch = Input(Bool())
    val Taken = Output(Bool()) // 看看是否跳转
  })
  io.Taken := false.B
  when(io.IsBranch) {
    switch(io.Funct3) {
      is("b000".U) { // 相等时分支（BEQ）
        io.Taken := io.A === io.B
      }
      is("b001".U) { // 不相等时分支（BNE）
        io.Taken := io.A =/= io.B
      }
      is("b100".U) { // 有符号小于时分支（BLT）
        io.Taken := io.A.asSInt < io.B.asSInt
      }
      is("b101".U) { // 有符号大于等于时分支（BGE）
        io.Taken := io.A.asSInt >= io.B.asSInt
      }
      is("b110".U) { // 无符号小于时分支（BLTU）
        io.Taken := io.A < io.B
      }
      is("b111".U) { // 无符号大于等于时分支（BGEU）
        io.Taken := io.A >= io.B
      }
    }
  }
}
