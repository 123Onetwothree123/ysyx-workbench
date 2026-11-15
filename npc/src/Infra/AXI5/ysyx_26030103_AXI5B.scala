package ysyx_26030103.infra
import chisel3._
class ysyx_26030103_AXI5B(IDWidth: Int = 4) extends Bundle {
  val BID = Input(UInt(IDWidth.W))
  val BRESP = Input(UInt(2.W)) // 标准写的是0，2，3
  val BVALID = Input(Bool()) // 有效
  val BREADY = Output(Bool()) // 准备
}
