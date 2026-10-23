package ysyx_26030103.common
import chisel3._
import _root_.ysyx_26030103.common.ysyx_26030103_ALUFunction.CtrlWidth

/** 取指级交付给译码级的消息。
  *
  * Instruction 保留取指内存中的原始编码：16 位压缩指令零扩展到 32 位，
  * 32 位指令保持原值。IsCompressed 指示低 16 位是否是完整的 RVC 指令，
  * 由 IDU 决定是否调用 RVC 展开器并计算 snpc=pc+2/pc+4。
  * PC/预测目标跟随 XLEN。默认值 32 保证既有单元测试和 RV32 配置的
  * 端口布局兼容（新增字段由 0.U.asTypeOf 自动置零）。
  */
class ysyx_26030103_IFUMessage(val XLEN: Int = 32) extends Bundle {
  require(XLEN == 32 || XLEN == 64, s"IFUMessage XLEN 非法: $XLEN")
  val Instruction = UInt(32.W)
  // 1=本条是 16 位压缩指令；0=32 位指令。关闭 C 扩展时恒为 0。
  val IsCompressed = Bool()
  val pc = UInt(
    XLEN.W
  ) // 和ysyx_26030103_IFU的ysyx_26030103_PC做区别，这个是取的那一刻的ysyx_26030103_PC
  // 取指时检测到的异常(目前只有指令访问错误,cause=1),随指令传递到EXU提交点统一处理
  val ExceptionValid = Bool()
  val ExceptionCause = UInt(4.W)
  // 仅用于精确调试报告：保留产生 instruction access fault
  // 的 AXI RRESP，并跟随同一条指令直到 EXU 提交点。
  val AccessFaultResp = UInt(2.W)
  // 分支预测信息: IFU取指时由BTB+BTFN给出,随指令传到EXU做"预测错误才重定向"
  val pred_taken = Bool()
  val pred_target = UInt(XLEN.W)
}

