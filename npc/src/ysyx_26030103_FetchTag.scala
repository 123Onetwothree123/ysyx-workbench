package ysyx_26030103
import _root_.ysyx_26030103.common.ysyx_26030103_XLENRequire
import chisel3._

// IFU 接受请求时保存的虚拟 PC/预测快照，用于响应级恢复架构 PC。
class ysyx_26030103_FetchTag(val XLEN: Int = 32) extends Bundle {
  ysyx_26030103_XLENRequire(XLEN, "FetchTag")
  val PC = UInt(XLEN.W)
  val predTaken = Bool()
  val predTarget = UInt(XLEN.W)
}
