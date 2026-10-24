package ysyx_26030103.common
import chisel3._
import chisel3.util._
object ysyx_26030103_opcode {
  val OPCODE_Register = "b0110011".U(7.W)
  // RV64 的 OP-IMM-32 / OP-32（ADDIW/ADDW/...）
  val OPCODE_ImmediateWord = "b0011011".U(7.W)
  val OPCODE_RegisterWord = "b0111011".U(7.W)
  val OPCODE_Immediate = "b0010011".U(7.W)
  val OPCODE_Immediate_Lxxx = "b0000011".U(7.W)
  val OPCODE_Immediate_Bxxx = "b1100111".U(7.W)
  val OPCODE_Store = "b0100011".U(7.W)
  val OPCODE_Branch = "b1100011".U(7.W)
  val OPCODE_UpperImmediate_lui = "b0110111".U(7.W)
  val OPCODE_UpperImmediate_auipc = "b0010111".U(7.W)
  val OPCODE_Jump = "b1101111".U(7.W)
  val OPCODE_System = "b1110011".U(7.W)
  val OPCODE_MiscMem = "b0001111".U(7.W)
  // A 扩展：LR/SC/AMO 共用 0101111 操作码。
  val OPCODE_Amo = "b0101111".U(7.W)
  // F/D 扩展：浮点 load/store 与 OP-FP/FMA 操作码。
  val OPCODE_LoadFP = "b0000111".U(7.W)
  val OPCODE_StoreFP = "b0100111".U(7.W)
  val OPCODE_OpFP = "b1010011".U(7.W)
  val OPCODE_Fmadd = "b1000011".U(7.W)
  val OPCODE_Fmsub = "b1000111".U(7.W)
  // RISC-V 编码：FNMSUB=1001011，FNMADD=1001111。两者容易与
  // “先取反再加减”的助记顺序混淆，必须以 ISA 编码为准。
  val OPCODE_Fnmsub = "b1001011".U(7.W)
  val OPCODE_Fnmadd = "b1001111".U(7.W)
}
