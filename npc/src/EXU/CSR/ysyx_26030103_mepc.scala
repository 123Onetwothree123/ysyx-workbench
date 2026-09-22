package ysyx_26030103.exu
import chisel3._
import chisel3.util._
class ysyx_26030103_mepc extends Module {
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
    RegMepc := Cat(io.ExceptionData(31, 2), 0.U(2.W))
  }.elsewhen(io.wen) {
    RegMepc := Cat(io.wdata(31, 2), 0.U(2.W))
  }
  // IALIGN=32：mepc[1:0]在写入和读出两侧都固定为0。
  io.rdata := Cat(RegMepc(31, 2), 0.U(2.W))
}
