package RV32I
import chisel3._

class IFUMessage extends Bundle {
  val Instruction = UInt(32.W)
  val pc = UInt(32.W)
}
