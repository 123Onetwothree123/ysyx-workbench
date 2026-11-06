package ysyx_26030103.io.axi5.slave
import chisel3._
import _root_.ysyx_26030103.ysyx_26030103_IOParams
private[ysyx_26030103] trait ysyx_26030103_SlaveBPorts {
  this: Bundle with ysyx_26030103_IOParams =>
  val bready = Input(Bool())
  val bvalid = Output(Bool())
  val bresp = Output(UInt(2.W))
  val bid = Output(UInt(4.W))
}
