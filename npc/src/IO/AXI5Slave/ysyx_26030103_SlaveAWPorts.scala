package ysyx_26030103.io.axi5.slave
import chisel3._
import _root_.ysyx_26030103.ysyx_26030103_IOParams
private[ysyx_26030103] trait ysyx_26030103_SlaveAWPorts {
  this: Bundle with ysyx_26030103_IOParams =>
  val awready = Output(Bool())
  val awvalid = Input(Bool())
  val awaddr = Input(UInt(SocXLEN.W))
  val awid = Input(UInt(4.W))
  val awlen = Input(UInt(8.W))
  val awsize = Input(UInt(3.W))
  val awburst = Input(UInt(2.W))
  val awlock = Input(Bool())
  val awcache = Input(UInt(4.W))
  val awprot = Input(UInt(3.W))
  val awqos = Input(UInt(4.W))
}
