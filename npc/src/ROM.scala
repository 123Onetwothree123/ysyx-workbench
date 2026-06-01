package RV32I
import chisel3._
import chisel3.util._
class ROM extends Module {
  val io = IO(new Bundle {
    val Address = Input(UInt(32.W))
    val ReadDATA = Output(UInt(32.W))
  })
  // 临时
  io.ReadDATA := 0.U
  // val InstructionMemory = Module(
  //   new RegisterFile(ADDR_WIDTH = 8, DATA_WIDTH = 32)
  // )
  // InstructionMemory.io.wen := false.B
  // InstructionMemory.io.waddr := 0.U(8.W)
  // InstructionMemory.io.wdata:=0.U(32.W)
  // InstructionMemory.io.raddr1:=io.Address(9,2)
  // InstructionMemory.io.raddr2:=0.U(8.W)
  // io.ReadDATA := RegNext(InstructionMemory.io.rdata1)
}
