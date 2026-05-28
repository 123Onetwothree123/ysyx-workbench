package RV32I
import chisel3._
import chisel3.util._
class mcause extends Module {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val rst = Input(Bool())
    val wen = Input(Bool())
    val wdata = Input(UInt(32.W))
    val rdata = Output(UInt(32.W))
  })
  val RegMcause = withClockAndReset(io.clk, io.rst) { RegInit(0.U(32.W)) }
  when(io.wen) {
    RegMcause := io.wdata
  }
  io.rdata := RegMcause
}
