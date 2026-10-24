package ysyx_26030103.common
import chisel3._

/** 执行级交付给访存级、再交给写回级的消息。
  *
  * 架构寄存器数据、PC、分支目标、CSR 读写数据均为 XLEN 位；
  * Instruction/异常 cause 仍是 32 位；VAddr 与总线物理地址按 XLEN/
  * AddressWidth 保持完整宽度，RV64 下不再截断到 32 位。
  */
class ysyx_26030103_EXUMessage(val XLEN: Int = 32) extends Bundle {
  require(XLEN == 32 || XLEN == 64, s"EXUMessage XLEN 非法: $XLEN")
  // 与提交记录同行传递，供退休级调试/trace精确对齐。
  // Instruction 保留原始编码（16 位压缩指令零扩展）；IsCompressed 供
  // 提交点区分 mepc 对齐和非法指令 mtval 语义。
  val Instruction = UInt(32.W)
  val IsCompressed = Bool()
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
  // 64 位原始 store 数据：RV64 的 SD/FSD 与 RV32D 的 FSD 都需要完整
  // 高 32 位。RV32 整数 store 只使用低 XLEN 位（高位为零扩展）。
  val StoreData = UInt(64.W)
  // A 扩展原子访存标记。IsAtomic=1 时 LSU 走独立的 uncached 串行事务，
  // 不进入写缓冲/DCache；AtomicOp 使用 funct5 原值（见 AtomicOp 对象）。
  val IsAtomic = Bool()
  val AtomicOp = UInt(ysyx_26030103_AtomicOp.Width.W)

  // F/D 扩展：IsFP 表示任意浮点指令（用于 retire 时 mstatus.FS=Dirty），
  // FPRegisterWrite/FPWriteData 描述独立的 f 寄存器写口。FP64 区分
  // FLD/FSD 与 FLW/FSW，LSU 用它在 load 完成时做 NaN-boxing。
  val IsFP = Bool()
  val FPRegisterWrite = Bool()
  val FPWriteData = UInt(64.W)
  val FP64 = Bool()

  // 异常信息(MEM故障提交时写mepc用)
  val pc = UInt(XLEN.W)
  val ExceptionValid = Bool()
  val ExceptionCause = UInt(4.W)
}
