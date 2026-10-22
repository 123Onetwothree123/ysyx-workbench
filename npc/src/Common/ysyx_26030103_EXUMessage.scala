package ysyx_26030103.common
import chisel3._

/** 执行级交付给访存级、再交给写回级的消息。
  *
  * 架构寄存器数据、PC、分支目标、CSR 读写数据均为 XLEN 位；
  * Instruction/异常 cause 仍是 32 位。物理地址在 LSU 内截断到 32 位。
  */
class ysyx_26030103_EXUMessage(val XLEN: Int = 32) extends Bundle {
  require(XLEN == 32 || XLEN == 64, s"EXUMessage XLEN 非法: $XLEN")
  // 与提交记录同行传递，供退休级调试/trace精确对齐。
  val Instruction = UInt(32.W)
  val Retire = Bool()
  // EXU算好交给MEM/WBU的最终写回相关字段
  val Rd = UInt(5.W)
  val RegisterWrite = Bool()
  val WBSelect = UInt(2.W)
  val ALUResult = UInt(XLEN.W)
  // DMMU 翻译前的虚拟访存地址；VIPT Cache 用虚拟地址选 index。
  val VAddr = UInt(XLEN.W)
  val LoadData = UInt(XLEN.W) // EXU填0,由MEM在访存完成后填真实数据
  val snpc = UInt(XLEN.W)
  // 该指令退休后的架构 PC：顺序指令为 snpc，分支/跳转/MRET
  // 为实际目标。DiffTest 必须比较这个值，而不是退休指令 PC。
  val NextPC = UInt(XLEN.W)
  val CSRReadData = UInt(XLEN.W)
  // 该指令提交后的精确 CSR 快照。CSR 在 EXU 产生副作用，
  // 不能在 WBU 退休时直接采样 CSR 实时值，否则可能包含年轻指令的更新。
  val CSRStateMstatus = UInt(XLEN.W)
  val CSRStateMtvec = UInt(XLEN.W)
  val CSRStateMepc = UInt(XLEN.W)
  val CSRStateMcause = UInt(XLEN.W)

  // 访存请求(MEM消费)
  val MemoryValid = Bool()
  val MemoryWrite = Bool()
  val WidthSelect = UInt(2.W)
  val LoadSigned = Bool()
  val StoreData = UInt(XLEN.W)

  // 异常信息(MEM故障提交时写mepc用)
  val pc = UInt(XLEN.W)
  val ExceptionValid = Bool()
  val ExceptionCause = UInt(4.W)
}
