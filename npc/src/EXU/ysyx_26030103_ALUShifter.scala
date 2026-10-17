package ysyx_26030103.exu
import chisel3._

// ALU 移位器：SLL/SRL/SRA 共用一个桶形移位器（RV32 移位量取 B[4:0]）。
class ysyx_26030103_ALUShifter extends Module {
  val io = IO(new Bundle {
    val A = Input(UInt(32.W))
    val shamt = Input(UInt(5.W))
    val sll = Output(UInt(32.W))
    val srl = Output(UInt(32.W))
    val sra = Output(UInt(32.W))
  })
  io.sll := (io.A << io.shamt)(31, 0)
  io.srl := io.A >> io.shamt
  io.sra := (io.A.asSInt >> io.shamt).asUInt
}
