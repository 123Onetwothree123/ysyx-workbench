package ysyx_26030103.exu
import chisel3._
import chisel3.util._
// 64 位周期计数器。RV32 通过 mcycle/mcycleh 访问高低半部；
// RV64 的 mcycle 直接返回完整 64 位值。
class ysyx_26030103_mcycle(val XLEN: Int = 32) extends Module {
  require(XLEN == 32 || XLEN == 64, s"mcycle XLEN 非法: $XLEN")
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val rst = Input(Bool())
    val Enable = Input(Bool())
    val wen = Input(Bool())
    val SelectHigh = Input(Bool())
    val wdata = Input(UInt(XLEN.W))
    val rdata = Output(UInt(32.W))
    val rdataFull = Output(UInt(64.W))
  })
  val counter = withClockAndReset(io.clk, io.rst) { RegInit(0.U(64.W)) }
  when(io.Enable) {
    counter := counter + 1.U
  }
  when(io.wen) {
    if (XLEN == 64) {
      counter := io.wdata
    } else {
      when(io.SelectHigh) {
        counter := Cat(io.wdata, counter(31, 0))
      }.otherwise {
        counter := Cat(counter(63, 32), io.wdata)
      }
    }
  }
  io.rdata := Mux(io.SelectHigh, counter(63, 32), counter(31, 0))
  io.rdataFull := counter
}
