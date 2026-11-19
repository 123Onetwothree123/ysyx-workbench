package ysyx_26030103.common
import chisel3._

// Instruction 保留取指内存中的原始编码：16 位压缩指令零扩展到 32 位。
// 是否调用 RVC 展开器并计算 snpc=PC+2/PC+4 由 IDU 决定；默认 XLEN=32
// 保证既有单元测试和 RV32 配置的端口布局兼容。
class ysyx_26030103_IFUMessage(val XLEN: Int = 32) extends Bundle {
  require(XLEN == 32 || XLEN == 64, s"IFUMessage XLEN 非法: $XLEN")
  val Instruction = UInt(32.W)
  // 1=本条是 16 位压缩指令；0=32 位指令。关闭 C 扩展时恒为 0。
  val IsCompressed = Bool()
  val PC = UInt(
    XLEN.W
  ) // 和ysyx_26030103_IFU的ysyx_26030103_PC做区别，这个是取的那一刻的ysyx_26030103_PC
  // 取指时检测到的异常(目前只有指令访问错误,cause=1),随指令传递到EXU提交点统一处理
  val ExceptionValid = Bool()
  val ExceptionCause = UInt(4.W)
  // 仅用于精确调试报告：保留产生 instruction access fault
  // 的 AXI RRESP，并跟随同一条指令直到 EXU 提交点。
  val AccessFaultResponse = UInt(2.W)
  // 分支预测信息: IFU取指时由BTB+BTFN给出,随指令传到EXU做"预测错误才重定向"
  val PredTaken = Bool()
  val PredTarget = UInt(XLEN.W)
}

