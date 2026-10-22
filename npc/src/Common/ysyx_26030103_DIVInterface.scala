package ysyx_26030103.common
import chisel3._
import chisel3.util._
//DIV指令请求。
class ysyx_26030103_DIVInstructionRequest(val XLEN: Int = 32) extends Bundle {
  require(XLEN == 32 || XLEN == 64, s"DIVInstructionRequest XLEN 非法: $XLEN")
  val LHS = UInt(XLEN.W) //被除数
  val RHS = UInt(XLEN.W) //除数
  val Signed = Bool() //有符号运算
  val TakeRemainder = Bool() //选择余数
}
//DIV指令结果。
class ysyx_26030103_DIVInstructionResponse(val XLEN: Int = 32) extends Bundle {
  require(XLEN == 32 || XLEN == 64, s"DIVInstructionResponse XLEN 非法: $XLEN")
  val Result = UInt(XLEN.W) //指令结果
}
//DIV适配层握手接口。
class ysyx_26030103_DIVUnitInterface(val XLEN: Int = 32) extends Bundle {
  val Req = Flipped(Decoupled(new ysyx_26030103_DIVInstructionRequest(XLEN))) //输入请求
  val Resp = Decoupled(new ysyx_26030103_DIVInstructionResponse(XLEN)) //输出结果
  val Flush = Input(Bool()) //取消事务
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
class ysyx_26030103_DIVCoreInterface(val Width: Int = 32) extends Bundle {
  val Req = Flipped(Decoupled(new ysyx_26030103_DIVCoreRequest(Width))) //输入请求
  val Resp = Decoupled(new ysyx_26030103_DIVCoreResponse(Width)) //输出商余数
  val Flush = Input(Bool()) //取消事务
}
//DIV适配层辅助函数。
object ysyx_26030103_DIVAdapterUtils {
  def Magnitude(Value: UInt, Signed: Bool): UInt = {
    val Width = Value.getWidth
    val Negated = (~Value + 1.U(Width.W))(Width - 1, 0)
    Mux(Signed && Value(Width - 1), Negated, Value)
  }
  def QuotientNegative(LHS: UInt, RHS: UInt, Signed: Bool): Bool = {
    val Width = LHS.getWidth
    Signed && RHS =/= 0.U && (LHS(Width - 1) ^ RHS(Width - 1))
  }
  def RemainderNegative(LHS: UInt, Signed: Bool): Bool = {
    val Width = LHS.getWidth
    Signed && LHS(Width - 1)
  }
  def RestoreSign(Value: UInt, Negative: Bool, Width: Int): UInt = {
    val Negated = (~Value + 1.U(Width.W))(Width - 1, 0)
    Mux(Negative, Negated, Value(Width - 1, 0))
  }
  def SelectResult(Quotient: UInt, Remainder: UInt, TakeRemainder: Bool): UInt = {
    Mux(TakeRemainder, Remainder, Quotient)
  }
}
