package ysyx_26030103.infra
import chisel3._
import chisel3.util._

class ysyx_26030103_mtime extends Module {
  val io = IO(new Bundle {
    val SelectHigh = Input(Bool())
    val rdata = Output(UInt(32.W))
    val value = Output(UInt(64.W))
  })
  val counter = RegInit(0.U(64.W))
  counter := counter + 1.U
  io.rdata := Mux(io.SelectHigh, counter(63, 32), counter(31, 0))
  io.value := counter
}
