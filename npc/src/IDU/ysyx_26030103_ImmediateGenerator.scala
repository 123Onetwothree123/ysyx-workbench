package ysyx_26030103.idu
import _root_.ysyx_26030103.common.ysyx_26030103_XLENRequire
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common.ysyx_26030103_opcode._
class ysyx_26030103_ImmediateGenerator(val XLEN: Int = 32) extends Module {
  ysyx_26030103_XLENRequire(XLEN, "ImmediateGenerator")
  val io = IO(new Bundle {
    val Instruction = Input(UInt(32.W))
    val Immediate = Output(UInt(XLEN.W))
  })
  val opcode = io.Instruction(6, 0)
  val iImmediate = io.Instruction(31, 20)
  val sImmediate = Cat(io.Instruction(31, 25), io.Instruction(11, 7))
  val bImmediate = Cat(
    io.Instruction(31),
    io.Instruction(7),
    io.Instruction(30, 25),
    io.Instruction(11, 8),
    0.U(1.W)
  )
  val uImmediate = Cat(io.Instruction(31, 12), 0.U(12.W))
  val jImmediate = Cat(
    io.Instruction(31),
    io.Instruction(19, 12),
    io.Instruction(20),
    io.Instruction(30, 21),
    0.U(1.W)
  )
  // I/S/B/J 型立即数统一按 bit31 符号扩展。XLEN=32 时 Fill 宽度为 0，
  // Chisel 不允许生成 UInt<0>，因此用 helper 在 32 位下直接返回原值。
  private def signExtend(value: UInt, bits: Int): UInt =
    if (XLEN == bits) value
    else Cat(Fill(XLEN - bits, value(bits - 1)), value)
  val iExt = signExtend(iImmediate, 12)
  val sExt = signExtend(sImmediate, 12)
  val bExt = signExtend(bImmediate, 13)
  val jExt = signExtend(jImmediate, 21)
  // RV64 的 LUI/AUIPC 立即数仍是 32 位，但写入 XLEN 寄存器时按 bit31
  // 符号扩展（例如 lui 0x80000 -> 0xffffffff80000000）。
  val uExt = signExtend(uImmediate, 32)
  io.Immediate := 0.U(XLEN.W)
  switch(opcode) {
    is(OPCODE_Immediate, OPCODE_ImmediateWord, OPCODE_Immediate_Lxxx,
      OPCODE_Immediate_Bxxx, OPCODE_LoadFP) {
      io.Immediate := iExt
    }
    is(OPCODE_Store, OPCODE_StoreFP) {
      io.Immediate := sExt
    }
    is(OPCODE_Branch) {
      io.Immediate := bExt
    }
    is(OPCODE_UpperImmediate_lui, OPCODE_UpperImmediate_auipc) {
      io.Immediate := uExt
    }
    is(OPCODE_Jump) {
      io.Immediate := jExt
    }
  }
}
