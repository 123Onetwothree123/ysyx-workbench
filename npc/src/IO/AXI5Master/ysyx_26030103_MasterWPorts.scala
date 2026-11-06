package ysyx_26030103.io.axi5.master
import chisel3._
import _root_.ysyx_26030103.ysyx_26030103_IOParams

private[ysyx_26030103] trait ysyx_26030103_MasterWPorts {
  this: Bundle with ysyx_26030103_IOParams =>
  val wready = Input(Bool())
  val wvalid = Output(Bool())
  val wdata = Output(UInt(SocDataWidth.W))
  val wstrb = Output(UInt(SocStrbWidth.W))
  val wlast = Output(Bool())
}
