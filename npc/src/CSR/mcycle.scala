package RV32I.CSR
import chisel3._
import chisel3.util._
class mcycle extends Module {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val rst = Input(Bool())
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
  }.otherwise {
    counter := counter + 1.U // 默认的情况下
  }
  io.rdata := Mux(io.SelectHigh, counter(63, 32), counter(31, 0))
}
