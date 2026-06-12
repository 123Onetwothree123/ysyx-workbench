package ysyx_26030103.ysyx_26030103_AXI5
import chisel3._
import chisel3.util._
class ysyx_26030103_AXI5Slave(
    AddressWidth: Int = 32,
    DataWidth: Int = 32,
    IdWidth: Int = 4
) extends Bundle {
  val ACLK = Bool() // 时钟
  val ARESETn = Bool() // 复位的
  val AW = Flipped(new ysyx_26030103_AXI5AW(AddressWidth, IdWidth))
  val W = Flipped(new ysyx_26030103_AXI5W(DataWidth))
  val B = Flipped(new ysyx_26030103_AXI5B(IdWidth))
  val AR = Flipped(new ysyx_26030103_AXI5AR(AddressWidth, IdWidth))
  val R = Flipped(new ysyx_26030103_AXI5R(DataWidth, IdWidth))
}
