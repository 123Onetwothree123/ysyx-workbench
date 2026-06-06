package RV32I.Message
import chisel3._
class IDUMessage extends Bundle {
  val pc = UInt(32.W)
  val snpc = UInt(32.W)

  val ALUCtrl = UInt(4.W)
  val ALU_A = UInt(32.W)
  val ALU_B = UInt(32.W)

  val BranchA = UInt(32.W)
  val BranchB = UInt(32.W)
  val BranchFunct3 = UInt(3.W)
  val IsBranch = Bool()
  val IsJal = Bool()
  val IsJalr = Bool()
  val Immediate = UInt(32.W)

  val Rd = UInt(5.W)
  val RegisterWrite = Bool()
  val WBSelect = UInt(2.W)

  val MemoryValid = Bool()
  val MemoryWrite = Bool()
  val WidthSelect = UInt(2.W)
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
  
  //新加的ALU的异常的指令，是ALUControlDecoder模块里面的
  val ALUCDIllegal=Bool()
}
