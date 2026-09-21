package ysyx_26030103.common
import chisel3._
import chisel3.util._
//MUL适配层使用的操作数符号模式。
object ysyx_26030103_MULSignedMode {
  final val Width: Int = 2
  //bit1：LHS有符号；bit0：RHS有符号。
  val UU: UInt = "b00".U(Width.W)
  val US: UInt = "b01".U(Width.W)
  val SU: UInt = "b10".U(Width.W)
  val SS: UInt = "b11".U(Width.W)
  def LHSSigned(Mode: UInt): Bool = Mode(1)
  def RHSSigned(Mode: UInt): Bool = Mode(0)
}
//MUL指令请求。
class ysyx_26030103_MULInstructionRequest extends Bundle {
  val LHS = UInt(32.W) //左操作数
  val RHS = UInt(32.W) //右操作数
  val SignedMode = UInt(ysyx_26030103_MULSignedMode.Width.W) //符号模式
  val TakeHigh = Bool() //选择高32位
}
//MUL指令结果。
class ysyx_26030103_MULInstructionResponse extends Bundle {
  val Result = UInt(32.W) //指令结果
}
//MUL适配层握手接口；Flush取消在途事务。
class ysyx_26030103_MULAdapterInterface extends Bundle {
  val Req = Flipped(Decoupled(new ysyx_26030103_MULInstructionRequest)) //输入请求
  val Resp = Decoupled(new ysyx_26030103_MULInstructionResponse) //输出结果
  val Flush = Input(Bool()) //取消事务
}
//MUL后端接收的无符号幅值。
class ysyx_26030103_MULCoreRequest(val Width: Int = 32) extends Bundle {
  val LHSMagnitude = UInt(Width.W) //左幅值
  val RHSMagnitude = UInt(Width.W) //右幅值
}
//MUL后端返回的2N位乘积；默认N=32时为64位。
class ysyx_26030103_MULCoreResponse(val Width: Int = 32) extends Bundle {
  val Product = UInt((2 * Width).W) //原始乘积
}
//所有MUL算法后端共用的握手接口。
class ysyx_26030103_MULCoreInterface(val Width: Int = 32) extends Bundle {
  val Req = Flipped(Decoupled(new ysyx_26030103_MULCoreRequest(Width))) //输入请求
  val Resp = Decoupled(new ysyx_26030103_MULCoreResponse(Width)) //输出乘积
  val Flush = Input(Bool()) //取消事务
}
//MUL适配层辅助函数。
object ysyx_26030103_MULAdapterUtils {
  def Magnitude(Value: UInt, Signed: Bool): UInt = {
    val Negated = (~Value + 1.U(32.W))(31, 0)
    Mux(Signed && Value(31), Negated, Value)
  }
  def ResultNegative(LHS: UInt, RHS: UInt, SignedMode: UInt): Bool = {
    val LHSNegative = ysyx_26030103_MULSignedMode.LHSSigned(SignedMode) && LHS(31)
    val RHSNegative = ysyx_26030103_MULSignedMode.RHSSigned(SignedMode) && RHS(31)
    LHSNegative ^ RHSNegative
  }
  def RestoreSign(Product: UInt, Negative: Bool): UInt = {
    val Negated = (~Product + 1.U(64.W))(63, 0)
    Mux(Negative, Negated, Product)
  }
  def SelectResult(Product: UInt, TakeHigh: Bool): UInt = {
    Mux(TakeHigh, Product(63, 32), Product(31, 0))
  }
}
