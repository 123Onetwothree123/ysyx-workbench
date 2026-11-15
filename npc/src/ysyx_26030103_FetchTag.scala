package ysyx_26030103
import chisel3._

/** IFU 接受请求时保存的虚拟 PC/预测快照，用于响应级恢复架构 PC。 */
class ysyx_26030103_FetchTag(val XLEN: Int = 32) extends Bundle {
  require(XLEN == 32 || XLEN == 64, s"FetchTag XLEN 非法: $XLEN")
  val pc = UInt(XLEN.W)
  val predTaken = Bool()
  val predTarget = UInt(XLEN.W)
}
