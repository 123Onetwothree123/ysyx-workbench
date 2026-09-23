package ysyx_26030103.common
import chisel3._
class ysyx_26030103_EXUMessage extends Bundle {
  // 与提交记录同行传递，供退休级调试/trace精确对齐。
  val Instruction = UInt(32.W)
  val Retire = Bool()
  // EXU算好交给MEM/WBU的最终写回相关字段
  val Rd = UInt(5.W)
  val RegisterWrite = Bool()
  val WBSelect = UInt(2.W)
  val ALUResult = UInt(32.W)
  val LoadData = UInt(32.W) // EXU填0,由MEM在访存完成后填真实数据
  val snpc = UInt(32.W)
  // 该指令退休后的架构 PC：顺序指令为 snpc，分支/跳转/MRET
  // 为实际目标。DiffTest 必须比较这个值，而不是退休指令 PC。
  val NextPC = UInt(32.W)
  val CSRReadData = UInt(32.W)

  // 访存请求(MEM消费)
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
