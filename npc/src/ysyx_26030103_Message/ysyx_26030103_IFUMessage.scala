package ysyx_26030103.ysyx_26030103_Message
import chisel3._
class ysyx_26030103_IFUMessage extends Bundle {
  val Instruction = UInt(32.W)
  val pc = UInt(32.W)//和ysyx_26030103_IFU的ysyx_26030103_PC做区别，这个是取的那一刻的ysyx_26030103_PC
  // 取指时检测到的异常(目前只有指令访问错误,cause=1),随指令传递到EXU提交点统一处理
  val ExceptionValid = Bool()
  val ExceptionCause = UInt(4.W)
  // 分支预测信息: IFU取指时由BTB+BTFN给出,随指令传到EXU做"预测错误才重定向"
  val pred_taken = Bool()
  val pred_target = UInt(32.W)
}
