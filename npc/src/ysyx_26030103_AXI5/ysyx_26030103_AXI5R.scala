package ysyx_26030103.ysyx_26030103_AXI5
import chisel3._
import chisel3.util._
class ysyx_26030103_AXI5R(DataWidth: Int = 32, IdWidth: Int = 4)
    extends Bundle {
  val RID = Input(UInt(IdWidth.W))
  val RDATA = Input(UInt(DataWidth.W)) // 读回来的数据
  val RRESP = Input(UInt(2.W)) // 标准写的是0和2和3bit，0是正常，01是独占访问成功，10是从设备错误，11是解码错误
  val RLAST = Input(Bool())
  val RVALID = Input(Bool()) // 读数据有效
  val RREADY = Output(Bool()) // 读数据就绪
}
