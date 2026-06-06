package RV32I.Message
import chisel3._
class IFUMessage extends Bundle {
  val Instruction = UInt(32.W)
  val pc = UInt(32.W)//和IFU的PC做区别，这个是取的那一刻的PC
}
