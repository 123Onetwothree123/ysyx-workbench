package RV32I.AXI5Lite
import chisel3._
import chisel3.util._
import chisel3.Bundle
import chisel3.Output
class mtime extends Module {
  val io = IO(new Bundle {
    val SelectHigh = Input(Bool())
    val rdata = Output(UInt(32.W))
  })
  val counter = RegInit(0.U(64.W))
  counter := counter + 1.U
  io.rdata := Mux(io.SelectHigh, counter(63, 32), counter(31, 0))
}
