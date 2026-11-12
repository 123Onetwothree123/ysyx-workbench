package ysyx_26030103.infra
import _root_.ysyx_26030103.common.ysyx_26030103_KconfigDefaults
import chisel3._
class ysyx_26030103_AXI5IO(
    AddressWidth: Int = ysyx_26030103_KconfigDefaults.NpcAddressWidth,
    DataWidth: Int = ysyx_26030103_KconfigDefaults.NpcInternalBusWidth,
    IdWidth: Int = ysyx_26030103_KconfigDefaults.AxiIdWidth
) extends Bundle {
  val AW = new ysyx_26030103_AXI5AW(AddressWidth, IdWidth)
  val W = new ysyx_26030103_AXI5W(DataWidth)
  val B = new ysyx_26030103_AXI5B(IdWidth)
  val AR = new ysyx_26030103_AXI5AR(AddressWidth, IdWidth)
  val R = new ysyx_26030103_AXI5R(DataWidth, IdWidth)
}
