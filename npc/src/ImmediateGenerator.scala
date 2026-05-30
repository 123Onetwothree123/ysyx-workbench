package RV32I
import chisel3._
import chisel3.util._
import RV32I.opcode._
class ImmediateGenerator extends Module {
  val io = IO(new Bundle {
    val Instruction = Input(UInt(32.W))
    val Immediate = Output(UInt(32.W))
  })
  val opcode = io.Instruction(6, 0)
  val i_imm = io.Instruction(31, 20)
  val s_imm = Cat(io.Instruction(31, 25), io.Instruction(11, 7))
  val b_imm = Cat(
    io.Instruction(31),
    io.Instruction(7),
    io.Instruction(30, 25),
    io.Instruction(11, 8),
    0.U(1.W)
  )
  val u_imm = Cat(io.Instruction(31, 12), 0.U(12.W))
  val j_imm = Cat(
    io.Instruction(31),
    io.Instruction(19, 12),
    io.Instruction(20),
    io.Instruction(30, 21),
    0.U(1.W)
  )
  // 这是符号扩展，先标记一下
  val i_ext = Cat(Fill(20, i_imm(11)), i_imm)
  val s_ext = Cat(Fill(20, s_imm(11)), s_imm)
  val b_ext = Cat(Fill(19, b_imm(12)), b_imm)
  val j_ext = Cat(Fill(11, j_imm(20)), j_imm)
  io.Immediate := 0.U(32.W)
  switch(opcode) {
    is(OPCODE_Immediate, OPCODE_Immediate_Lxxx, OPCODE_Immediate_Bxxx) {
      io.Immediate := i_ext
    }
    is(OPCODE_Store) {
      io.Immediate := s_ext
    }
    is(OPCODE_Branch) {
      io.Immediate := b_ext
    }
    is(OPCODE_UpperImmediate_lui, OPCODE_UpperImmediate_auipc) {
      io.Immediate := u_imm
    }
    is(OPCODE_Jump) {
      io.Immediate := j_ext
    }
  }
}
