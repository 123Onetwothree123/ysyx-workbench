package ysyx_26030103.common
import chisel3._
import chisel3.util._

/*
 * 乘法器接口分成两层：
 * 1. MUL 适配层：处理指令语义、符号和高低位结果选择；
 * 2. MUL 核心层：只计算两个无符号幅值的 32×32 乘积。
 */

/** ISA 适配层中两个操作数的符号属性。 */
object ysyx_26030103_MULSignedMode {
  final val Width: Int = 2
  // bit1：LHS 有符号；bit0：RHS 有符号。
  val UU: UInt = "b00".U(Width.W)
  val US: UInt = "b01".U(Width.W)
  val SU: UInt = "b10".U(Width.W)
  val SS: UInt = "b11".U(Width.W)
  def LHSSigned(Mode: UInt): Bool = Mode(1)
  def RHSSigned(Mode: UInt): Bool = Mode(0)
}

/** ISA 适配层接收的一条乘法请求。 */
class ysyx_26030103_MULInstructionRequest extends Bundle {
  val LHS = UInt(32.W) // 左操作数
  val RHS = UInt(32.W) // 右操作数
  val SignedMode = UInt(ysyx_26030103_MULSignedMode.Width.W) // 符号模式
  val TakeHigh = Bool() // 是否返回乘积高 32 位
}

/** ISA 适配层返回的 32 位结果。 */
class ysyx_26030103_MULInstructionResponse extends Bundle {
  val Result = UInt(32.W)
}

/**
  * MUL 适配层的握手接口。
  *
  * Req.fire：接收一条请求。
  * Resp.fire：完成一条响应。
  * Resp.valid 且 Resp.ready 为低时，响应内容必须保持不变。
  * Flush 会取消在途请求和未消费结果，并且优先于同拍的握手。
  */
class ysyx_26030103_MULAdapterInterface extends Bundle {
  val Req = Flipped(Decoupled(new ysyx_26030103_MULInstructionRequest))
  val Resp = Decoupled(new ysyx_26030103_MULInstructionResponse)
  val Flush = Input(Bool())
}

/**
  * MUL 后端的规范化请求。
  *
  * 适配层已经完成符号处理；后端只接收两个无符号幅值，
  * 并计算 32×32 位的 64 位乘积。
  */
class ysyx_26030103_MULCoreRequest extends Bundle {
  val LHSMagnitude = UInt(32.W) // LHS 的无符号幅值
  val RHSMagnitude = UInt(32.W) // RHS 的无符号幅值
}

/** MUL 后端返回的原始 64 位乘积。 */
class ysyx_26030103_MULCoreResponse extends Bundle {
  val Product = UInt(64.W)
}

/**
  * 所有 MUL 后端共用的握手接口。
  *
  * 该接口不包含符号模式、指令高低位选择、RD 或 PC，
  * 因而普通移位累加、Booth、Wallace、Dadda 可以互换。
  *
  * 后端只在 Req.fire 时采样操作数；每个未被 Flush 的请求按顺序返回一个响应。
  * Resp.valid 且 Resp.ready 为低时，Product 必须保持稳定。
  * 第一阶段只允许一个请求在途；未来支持多请求并行时，再增加 Tag 或元数据队列。
  */
class ysyx_26030103_MULCoreInterface extends Bundle {
  val Req = Flipped(Decoupled(new ysyx_26030103_MULCoreRequest))
  val Resp = Decoupled(new ysyx_26030103_MULCoreResponse)
  val Flush = Input(Bool())
}

/** 供 MUL 适配层使用的符号、幅值和结果选择辅助函数。 */
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
