package RV32I
import chisel3._
import chisel3.util._

class IDU extends Module {
  val io = IO(new Bundle {
    val Instruction = Input(UInt(32.W))
    val RegWrite = Output(Bool())
    val MemoryValid = Output(Bool())
    val MemoryWrite = Output(Bool())
    val WidthSel = Output(UInt(2.W)) // 00: 字节, 01: 半字, 10: 字
    val LoadSigned = Output(Bool())
    val ALUCtrl = Output(UInt(4.W))
    val Illegal = Output(Bool())
    val Immediate = Output(UInt(32.W))
    val WBSel = Output(UInt(2.W)) // 00: ALUResult, 01: LoadDATA, 10: SNPC
    val rs1 = Output(UInt(5.W))
    val rs2 = Output(UInt(5.W))
    val rd = Output(UInt(5.W))
    val IsEbreak_gtest = Output(Bool())
    val IsCsrrw = Output(Bool())
    val IsCsrrs = Output(Bool())
    val IsEcall = Output(Bool())
    val IsMret = Output(Bool())
    val CSRAddress = Output(UInt(12.W))
  })
  
}