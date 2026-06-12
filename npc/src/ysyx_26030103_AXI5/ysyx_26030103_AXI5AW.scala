package ysyx_26030103.ysyx_26030103_AXI5
import chisel3._
import chisel3.util._
class ysyx_26030103_AXI5AW(AddressWidth: Int = 32, IdWidth: Int = 4)
    extends Bundle {
  val AWID = Output(UInt(IdWidth.W))
  val AWADDR = Output(UInt(AddressWidth.W)) // 写地址
  val AWLEN = Output(UInt(8.W))
  val AWSIZE = Output(UInt(3.W))
  val AWBURST = Output(UInt(2.W))
  val AWPROT = Output(UInt(3.W)) // 保护的
  val AWVALID = Output(Bool()) // 有效
  val AWREADY = Input(Bool()) // 就绪
}
