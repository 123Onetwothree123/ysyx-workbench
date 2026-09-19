package ysyx_26030103.exu
import chisel3._
import _root_.ysyx_26030103.common._
//DIV ISA适配层。
class ysyx_26030103_DIVUnit(
    val config: ysyx_26030103_NPCConfig = ysyx_26030103_NPCConfig() //配置
) extends Module {
  val IO = _root_.chisel3.IO(new ysyx_26030103_DIVUnitInterface) //除法适配接口
  private val Core = ysyx_26030103_DIVCoreFactory.Create(config)
  private val Pending = RegInit(false.B)
  private val PendingQuotientNegative = RegInit(false.B)
  private val PendingRemainderNegative = RegInit(false.B)
  private val PendingTakeRemainder = RegInit(false.B)
  val DividendMagnitude = ysyx_26030103_DIVAdapterUtils.Magnitude(
    IO.Req.bits.LHS,
    IO.Req.bits.Signed
  )
  val DivisorMagnitude = ysyx_26030103_DIVAdapterUtils.Magnitude(
    IO.Req.bits.RHS,
    IO.Req.bits.Signed
  )
  val QuotientNegative = ysyx_26030103_DIVAdapterUtils.QuotientNegative(
    IO.Req.bits.LHS,
    IO.Req.bits.RHS,
    IO.Req.bits.Signed
  )
  val RemainderNegative = ysyx_26030103_DIVAdapterUtils.RemainderNegative(
    IO.Req.bits.LHS,
    IO.Req.bits.Signed
  )
  Core.IO.Flush := IO.Flush
  Core.IO.Req.valid := IO.Req.valid
  Core.IO.Req.bits.DividendMagnitude := DividendMagnitude
  Core.IO.Req.bits.DivisorMagnitude := DivisorMagnitude
  IO.Req.ready := Core.IO.Req.ready
  Core.IO.Resp.ready := IO.Resp.ready
  val Quotient = ysyx_26030103_DIVAdapterUtils.RestoreSign(
    Core.IO.Resp.bits.Quotient,
    PendingQuotientNegative
  )
  val Remainder = ysyx_26030103_DIVAdapterUtils.RestoreSign(
    Core.IO.Resp.bits.Remainder,
    PendingRemainderNegative
  )
  IO.Resp.valid := Pending && Core.IO.Resp.valid
  IO.Resp.bits.Result := ysyx_26030103_DIVAdapterUtils.SelectResult(
    Quotient,
    Remainder,
    PendingTakeRemainder
  )
  val ReqFire = IO.Req.valid && IO.Req.ready
  val RespFire = IO.Resp.valid && IO.Resp.ready
  when(IO.Flush) {
    Pending := false.B
    PendingQuotientNegative := false.B
    PendingRemainderNegative := false.B
    PendingTakeRemainder := false.B
  }.elsewhen(ReqFire) {
    Pending := true.B
    PendingQuotientNegative := QuotientNegative
    PendingRemainderNegative := RemainderNegative
    PendingTakeRemainder := IO.Req.bits.TakeRemainder
  }.elsewhen(RespFire) {
    Pending := false.B
  }
}
