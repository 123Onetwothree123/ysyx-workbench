package ysyx_26030103.exu
import chisel3._
import _root_.ysyx_26030103.common._
//MUL ISA适配层。
class ysyx_26030103_MULUnit(
    val config: ysyx_26030103_NPCConfig = ysyx_26030103_NPCConfig() //配置
) extends ysyx_26030103_MULAdapter {
  private val Core = ysyx_26030103_MULCoreFactory.Create(config)
  private val Pending = RegInit(false.B)
  private val PendingNegative = RegInit(false.B)
  private val PendingTakeHigh = RegInit(false.B)
  val RequestNegative = ysyx_26030103_MULAdapterUtils.ResultNegative(
    IO.Req.bits.LHS,
    IO.Req.bits.RHS,
    IO.Req.bits.SignedMode
  )
  val LHSMagnitude = ysyx_26030103_MULAdapterUtils.Magnitude(
    IO.Req.bits.LHS,
    ysyx_26030103_MULSignedMode.LHSSigned(IO.Req.bits.SignedMode)
  )
  val RHSMagnitude = ysyx_26030103_MULAdapterUtils.Magnitude(
    IO.Req.bits.RHS,
    ysyx_26030103_MULSignedMode.RHSSigned(IO.Req.bits.SignedMode)
  )
  Core.IO.Flush := IO.Flush
  Core.IO.Req.bits.LHSMagnitude := LHSMagnitude
  Core.IO.Req.bits.RHSMagnitude := RHSMagnitude
  Core.IO.Resp.ready := Pending && IO.Resp.ready
  val CoreRespFire = Core.IO.Resp.valid && Core.IO.Resp.ready
  val CanAccept = !Pending || CoreRespFire
  IO.Req.ready := CanAccept && Core.IO.Req.ready
  Core.IO.Req.valid := IO.Req.valid && CanAccept
  val CoreProduct = ysyx_26030103_MULAdapterUtils.RestoreSign(
    Core.IO.Resp.bits.Product,
    PendingNegative
  )
  IO.Resp.valid := Pending && Core.IO.Resp.valid
  IO.Resp.bits.Result := ysyx_26030103_MULAdapterUtils.SelectResult(
    CoreProduct,
    PendingTakeHigh
  )
  val ReqFire = IO.Req.valid && IO.Req.ready
  val RespFire = IO.Resp.valid && IO.Resp.ready
  when(IO.Flush) {
    Pending := false.B
    PendingNegative := false.B
    PendingTakeHigh := false.B
  }.elsewhen(ReqFire) {
    Pending := true.B
    PendingNegative := RequestNegative
    PendingTakeHigh := IO.Req.bits.TakeHigh
  }.elsewhen(RespFire) {
    Pending := false.B
  }
}
