package ysyx_26030103.infra
import _root_.ysyx_26030103.common.ysyx_26030103_KconfigDefaults
import chisel3._
class ysyx_26030103_AXI5Slave(
    AddressWidth: Int = ysyx_26030103_KconfigDefaults.NpcAddressWidth,
    DataWidth: Int = ysyx_26030103_KconfigDefaults.NpcInternalBusWidth,
    IdWidth: Int = ysyx_26030103_KconfigDefaults.AxiIdWidth
) extends Bundle {
  val ACLK = Bool() // 时钟
  val ARESETn = Bool() // 复位的
  val AW = Flipped(new ysyx_26030103_AXI5AW(AddressWidth, IdWidth))
  val W = Flipped(new ysyx_26030103_AXI5W(DataWidth))
  val B = Flipped(new ysyx_26030103_AXI5B(IdWidth))
  val AR = Flipped(new ysyx_26030103_AXI5AR(AddressWidth, IdWidth))
  val R = Flipped(new ysyx_26030103_AXI5R(DataWidth, IdWidth))
}
