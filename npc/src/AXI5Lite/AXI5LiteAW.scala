package RV32I.AXI5Lite
import chisel3._
import chisel3.util._
class AXI5LiteAW(AddressWidth: Int = 32) extends Bundle {
  val AWADDR = Output(UInt(AddressWidth.W)) // 写地址
  val AWPROT = Output(UInt(3.W)) // 保护的
  val AWVALID = Output(Bool()) // 有效
  val AWREADY = Input(Bool()) // 就绪
}
