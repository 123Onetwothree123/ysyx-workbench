package RV32I.AXI5Lite
import chisel3._
class AXI5LiteIO(AddressWidth: Int = 32) extends Bundle {
  val AW = new AXI5LiteAW(AddressWidth)
  val W = new AXI5LiteW(AddressWidth)
  val B = new AXI5LiteB()
  val AR = new AXI5LiteAR(AddressWidth)
  val R = new AXI5LiteR(AddressWidth)
}
