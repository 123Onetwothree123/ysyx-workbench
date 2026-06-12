package ysyx_26030103.ysyx_26030103_AXI5
import chisel3._
import chisel3.util._
class ysyx_26030103_AXI5AR(AddressWidth: Int = 32, IdWidth: Int = 4)
    extends Bundle {
  val ARID = Output(UInt(IdWidth.W))
  val ARADDR = Output(UInt(AddressWidth.W)) // 读地址
  val ARLEN = Output(UInt(8.W))
  val ARSIZE = Output(UInt(3.W))
  val ARBURST = Output(UInt(2.W))
  val ARPROT = Output(UInt(3.W)) // 保护类型
  val ARVALID = Output(Bool()) // 读地址有效
  val ARREADY = Input(Bool()) // 读地址就绪
}
