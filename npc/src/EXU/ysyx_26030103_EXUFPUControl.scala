package ysyx_26030103.exu
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._
import _root_.ysyx_26030103.config._
import _root_.ysyx_26030103.fpu._

// EXU 内 FPU 唯一在途请求的发射与响应握手。
private[exu] trait EXUFPUControl { this: ysyx_26030103_EXU =>
  def fpuControlBody(): Unit = {
    FPURequest.valid := io.in.valid && IsFPUInstruction && !FPPending &&
      !PendingMDU && !io.MemTrapCommit && !blockForOlder
    FPURequest.bits.op := inst.FPOp
    FPURequest.bits.fmt := inst.FPFmt
    FPURequest.bits.SourceFmt := inst.FPSrcFmt
    FPURequest.bits.rm := inst.FPRm
    FPURequest.bits.intWord := inst.FPIntWord
    FPURequest.bits.rs1 := inst.FPRs1
    FPURequest.bits.rs2 := inst.FPRs2
    FPURequest.bits.rs3 := inst.FPRs3
    FPUUnitOpt match {
      case Some(fpu) =>
        fpu.io.request.valid := FPURequest.valid
        fpu.io.request.bits := FPURequest.bits
        FPURequest.ready := fpu.io.request.ready
        fpu.io.response.ready := FPResponseFire
        fpu.io.flush := FPUFlush
      case None =>
        FPURequest.ready := false.B
    }
    when(FPURequestFire) {
      FPPending := true.B
      FPInstReg := inst
    }
    when(FPResponseFire) {
      FPPending := false.B
    }
    when(FPUFlush) {
      FPPending := false.B
    }
  }
}
