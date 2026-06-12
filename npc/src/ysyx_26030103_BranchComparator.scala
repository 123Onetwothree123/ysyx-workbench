package ysyx_26030103
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
      is("b000".U) { // BEQ
        io.Taken := io.A === io.B
      }
      is("b001".U) { // BNE
        io.Taken := io.A =/= io.B
      }
      is("b100".U) { // BLT
        io.Taken := io.A.asSInt < io.B.asSInt
      }
      is("b101".U) { // BGE
        io.Taken := io.A.asSInt >= io.B.asSInt
      }
      is("b110".U) { // BLTU
        io.Taken := io.A < io.B
      }
      is("b111".U) { // BGEU
        io.Taken := io.A >= io.B
      }
    }
  }
}
