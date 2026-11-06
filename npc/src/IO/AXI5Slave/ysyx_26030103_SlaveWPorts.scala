package ysyx_26030103.io.axi5.slave
import chisel3._
import _root_.ysyx_26030103.ysyx_26030103_IOParams
private[ysyx_26030103] trait ysyx_26030103_SlaveWPorts {
  this: Bundle with ysyx_26030103_IOParams =>
  val wready = Output(Bool())
  val wvalid = Input(Bool())
  val wdata = Input(UInt(SocDataWidth.W))
  val wstrb = Input(UInt(SocStrbWidth.W))
  val wlast = Input(Bool())
}
