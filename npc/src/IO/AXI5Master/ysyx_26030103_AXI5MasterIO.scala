package ysyx_26030103.io.axi5.master
import chisel3._
import _root_.ysyx_26030103.ysyx_26030103_IOParams
class ysyx_26030103_AXI5MasterIO(val SocXLEN: Int = 32)
    extends Bundle
    with ysyx_26030103_IOParams
    with ysyx_26030103_MasterAWPorts
    with ysyx_26030103_MasterWPorts
    with ysyx_26030103_MasterBPorts
    with ysyx_26030103_MasterARPorts
    with ysyx_26030103_MasterRPorts {
  ysyx_26030103_IOParams.requireSocXLEN(SocXLEN, "AXI5MasterIO")
}
