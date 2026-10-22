package ysyx_26030103.exu
import chisel3._
import chisel3.util._
class ysyx_26030103_mcycle extends Module {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val rst = Input(Bool())
    val Enable = Input(Bool())
    val wen = Input(Bool())
    val SelectHigh = Input(Bool())
    val wdata = Input(UInt(32.W))
    val rdata = Output(UInt(32.W))
  })
  val counter = withClockAndReset(io.clk, io.rst) { RegInit(0.U(64.W)) }
  when(io.wen) {
    when(io.SelectHigh) {
      counter := Cat(io.wdata, counter(31, 0))
    }.otherwise {
      counter := Cat(counter(63, 32), io.wdata)
    }
  }.elsewhen(io.Enable) {
    counter := counter + 1.U
  }
  io.rdata := Mux(io.SelectHigh, counter(63, 32), counter(31, 0))
}
