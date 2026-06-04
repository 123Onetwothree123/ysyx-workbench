package RV32I.AXI5Lite
import chisel3._
import chisel3.util._
class AXI5LiteMaster(AddressWidth: Int = 32) extends Bundle {
  val ACLK = Bool() // 时钟
  val ARESETn = Bool() // 复位的
  val AW = new AXI5LiteAW(AddressWidth)
  val W = new AXI5LiteW(AddressWidth)
  val B = new AXI5LiteB()
  val AR = new AXI5LiteAR(AddressWidth)
  val R = new AXI5LiteR(AddressWidth)
}
