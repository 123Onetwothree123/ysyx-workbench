package ysyx_26030103.exu
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._
import _root_.ysyx_26030103.config._
import _root_.ysyx_26030103.fpu._

// trap/CSR/重定向状态导出给顶层与 DiffTest。
private[exu] trait EXUStatus { this: ysyx_26030103_EXU =>
  def statusBody(): Unit = {
    io.SimHaltValid := instructionCommit && inst.IsSimHalt
    io.SimHaltPC := inst.PC
    io.TrapCommit := CSRUnit.io.TrapCommit
    io.TrapPC := TrapBoundaryPC
    io.TrapTarget := CSRUnit.io.ExceptionTarget
    io.TrapCause := CSRUnit.io.CommittedCause
    io.CSRPrivilege := CSRUnit.io.Privilege
    io.CSRSatp := CSRUnit.io.Satp
    io.CSRCurrentMstatus := CSRUnit.io.CurrentMstatus
    io.CSRFlushTLB := CSRUnit.io.FlushTLB
    io.CSRStateMstatus := CSRUnit.io.StateMstatus
    io.CSRStateMtvec := CSRUnit.io.StateMtvec
    io.CSRStateMepc := CSRUnit.io.StateMepc
    io.CSRStateMcause := CSRUnit.io.StateMcause
    io.CSRStateTval := CSRUnit.io.StateTval
    io.PMPConfig := CSRUnit.io.PMPConfig
    io.PMPAddress := CSRUnit.io.PMPAddress
    CSRUnit.io.Mtime := io.Mtime
    io.Mcounteren := CSRUnit.io.McounterenOut
    io.Scounteren := CSRUnit.io.ScounterenOut
    io.FetchAccessFaultCommit := CSRUnit.io.TrapCommit && !io.MemTrapCommit &&
      !CSRUnit.io.IRQCommit && upstreamException && inst.ExceptionCause === 1.U
    io.FetchAccessFaultPC := inst.PC
    io.FetchAccessFaultResponse := inst.AccessFaultResponse
    io.Redirect := instructionCommit && redirect
    // 重定向目标必须与实际提交的下一 PC 一致：MRET 实际目标是 mepc（CommitNextPC 分支），
    // 用 ActualNextPC 会给顺序 snpc；功能上由 ExceptionTaken 覆盖，此处只修正波形/perf 语义。
    io.RedirectTarget := CommitNextPC
    // ecall/mret/上游异常提交,或MEM经CSR后门提交的访存故障
    io.ExceptionTaken := CSRUnit.io.ExceptionTaken
    io.ExceptionTarget := CSRUnit.io.ExceptionTarget
  }
}
