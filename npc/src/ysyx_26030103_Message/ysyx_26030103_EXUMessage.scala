package ysyx_26030103.ysyx_26030103_Message
import chisel3._
class ysyx_26030103_EXUMessage extends Bundle {
  // EXU算好交给MEMU/WBU的最终写回相关字段
  val Rd = UInt(5.W)
  val RegisterWrite = Bool()
  val WBSelect = UInt(2.W)
  val ALUResult = UInt(32.W)
  val LoadData = UInt(32.W) // EXU填0,由MEMU在访存完成后填真实数据
  val snpc = UInt(32.W)
  val CSRReadData = UInt(32.W)

  // 访存请求(MEMU消费)
  val MemoryValid = Bool()
  val MemoryWrite = Bool()
  val WidthSelect = UInt(2.W)
  val LoadSigned = Bool()
  val StoreData = UInt(32.W)

  // 异常信息(MEM故障提交时写mepc用)
  val pc = UInt(32.W)
  val ExceptionValid = Bool()
  val ExceptionCause = UInt(4.W)
}
