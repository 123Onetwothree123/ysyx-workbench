package ysyx_26030103.io.axi5.master
import chisel3._
import _root_.ysyx_26030103.ysyx_26030103_IOParams

private[ysyx_26030103] trait ysyx_26030103_MasterARPorts {
  this: Bundle with ysyx_26030103_IOParams =>
  val arready = Input(Bool())
  val arvalid = Output(Bool())
  val araddr = Output(UInt(SocXLEN.W))
  val arid = Output(UInt(4.W))
  val arlen = Output(UInt(8.W))
  val arsize = Output(UInt(3.W))
  val arburst = Output(UInt(2.W))
  val arlock = Output(Bool())
  val arcache = Output(UInt(4.W))
  val arprot = Output(UInt(3.W))
  val arqos = Output(UInt(4.W))
}
