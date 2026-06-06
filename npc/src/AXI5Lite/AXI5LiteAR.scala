package RV32I.AXI5Lite
import chisel3._
import chisel3.util._
class AXI5LiteAR(AddressWidth: Int = 32) extends Bundle {
  val ARADDR = Output(UInt(AddressWidth.W)) // 读地址
  val ARPROT = Output(UInt(3.W)) // 保护类型
  val ARVALID = Output(Bool()) // 读地址有效
  val ARREADY = Input(Bool()) // 读地址就绪
}
