package ysyx_26030103.fpu

import chisel3._
import hardfloat._

// HardFloat 的多周期 DIV/SQRT 和响应握手。
trait ysyx_26030103_HardFloatFPUResponse {
  this: ysyx_26030103_HardFloatFPUBase
    with ysyx_26030103_HardFloatFPUArith
    with ysyx_26030103_HardFloatFPUResult
    with ysyx_26030103_FPUResponseShell =>

  // 多周期 DIV/SQRT
  val divS = Module(new DivSqrtRecFN_small(expS, sigS, 0))
  val divD = Module(new DivSqrtRecFN_small(expD, sigD, 0))
  private def sqrtOp: Bool = op === ysyx_26030103_FPUOp.FSqrt
  divS.io.sqrtOp := sqrtOp
  divS.io.a := aRecS
  divS.io.b := bRecS
  divS.io.roundingMode := rm
  divS.io.detectTininess := detectTininessUInt
  divD.io.sqrtOp := sqrtOp
  divD.io.a := aRecD
  divD.io.b := bRecD
  divD.io.roundingMode := rm
  divD.io.detectTininess := detectTininessUInt

  val divActive = RegInit(false.B)
  val divAbandoned = RegInit(false.B)
  val ActiveDivIsSingle = RegInit(false.B)

  val selectedDivReady = Mux(fmtS, divS.io.inReady, divD.io.inReady)
  // io.request.ready 不能组合依赖 io.flush，否则经 CSRUnit.ExceptionTaken 成环。
  io.request.ready := !responseValid && !divActive &&
    Mux(isDivSqrt, selectedDivReady, true.B)
  val divRequestFire = io.request.valid && io.request.ready && isDivSqrt && !io.flush
  divS.io.inValid := divRequestFire && fmtS && !io.flush
  divD.io.inValid := divRequestFire && fmtD && !io.flush

  when(divRequestFire) {
    divActive := true.B
    ActiveDivIsSingle := fmtS
    divAbandoned := false.B
  }

  val divOutValid = Mux(ActiveDivIsSingle,
    divS.io.outValid_div || divS.io.outValid_sqrt,
    divD.io.outValid_div || divD.io.outValid_sqrt)
  val divOutResult = Mux(ActiveDivIsSingle,
    boxF32(fNFromRecFN(expS, sigS, divS.io.out)),
    boxD(fNFromRecFN(expD, sigD, divD.io.out)))
  val divOutFlags = Mux(ActiveDivIsSingle, divS.io.exceptionFlags,
    divD.io.exceptionFlags)
  when(divActive && divOutValid) {
    divActive := false.B
    divAbandoned := false.B
    when(!divAbandoned) {
      PublishResponse(divOutResult, divOutFlags)
    }
  }

  // 非 DIV/SQRT 在接收请求的下一拍给出寄存结果。
  CaptureFastResponse(
    io.request.valid && io.request.ready && !isDivSqrt && !io.flush)
  ReleaseResponse()
  when(io.flush) {
    ClearResponseOnFlush()
    when(divActive) { divAbandoned := true.B }
  }

  // io.response 已由响应流水级驱动，不再直接暴露 ResponseValid。
}
