package ysyx_26030103.infra
import chisel3._
import chisel3.util._
class ysyx_26030103_AXI5W(DataWidth: Int = 32) extends Bundle {
  val WDATA = Output(UInt(DataWidth.W)) // 写数据
  val WSTRB = Output(UInt((DataWidth / 8).W)) // mask掩码
  val WLAST = Output(Bool())
  val WVALID = Output(Bool()) // 有效的
  val WREADY = Input(Bool()) // 准备好了
}
