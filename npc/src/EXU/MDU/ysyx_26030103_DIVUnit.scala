package ysyx_26030103.exu
import chisel3._
import _root_.ysyx_26030103.common._
import _root_.ysyx_26030103.config._
//DIV ISA适配层。
class ysyx_26030103_DIVUnit(
    val config: ysyx_26030103_NPCConfig = ysyx_26030103_NPCConfig() //配置
) extends Module {
  private val XLEN = config.XLEN
  val IO = _root_.chisel3.IO(new ysyx_26030103_DIVUnitInterface(XLEN)) //除法适配接口
  private val core = ysyx_26030103_DIVCoreFactory.Create(config)
  private val pending = RegInit(false.B)
  private val pendingQuotientNegative = RegInit(false.B)
  private val pendingRemainderNegative = RegInit(false.B)
  private val pendingTakeRemainder = RegInit(false.B)
  val dividendMagnitude = ysyx_26030103_DIVAdapterUtils.Magnitude(
    IO.Request.bits.LHS,
    IO.Request.bits.Signed
  )
  val divisorMagnitude = ysyx_26030103_DIVAdapterUtils.Magnitude(
    IO.Request.bits.RHS,
    IO.Request.bits.Signed
  )
  val quotientNegative = ysyx_26030103_DIVAdapterUtils.QuotientNegative(
    IO.Request.bits.LHS,
    IO.Request.bits.RHS,
    IO.Request.bits.Signed
  )
  val remainderNegative = ysyx_26030103_DIVAdapterUtils.RemainderNegative(
    IO.Request.bits.LHS,
    IO.Request.bits.Signed
  )
  core.IO.Flush := IO.Flush
  core.IO.Request.valid := IO.Request.valid
  core.IO.Request.bits.DividendMagnitude := dividendMagnitude
  core.IO.Request.bits.DivisorMagnitude := divisorMagnitude
  IO.Request.ready := core.IO.Request.ready
  core.IO.Response.ready := IO.Response.ready
  val quotient = ysyx_26030103_DIVAdapterUtils.RestoreSign(
    core.IO.Response.bits.Quotient,
    pendingQuotientNegative,
    XLEN
  )
  val remainder = ysyx_26030103_DIVAdapterUtils.RestoreSign(
    core.IO.Response.bits.Remainder,
    pendingRemainderNegative,
    XLEN
  )
  IO.Response.valid := pending && core.IO.Response.valid
  IO.Response.bits.Result := ysyx_26030103_DIVAdapterUtils.SelectResult(
    quotient,
    remainder,
    pendingTakeRemainder
  )
  val requestFire = IO.Request.valid && IO.Request.ready
  val responseFire = IO.Response.valid && IO.Response.ready
  IO.PerfIterations := core.IO.IterationCount
  IO.PerfEarlyOut := core.IO.EarlyOut
  when(IO.Flush) {
    pending := false.B
    pendingQuotientNegative := false.B
    pendingRemainderNegative := false.B
    pendingTakeRemainder := false.B
  }.elsewhen(requestFire) {
    pending := true.B
    pendingQuotientNegative := quotientNegative
    pendingRemainderNegative := remainderNegative
    pendingTakeRemainder := IO.Request.bits.TakeRemainder
  }.elsewhen(responseFire) {
    pending := false.B
  }
}
