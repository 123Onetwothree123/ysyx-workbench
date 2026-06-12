package ysyx_26030103.ysyx_26030103_CSR
import chisel3._
import chisel3.util._
class ysyx_26030103_mtvec extends Module {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val rst = Input(Bool())
    val wen = Input(Bool())
    val wdata = Input(UInt(32.W))
    val rdata = Output(UInt(32.W))
  })
  val RegMtvec = withClockAndReset(io.clk, io.rst) { RegInit(0.U(32.W)) }
  when(io.rst) {
    RegMtvec := 0.U(32.W)
  }.elsewhen(io.wen) {
    RegMtvec := io.wdata
  }
  io.rdata := RegMtvec
}