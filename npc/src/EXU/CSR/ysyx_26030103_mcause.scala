package ysyx_26030103.exu
import chisel3._
import chisel3.util._
class ysyx_26030103_mcause(val XLEN: Int = 32) extends Module {
  require(XLEN == 32 || XLEN == 64, s"mcause XLEN 非法: $XLEN")
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val rst = Input(Bool())
    val wen = Input(Bool())
    val wdata = Input(UInt(XLEN.W))
    val rdata = Output(UInt(XLEN.W))
  })
  val RegMcause = withClockAndReset(io.clk, io.rst) { RegInit(0.U(XLEN.W)) }
  when(io.wen) {
    RegMcause := io.wdata
  }
  io.rdata := RegMcause
}
