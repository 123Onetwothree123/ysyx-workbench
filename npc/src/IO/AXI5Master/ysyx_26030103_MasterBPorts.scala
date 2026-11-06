package ysyx_26030103.io.axi5.master
import chisel3._
import _root_.ysyx_26030103.ysyx_26030103_IOParams

private[ysyx_26030103] trait ysyx_26030103_MasterBPorts {
  this: Bundle with ysyx_26030103_IOParams =>
  val bready = Output(Bool())
  val bvalid = Input(Bool())
  val bresp = Input(UInt(2.W))
  val bid = Input(UInt(4.W))
}
