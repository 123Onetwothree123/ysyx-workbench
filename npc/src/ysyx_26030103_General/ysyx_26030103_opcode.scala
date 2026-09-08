package ysyx_26030103.ysyx_26030103_General
import chisel3._
import chisel3.util._
object ysyx_26030103_opcode {
  val OPCODE_Register = "b0110011".U(7.W)
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
}
