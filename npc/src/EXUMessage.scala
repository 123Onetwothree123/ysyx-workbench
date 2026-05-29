package RV32I
import chisel3._

class EXUMessage extends Bundle {
  val Rd = UInt(5.W)
  val RegWrite = Bool()
  val WBSel = UInt(2.W)
  val ALUResult = UInt(32.W)
  val LoadData = UInt(32.W)
  val snpc = UInt(32.W)
  val CSRReadData = UInt(32.W)
}
