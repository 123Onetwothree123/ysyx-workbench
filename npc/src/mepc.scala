package RV32I
import chisel3._
import chisel3.util._
class mepc extends Module {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val rst = Input(Bool())
    val wen = Input(Bool())
    // ecall异常触发时自动写入
    val ExceptionWE = Input(Bool())
    val ExceptionData = Input(UInt(32.W))
    val wdata = Input(UInt(32.W))
    val rdata = Output(UInt(32.W))
  })
  val RegMepc = withClockAndReset(io.clk, io.rst) { RegInit(0.U(32.W)) }
  when(io.ExceptionWE) { // 异常优先跑
    RegMepc := io.ExceptionData
  }.elsewhen(io.wen) {
    RegMepc := io.wdata
  }
  io.rdata := RegMepc
}