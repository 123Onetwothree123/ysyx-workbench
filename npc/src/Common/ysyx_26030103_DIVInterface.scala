package ysyx_26030103.common
import chisel3._
import chisel3.util._
//DIV指令请求。
class ysyx_26030103_DIVInstructionRequest(val XLEN: Int = 32) extends Bundle {
  ysyx_26030103_XLENRequire(XLEN, "DIVInstructionRequest")
  val LHS = UInt(XLEN.W) //被除数
  val RHS = UInt(XLEN.W) //除数
  val Signed = Bool() //有符号运算
  val TakeRemainder = Bool() //选择余数
}
//DIV指令结果。
class ysyx_26030103_DIVInstructionResponse(val XLEN: Int = 32) extends ysyx_26030103_MDUResult(XLEN) {
  ysyx_26030103_XLENRequire(XLEN, "DIVInstructionResponse")
}
//DIV适配层握手接口。
class ysyx_26030103_DIVUnitInterface(val XLEN: Int = 32)
    extends ysyx_26030103_MDUHandshake(
      new ysyx_26030103_DIVInstructionRequest(XLEN),
      new ysyx_26030103_DIVInstructionResponse(XLEN)
    ) {
  val PerfIterations = Output(UInt(16.W)) //最近一次除法的实际迭代位数
  val PerfEarlyOut = Output(Bool()) //最近一次除法是否提前结束
}
//DIV后端接收的无符号幅值。
class ysyx_26030103_DIVCoreRequest(val Width: Int = 32) extends Bundle {
  val DividendMagnitude = UInt(Width.W) //被除数幅值
  val DivisorMagnitude = UInt(Width.W) //除数幅值
}
//DIV后端同时返回商和余数。
class ysyx_26030103_DIVCoreResponse(val Width: Int = 32) extends Bundle {
  val Quotient = UInt(Width.W) //原始商
  val Remainder = UInt(Width.W) //原始余数
}
//DIV算法后端握手接口。
class ysyx_26030103_DIVCoreInterface(val Width: Int = 32)
    extends ysyx_26030103_MDUHandshake(
      new ysyx_26030103_DIVCoreRequest(Width),
      new ysyx_26030103_DIVCoreResponse(Width)
    )
    with ysyx_26030103_MDUPerfPorts
//DIV适配层辅助函数。
object ysyx_26030103_DIVAdapterUtils {
  def QuotientNegative(LHS: UInt, RHS: UInt, Signed: Bool): Bool = {
    val Width = LHS.getWidth
    Signed && RHS =/= 0.U && (LHS(Width - 1) ^ RHS(Width - 1))
  }
  def RemainderNegative(LHS: UInt, Signed: Bool): Bool = {
    val Width = LHS.getWidth
    Signed && LHS(Width - 1)
  }
  def SelectResult(Quotient: UInt, Remainder: UInt, TakeRemainder: Bool): UInt = {
    Mux(TakeRemainder, Remainder, Quotient)
  }
}
