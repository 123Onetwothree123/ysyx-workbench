package ysyx_26030103.fpu

import chisel3._
import chisel3.util._

trait ysyx_26030103_FPUResponseShell {
  this: ysyx_26030103_FPU =>

  protected def fastResult: UInt
  protected def fastFlags: UInt

  protected val responseValid = RegInit(false.B)
  protected val responseResult = RegInit(0.U(Config.FPULen.W))
  protected val responseFlags =
    RegInit(0.U(ysyx_26030103_FPUFlags.Width.W))

  protected val responsePipe = Module(
    new ysyx_26030103_FPUResponsePipeline(
      Config.FPULen,
      Config.FPUProfile.Pipeline
    )
  )
  responsePipe.io.flush := io.flush
  responsePipe.io.in.valid := responseValid
  responsePipe.io.in.bits.result := responseResult
  responsePipe.io.in.bits.fflags := responseFlags
  io.response <> responsePipe.io.out
  protected val responsePipeReady = responsePipe.io.in.ready

  protected def PublishResponse(result: UInt, flags: UInt): Unit = {
    responseValid := true.B
    responseResult := result
    responseFlags := flags
  }

  protected def CaptureFastResponse(enable: Bool): Unit =
    when(enable) {
      PublishResponse(fastResult, fastFlags)
    }

  protected def ReleaseResponse(): Unit =
    when(responseValid && responsePipeReady) {
      responseValid := false.B
    }

  protected def ClearResponseOnFlush(): Unit =
    responseValid := false.B
}
