package ysyx_26030103.io.axi5.master
import chisel3._
import _root_.ysyx_26030103.ysyx_26030103_IOParams

private[ysyx_26030103] trait ysyx_26030103_MasterRPorts {
  this: Bundle with ysyx_26030103_IOParams =>
  val rready = Output(Bool())
  val rvalid = Input(Bool())
  val rresp = Input(UInt(2.W))
  val rdata = Input(UInt(SocDataWidth.W))
  val rlast = Input(Bool())
  val rid = Input(UInt(4.W))
}
