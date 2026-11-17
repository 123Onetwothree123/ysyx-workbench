package ysyx_26030103.exu
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._
import _root_.ysyx_26030103.config._
import _root_.ysyx_26030103.fpu._

// EXU 内 FPU 唯一在途请求的发射与响应握手。
private[exu] trait EXUFPUControl { this: ysyx_26030103_EXU =>
  def fpuControlBody(): Unit = {
    fpuRequest.valid := io.in.valid && isFPUInstruction && !fpPending &&
      !pendingMDU && !io.MemTrapCommit && !blockForOlder
    fpuRequest.bits.op := inst.FPOp
    fpuRequest.bits.fmt := inst.FPFmt
    fpuRequest.bits.srcFmt := inst.FPSrcFmt
    fpuRequest.bits.rm := inst.FPRm
    fpuRequest.bits.intWord := inst.FPIntWord
    fpuRequest.bits.rs1 := inst.FPRs1
    fpuRequest.bits.rs2 := inst.FPRs2
    fpuRequest.bits.rs3 := inst.FPRs3
    fpuUnitOpt match {
      case Some(fpu) =>
        fpu.io.req.valid := fpuRequest.valid
        fpu.io.req.bits := fpuRequest.bits
        fpuRequest.ready := fpu.io.req.ready
        fpu.io.resp.ready := fpResponseFire
        fpu.io.flush := fpuFlush
      case None =>
        fpuRequest.ready := false.B
    }
    when(fpuRequestFire) {
      fpPending := true.B
      fpInstReg := inst
    }
    when(fpResponseFire) {
      fpPending := false.B
    }
    when(fpuFlush) {
      fpPending := false.B
    }
  }
}
