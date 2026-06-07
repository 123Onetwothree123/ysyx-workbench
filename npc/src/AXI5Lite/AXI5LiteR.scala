package RV32I.AXI5Lite
import chisel3._
import chisel3.util._
class AXI5LiteR(AddressWidth: Int = 32) extends Bundle {
  val RDATA = Input(UInt(AddressWidth.W)) // 读回来的数据
  val RRESP = Input(UInt(2.W)) // 标准写的是0和2和3bit，0是正常，01是独占访问成功，10是从设备错误，11是解码错误
  val RVALID = Input(Bool()) // 读数据有效
  val RREADY = Output(Bool()) // 读数据就绪
}
