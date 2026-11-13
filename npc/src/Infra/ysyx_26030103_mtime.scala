package ysyx_26030103.infra
import chisel3._

class ysyx_26030103_mtime extends Module {
  val io = IO(new Bundle {
    val value = Output(UInt(64.W))
  })
  val counter = RegInit(0.U(64.W))
  counter := counter + 1.U
  io.value := counter
}
