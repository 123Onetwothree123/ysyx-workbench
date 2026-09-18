package ysyx_26030103.exu

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common.ysyx_26030103_NPCConfig
import _root_.ysyx_26030103.common.ysyx_26030103_MDUOp

/**
  * M 扩展执行单元的请求。
  *
  * LHS/RHS 保留寄存器中的 32 位原始位模式，具体的有符号解释由
  * MDUOp 决定；这样 EXU 不需要在发请求前自行拆分八种指令。
  */
class ysyx_26030103_MDURequest extends Bundle {
  val LHS = UInt(32.W)
  val RHS = UInt(32.W)
  val MDUOp = UInt(ysyx_26030103_MDUOp.Width.W)
}

/** MDU 返回的 RV32 结果。 */
class ysyx_26030103_MDUResponse extends Bundle {
  val Result = UInt(32.W)
}

/**
  * MDU 的统一事务接口。
  *
  * Req/Resp 遵守 Decoupled 协议。第一版只允许一个请求在途；请求被
  * Req.fire 接收后，至少经过一个时钟周期才会产生 Resp.valid。Resp.valid
  * 置高而 Resp.ready 为低时，Result 保持不变。Flush 优先级最高，会取
  * 消在途请求和尚未被接收的响应。
  */
class ysyx_26030103_MDUInterface extends Bundle {
  val Req = Flipped(Decoupled(new ysyx_26030103_MDURequest))
  val Resp = Decoupled(new ysyx_26030103_MDUResponse)
  val Flush = Input(Bool())

  // 兼容尚未完成命名迁移的调用点；两者引用同一组端口。
  def req = Req
  def resp = Resp
  def flush = Flush
}

/**
  * RV32M 参考实现。
  *
  * 这里故意不绑定某一种乘法/除法算法：算术表达式只作为正确性基线，
  * 后续可在同一 Req/Resp 协议下替换为迭代式、Booth、Wallace 或 Dadda
  * 后端。结果寄存器把组合计算与握手隔开，因此不会产生零周期响应。
  */
class ysyx_26030103_MDU(
    val config: ysyx_26030103_NPCConfig = ysyx_26030103_NPCConfig()
) extends Module {
  val io = IO(new ysyx_26030103_MDUInterface)

  val ResultValid = RegInit(false.B)
  val ResultReg = RegInit(0.U(32.W))

  // Flush 对外表现为立即取消：同拍不接收新请求，也不暴露旧响应。
  io.Req.ready := !io.Flush && (!ResultValid || io.Resp.ready)
  io.Resp.valid := ResultValid && !io.Flush
  io.Resp.bits.Result := ResultReg

  val Request = io.Req.bits
  val IsMUL = Request.MDUOp <= ysyx_26030103_MDUOp.MULHU
  val IsDIV = Request.MDUOp === ysyx_26030103_MDUOp.DIV
  val IsDIVU = Request.MDUOp === ysyx_26030103_MDUOp.DIVU
  val IsREM = Request.MDUOp === ysyx_26030103_MDUOp.REM
  val IsREMU = Request.MDUOp === ysyx_26030103_MDUOp.REMU

  // 乘法：先把两个 32 位位模式扩展为 64 位，再取 128 位乘积的低 64 位。
  // 对有符号操作数做符号扩展、对无符号操作数做零扩展；低 64 位正好
  // 是 RV32M 所需的二补码乘积，即使是 MULHSU 也不会丢失符号信息。
  val LHSIsSigned = Request.MDUOp === ysyx_26030103_MDUOp.MULH ||
    Request.MDUOp === ysyx_26030103_MDUOp.MULHSU
  val RHSIsSigned = Request.MDUOp === ysyx_26030103_MDUOp.MULH
  val LHSWide = Mux(
    LHSIsSigned,
    Cat(Fill(32, Request.LHS(31)), Request.LHS),
    Cat(0.U(32.W), Request.LHS)
  )
  val RHSWide = Mux(
    RHSIsSigned,
    Cat(Fill(32, Request.RHS(31)), Request.RHS),
    Cat(0.U(32.W), Request.RHS)
  )
  val ProductWide = LHSWide * RHSWide
  val Product = ProductWide(63, 0)
  val ProductResult = Mux(
    Request.MDUOp === ysyx_26030103_MDUOp.MUL,
    Product(31, 0),
    Product(63, 32)
  )

  // 除法/余数使用 SInt 运算表达 RV32 的“向零截断”语义。除零和
  // INT_MIN/-1 是规范规定的特殊结果，显式优先于 Chisel 的除法表达式。
  val LHSIsMinInt = Request.LHS === "h80000000".U(32.W)
  val RHSIsMinusOne = Request.RHS === "hffffffff".U(32.W)
  val RHSIsZero = Request.RHS === 0.U
  val SignedLHS = Request.LHS.asSInt
  val SignedRHS = Request.RHS.asSInt
  val SignedQuotient = (SignedLHS / SignedRHS).asUInt
  val SignedRemainder = (SignedLHS % SignedRHS).asUInt
  val UnsignedQuotient = Request.LHS / Request.RHS
  val UnsignedRemainder = Request.LHS % Request.RHS
  val DivResult = Mux(
    RHSIsZero,
    "hffffffff".U(32.W),
    Mux(
      IsDIV && LHSIsMinInt && RHSIsMinusOne,
      "h80000000".U(32.W),
      Mux(IsDIV, SignedQuotient, UnsignedQuotient)
    )
  )
  val RemResult = Mux(
    RHSIsZero,
    Request.LHS,
    Mux(
      IsREM && LHSIsMinInt && RHSIsMinusOne,
      0.U(32.W),
      Mux(IsREM, SignedRemainder, UnsignedRemainder)
    )
  )
  val ComputedResult = Mux(
    IsMUL,
    ProductResult,
    Mux(IsDIV || IsDIVU, DivResult, RemResult)
  )

  val ReqFire = io.Req.valid && io.Req.ready
  val RespFire = io.Resp.valid && io.Resp.ready

  when(io.Flush) {
    ResultValid := false.B
    ResultReg := 0.U
  }.elsewhen(ReqFire) {
    ResultReg := ComputedResult
    ResultValid := true.B
  }.elsewhen(RespFire) {
    ResultValid := false.B
  }
}
