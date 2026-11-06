package ysyx_26030103.io.axi5.slave
import chisel3._
import _root_.ysyx_26030103.ysyx_26030103_IOParams
private[ysyx_26030103] trait ysyx_26030103_SlaveARPorts {
  this: Bundle with ysyx_26030103_IOParams =>
  val arready = Output(Bool())
  val arvalid = Input(Bool())
  val araddr = Input(UInt(SocXLEN.W))
  val arid = Input(UInt(4.W))
  val arlen = Input(UInt(8.W))
  val arsize = Input(UInt(3.W))
  val arburst = Input(UInt(2.W))
  val arlock = Input(Bool())
  val arcache = Input(UInt(4.W))
  val arprot = Input(UInt(3.W))
  val arqos = Input(UInt(4.W))
}
