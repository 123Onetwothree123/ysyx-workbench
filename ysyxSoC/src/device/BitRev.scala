package ysyx

import chisel3._
import chisel3.util._

class bitrev extends BlackBox {
  val io = IO(Flipped(new SPIIO(1)))
}

class bitrevChisel extends RawModule { // we do not need clock and reset
  val io = IO(Flipped(new SPIIO(1)))
  io.miso := true.B
  val posedge = io.sck.asClock
  val negedge = (!io.sck).asClock
  val reset = io.ss.asBool.asAsyncReset
  val counter = withClockAndReset(posedge, reset)(RegInit(0.U(8.W))) // 计数器
  val RxReg = withClockAndReset(posedge, reset)(RegInit(0.U(8.W))) // 移位寄存器
  // reset的时候输出true是因为空闲要高电平
  val MISOReg = withClockAndReset(negedge, reset)(RegInit(true.B))
  when(counter < 16.U) {
    counter := counter + 1.U
  }
  when(counter < 8.U) {
    RxReg := Cat(RxReg(6, 0), io.mosi)
  }
  when(counter >= 8.U && counter < 16.U) {
    MISOReg := RxReg(counter - 8.U)
  }.otherwise {
    MISOReg := true.B
  }
  io.miso := MISOReg
}
