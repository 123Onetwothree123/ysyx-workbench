package RV32I.AXI5Lite
import chisel3._
import chisel3.util._
class AXI5LiteW(AddressWidth: Int = 32) extends Bundle {
  val WDATA = Output(UInt(AddressWidth.W)) // 写数据
  val WSTRB = Output(UInt((AddressWidth / 8).W)) // mask掩码
  val WVALID = Output(Bool()) // 有效的
  val WREADY = Input(Bool()) // 准备好了
}
