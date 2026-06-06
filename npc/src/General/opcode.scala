package RV32I.General
import chisel3._
import chisel3.util._
object opcode {
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
  object BitPats {
    val OPCODE_Register = BitPat("b0110011")
    val OPCODE_Immediate = BitPat("b0010011")
    val OPCODE_Immediate_Lxxx = BitPat("b0000011")
    val OPCODE_Immediate_Bxxx = BitPat("b1100111")
    val OPCODE_Store = BitPat("b0100011")
    val OPCODE_Branch = BitPat("b1100011")
    val OPCODE_UpperImmediate_lui = BitPat("b0110111")
    val OPCODE_UpperImmediate_auipc = BitPat("b0010111")
    val OPCODE_Jump = BitPat("b1101111")
    val OPCODE_System = BitPat("b1110011")
  }
}