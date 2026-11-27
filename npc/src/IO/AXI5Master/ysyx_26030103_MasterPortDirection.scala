package ysyx_26030103.io.axi5.master
import chisel3._
import _root_.ysyx_26030103.ysyx_26030103_IOParams
import _root_.ysyx_26030103.ysyx_26030103_AXI5Payload
private[ysyx_26030103] trait ysyx_26030103_MasterPortDirection
    extends ysyx_26030103_AXI5Payload {
  this: Bundle with ysyx_26030103_IOParams =>
  override protected final def masterInput[T <: Data](d: T): T = Input(d)
  override protected final def masterOutput[T <: Data](d: T): T = Output(d)
}
