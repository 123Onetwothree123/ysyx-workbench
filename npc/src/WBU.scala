package RV32I
import chisel3._
import chisel3.util._
class WBU extends Module {
  val io = IO(new Bundle {
    val RegWrite = Input(Bool())
    val WBSel =
      Input(UInt(2.W)) // 00是ALUResult，01是LoadDATA，10是SNPC，11是CSR_rdata
    val ALUResult = Input(UInt(32.W))
    val LoadDATA = Input(UInt(32.W))
    val SNPC = Input(UInt(32.W))
    val CSR_rdata = Input(UInt(32.W))
    val RegisterFileWriteEN = Output(Bool())
    val RegisterFileWriteDATA = Output(UInt(32.W))
  })
  io.RegisterFileWriteEN := io.RegWrite
  io.RegisterFileWriteDATA := 0.U(32.W)
  switch(io.WBSel) {
    is("b00".U) {
      io.RegisterFileWriteDATA := io.ALUResult
    }
    is("b01".U) {
      io.RegisterFileWriteDATA := io.LoadDATA
    }
    is("b10".U) {
      io.RegisterFileWriteDATA := io.SNPC
    }
    is("b11".U) {
      io.RegisterFileWriteDATA := io.CSR_rdata
    }
  }
}