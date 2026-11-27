package ysyx_26030103.exu
import chisel3._
import chisel3.util._
trait ysyx_26030103_IterativeCore[TReq <: Data, TResp <: Data] {
  this: Module =>
  protected def IterativeRequest: DecoupledIO[TReq]
  protected def IterativeResponse: DecoupledIO[TResp]
  protected def IterativeFlush: Bool
  protected val busy = RegInit(false.B)
  protected val resultValid = RegInit(false.B)
  protected val requestFire = IterativeRequest.valid && IterativeRequest.ready
  protected val responseFire = IterativeResponse.valid && IterativeResponse.ready
  protected def IterativeHandshake(): Unit = {
    IterativeRequest.ready := !IterativeFlush && !busy && (!resultValid || IterativeResponse.ready)
    IterativeResponse.valid := resultValid && !IterativeFlush
  }
  protected def RunIterative(
      ClearState: () => Unit,
      StartTransaction: () => Unit,
      BusyStep: () => Unit
  ): Unit = {
    when(IterativeFlush) {
      busy := false.B
      resultValid := false.B
      ClearState()
    }.elsewhen(requestFire) {
      busy := true.B
      resultValid := false.B
      StartTransaction()
    }.elsewhen(busy) {
      BusyStep()
    }.elsewhen(responseFire) {
      resultValid := false.B
    }
  }
}
