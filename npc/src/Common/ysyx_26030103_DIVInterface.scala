package ysyx_26030103.common
import chisel3._
import chisel3.util._
//DIV指令请求。
class ysyx_26030103_DIVInstructionRequest extends Bundle {
  val LHS = UInt(32.W) //被除数
  val RHS = UInt(32.W) //除数
  val Signed = Bool() //有符号运算
  val TakeRemainder = Bool() //选择余数
}
//DIV指令结果。
class ysyx_26030103_DIVInstructionResponse extends Bundle {
  val Result = UInt(32.W) //指令结果
}
//DIV适配层握手接口。
class ysyx_26030103_DIVUnitInterface extends Bundle {
  val Req = Flipped(Decoupled(new ysyx_26030103_DIVInstructionRequest)) //输入请求
  val Resp = Decoupled(new ysyx_26030103_DIVInstructionResponse) //输出结果
  val Flush = Input(Bool()) //取消事务
}
//DIV后端接收的无符号幅值。
class ysyx_26030103_DIVCoreRequest extends Bundle {
  val DividendMagnitude = UInt(32.W) //被除数幅值
  val DivisorMagnitude = UInt(32.W) //除数幅值
}
//DIV后端同时返回商和余数。
class ysyx_26030103_DIVCoreResponse extends Bundle {
  val Quotient = UInt(32.W) //原始商
  val Remainder = UInt(32.W) //原始余数
}
//DIV算法后端握手接口。
class ysyx_26030103_DIVCoreInterface extends Bundle {
  val Req = Flipped(Decoupled(new ysyx_26030103_DIVCoreRequest)) //输入请求
  val Resp = Decoupled(new ysyx_26030103_DIVCoreResponse) //输出商余数
  val Flush = Input(Bool()) //取消事务
}
//DIV适配层辅助函数。
object ysyx_26030103_DIVAdapterUtils {
  def Magnitude(Value: UInt, Signed: Bool): UInt = {
    val Negated = (~Value + 1.U(32.W))(31, 0)
    Mux(Signed && Value(31), Negated, Value)
  }
  def QuotientNegative(LHS: UInt, RHS: UInt, Signed: Bool): Bool = {
    Signed && RHS =/= 0.U && (LHS(31) ^ RHS(31))
  }
  def RemainderNegative(LHS: UInt, Signed: Bool): Bool = {
    Signed && LHS(31)
  }
  def RestoreSign(Value: UInt, Negative: Bool): UInt = {
    val Negated = (~Value + 1.U(32.W))(31, 0)
    Mux(Negative, Negated, Value)
  }
  def SelectResult(Quotient: UInt, Remainder: UInt, TakeRemainder: Bool): UInt = {
    Mux(TakeRemainder, Remainder, Quotient)
  }
}
