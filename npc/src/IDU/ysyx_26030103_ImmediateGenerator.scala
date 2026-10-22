package ysyx_26030103.idu
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common.ysyx_26030103_opcode._
class ysyx_26030103_ImmediateGenerator(val XLEN: Int = 32) extends Module {
  require(XLEN == 32 || XLEN == 64, s"ImmediateGenerator XLEN 非法: $XLEN")
  val io = IO(new Bundle {
    val Instruction = Input(UInt(32.W))
    val Immediate = Output(UInt(XLEN.W))
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
  // I/S/B/J 型立即数统一按 bit31 符号扩展。XLEN=32 时 Fill 宽度为 0，
  // Chisel 不允许生成 UInt<0>，因此用 helper 在 32 位下直接返回原值。
  private def SignExtend(Value: UInt, Bits: Int): UInt =
    if (XLEN == Bits) Value
    else Cat(Fill(XLEN - Bits, Value(Bits - 1)), Value)
  val i_ext = SignExtend(i_imm, 12)
  val s_ext = SignExtend(s_imm, 12)
  val b_ext = SignExtend(b_imm, 13)
  val j_ext = SignExtend(j_imm, 21)
  // RV64 的 LUI/AUIPC 立即数仍是 32 位，但写入 XLEN 寄存器时按 bit31
  // 符号扩展（例如 lui 0x80000 -> 0xffffffff80000000）。
  val u_ext = SignExtend(u_imm, 32)
  io.Immediate := 0.U(XLEN.W)
  switch(opcode) {
    is(OPCODE_Immediate, OPCODE_ImmediateWord, OPCODE_Immediate_Lxxx, OPCODE_Immediate_Bxxx) {
      io.Immediate := i_ext
    }
    is(OPCODE_Store) {
      io.Immediate := s_ext
    }
    is(OPCODE_Branch) {
      io.Immediate := b_ext
    }
    is(OPCODE_UpperImmediate_lui, OPCODE_UpperImmediate_auipc) {
      io.Immediate := u_ext
    }
    is(OPCODE_Jump) {
      io.Immediate := j_ext
    }
  }
}
