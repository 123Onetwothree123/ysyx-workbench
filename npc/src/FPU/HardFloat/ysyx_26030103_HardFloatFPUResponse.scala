package ysyx_26030103.fpu

import chisel3._
import chisel3.util._
import hardfloat._

// HardFloat 的多周期 DIV/SQRT 和响应握手。
trait ysyx_26030103_HardFloatFPUResponse {
  this: ysyx_26030103_HardFloatFPUBase
    with ysyx_26030103_HardFloatFPUArith
    with ysyx_26030103_HardFloatFPUResult =>

  // 多周期 DIV/SQRT
  val divS = Module(new DivSqrtRecFN_small(ExpS, SigS, 0))
  val divD = Module(new DivSqrtRecFN_small(ExpD, SigD, 0))
  private def SqrtOp: Bool = op === ysyx_26030103_FpuOp.FSqrt
  divS.io.inValid := false.B
  divS.io.sqrtOp := SqrtOp
  divS.io.a := aRecS
  divS.io.b := bRecS
  divS.io.roundingMode := rm
  divS.io.detectTininess := DetectTininessUInt
  divD.io.inValid := false.B
  divD.io.sqrtOp := SqrtOp
  divD.io.a := aRecD
  divD.io.b := bRecD
  divD.io.roundingMode := rm
  divD.io.detectTininess := DetectTininessUInt

  val respValid = RegInit(false.B)
  val respResult = RegInit(0.U(FLEN.W))
  val respFlags = RegInit(0.U(5.W))
  val divActive = RegInit(false.B)
  val divAbandoned = RegInit(false.B)
  val divStartedS = RegInit(false.B)

  // FPU_PIPELINE=N：在响应边界插 N 级 valid/ready，反压沿 ready 回传。
  val respPipe =
    Module(new ysyx_26030103_FPUResponsePipeline(FLEN, Config.FPUProfile.Pipeline))
  respPipe.io.flush := io.flush
  respPipe.io.in.valid := respValid
  respPipe.io.in.bits.result := respResult
  respPipe.io.in.bits.fflags := respFlags
  io.resp <> respPipe.io.out
  val respPipeReady = respPipe.io.in.ready

  val selectedDivReady = Mux(fmtS, divS.io.inReady, divD.io.inReady)
  // io.req.ready 不能组合依赖 io.flush，否则经 CSRUnit.ExceptionTaken 成环。
  io.req.ready := !respValid && !divActive &&
    Mux(isDivSqrt, selectedDivReady, true.B)
  val divRequestFire = io.req.valid && io.req.ready && isDivSqrt && !io.flush
  divS.io.inValid := divRequestFire && fmtS && !io.flush
  divD.io.inValid := divRequestFire && fmtD && !io.flush

  when(divRequestFire) {
    divActive := true.B
    divStartedS := fmtS
    divAbandoned := false.B
  }

  val divOutValid = Mux(divStartedS,
    divS.io.outValid_div || divS.io.outValid_sqrt,
    divD.io.outValid_div || divD.io.outValid_sqrt)
  val divOutResult = Mux(divStartedS,
    BoxF32(fNFromRecFN(ExpS, SigS, divS.io.out)),
    BoxD(fNFromRecFN(ExpD, SigD, divD.io.out)))
  val divOutFlags = Mux(divStartedS, divS.io.exceptionFlags,
    divD.io.exceptionFlags)
  when(divActive && divOutValid) {
    divActive := false.B
    divAbandoned := false.B
    when(!divAbandoned) {
      respValid := true.B
      respResult := divOutResult
      respFlags := divOutFlags
    }
  }

  // 非 DIV/SQRT 在接收请求的下一拍给出寄存结果。
  when(io.req.valid && io.req.ready && !isDivSqrt && !io.flush) {
    respValid := true.B
    respResult := fastResult
    respFlags := fastFlags
  }
  when(respValid && respPipeReady) {
    respValid := false.B
  }
  when(io.flush) {
    respValid := false.B
    when(divActive) { divAbandoned := true.B }
  }

  // io.resp 已由响应流水级驱动，不再直接暴露 respValid。
}
