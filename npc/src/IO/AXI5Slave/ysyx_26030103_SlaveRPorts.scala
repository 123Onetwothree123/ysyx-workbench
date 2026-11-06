package ysyx_26030103.io.axi5.slave
import chisel3._
import _root_.ysyx_26030103.ysyx_26030103_IOParams
private[ysyx_26030103] trait ysyx_26030103_SlaveRPorts {
  this: Bundle with ysyx_26030103_IOParams =>
  val rready = Input(Bool())
  val rvalid = Output(Bool())
  val rresp = Output(UInt(2.W))
  val rdata = Output(UInt(SocDataWidth.W))
  val rlast = Output(Bool())
  val rid = Output(UInt(4.W))
}
