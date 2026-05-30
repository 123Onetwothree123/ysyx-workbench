package RV32I
import chisel3._

class IDUMessage extends Bundle {
  val pc = UInt(32.W)
  val snpc = UInt(32.W)

  val ALUCtrl = UInt(4.W)
  val ALU_A = UInt(32.W)
  val AluB = UInt(32.W)

  val BranchA = UInt(32.W)
  val BranchB = UInt(32.W)
  val BranchFunct3 = UInt(3.W)
  val IsBranch = Bool()
  val IsJal = Bool()
  val IsJalr = Bool()
  val Immediate = UInt(32.W)

  val Rd = UInt(5.W)
  val RegWrite = Bool()
  val WBSel = UInt(2.W)

  val MemValid = Bool()
  val MemWrite = Bool()
  val WidthSel = UInt(2.W)
  val LoadSigned = Bool()
  val StoreData = UInt(32.W)

  val IsCsrrw = Bool()
  val IsCsrrs = Bool()
  val IsEcall = Bool()
  val IsEbreak = Bool()
  val IsMret = Bool()
  val CSRAddress = UInt(12.W)
  val Rs1 = UInt(5.W)
  val Rs1Data = UInt(32.W)
}
