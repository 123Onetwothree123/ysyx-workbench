package ysyx_26030103.io.axi5.slave
import chisel3._
import _root_.ysyx_26030103.ysyx_26030103_IOParams
class ysyx_26030103_AXI5SlaveIO(val SocXLEN: Int = 32)
    extends Bundle
    with ysyx_26030103_IOParams
    with ysyx_26030103_SlaveAWPorts
    with ysyx_26030103_SlaveWPorts
    with ysyx_26030103_SlaveBPorts
    with ysyx_26030103_SlaveARPorts
    with ysyx_26030103_SlaveRPorts {
  require(SocXLEN == 32 || SocXLEN == 64,
    s"AXI5SlaveIO SocXLEN 只允许 32 或 64，收到 $SocXLEN")
}
