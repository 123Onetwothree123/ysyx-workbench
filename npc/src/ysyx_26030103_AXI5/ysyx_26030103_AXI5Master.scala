package ysyx_26030103.ysyx_26030103_AXI5
import chisel3._
import chisel3.util._
class ysyx_26030103_AXI5Master(
    AddressWidth: Int = 32,
    DataWidth: Int = 32,
    IdWidth: Int = 4
) extends Bundle {
  val ACLK = Bool() // 时钟
  val ARESETn = Bool() // 复位的
  val AW = new ysyx_26030103_AXI5AW(AddressWidth, IdWidth)
  val W = new ysyx_26030103_AXI5W(DataWidth)
  val B = new ysyx_26030103_AXI5B(IdWidth)
  val AR = new ysyx_26030103_AXI5AR(AddressWidth, IdWidth)
  val R = new ysyx_26030103_AXI5R(DataWidth, IdWidth)
}
