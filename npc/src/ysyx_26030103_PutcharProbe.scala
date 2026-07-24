package ysyx_26030103
import chisel3._
import chisel3.util._

class ysyx_26030103_PutcharProbe extends BlackBox {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val en = Input(Bool())
    val data = Input(UInt(8.W))
  })
}
