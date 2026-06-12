package ysyx_26030103.ysyx_26030103_AXI5
import chisel3._
import chisel3.util._
class ysyx_26030103_AXI5B(IdWidth: Int = 4) extends Bundle {
  val BID = Input(UInt(IdWidth.W))
  val BRESP = Input(UInt(2.W)) // 标准写的是0，2，3
  val BVALID = Input(Bool()) // 有效
  val BREADY = Output(Bool()) // 准备
}
