package ysyx_26030103.exu
import chisel3._
import chisel3.util._
class ysyx_26030103_mepc(val XLEN: Int = 32) extends Module {
  require(XLEN == 32 || XLEN == 64, s"mepc XLEN 非法: $XLEN")
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val rst = Input(Bool())
    val wen = Input(Bool())
    // ecall异常触发时自动写入
    val ExceptionWE = Input(Bool())
    val ExceptionData = Input(UInt(XLEN.W))
    val wdata = Input(UInt(XLEN.W))
    val rdata = Output(UInt(XLEN.W))
  })
  val RegMepc = withClockAndReset(io.clk, io.rst) { RegInit(0.U(XLEN.W)) }
  // IALIGN=32：mepc[1:0]在写入和读出两侧都固定为0。
  private def Align(Value: UInt): UInt = Cat(Value(XLEN - 1, 2), 0.U(2.W))
  when(io.ExceptionWE) { // 异常优先跑
    RegMepc := Align(io.ExceptionData)
  }.elsewhen(io.wen) {
    RegMepc := Align(io.wdata)
  }
  io.rdata := Align(RegMepc)
}
