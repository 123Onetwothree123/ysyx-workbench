package ysyx_26030103.io.axi5.master
import chisel3._
import _root_.ysyx_26030103.ysyx_26030103_IOParams
import _root_.ysyx_26030103.ysyx_26030103_AXI5RPayload
private[ysyx_26030103] trait ysyx_26030103_MasterRPorts
    extends ysyx_26030103_AXI5RPayload
    with ysyx_26030103_MasterPortDirection {
  this: Bundle with ysyx_26030103_IOParams =>
}
