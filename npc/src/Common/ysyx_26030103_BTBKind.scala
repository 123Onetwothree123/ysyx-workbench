package ysyx_26030103.common
import chisel3._

// jal BTB表项类型(KindBits=2时使用): ret表项只作ret标记, 预测目标由RAS给出
object ysyx_26030103_BTBKind {
  val Jal = 0.U(2.W)
  val Call = 1.U(2.W)
  val Ret = 2.U(2.W)
}
