package RV32I.AXI5Lite
import chisel3._
import chisel3.util._
class AXI5LiteSlave(AddressWidth: Int = 32) extends Bundle {
  val ACLK = Bool() // 时钟
  val ARESETn = Bool() // 复位的
  val AW = Flipped(new AXI5LiteAW(AddressWidth))
  val W = Flipped(new AXI5LiteW(AddressWidth))
  val B = Flipped(new AXI5LiteB())
  val AR = Flipped(new AXI5LiteAR(AddressWidth))
  val R = Flipped(new AXI5LiteR(AddressWidth))
}
