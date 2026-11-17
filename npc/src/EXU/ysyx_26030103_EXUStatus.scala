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
    io.SimHaltPC := inst.pc
    io.TrapCommit := csrUnit.io.TrapCommit
    io.TrapPC := trapBoundaryPC
    io.TrapTarget := csrUnit.io.ExceptionTarget
    io.TrapCause := csrUnit.io.CommittedCause
    io.CSRPrivilege := csrUnit.io.Privilege
    io.CSRSatp := csrUnit.io.Satp
    io.CSRCurrentMstatus := csrUnit.io.CurrentMstatus
    io.CSRFlushTLB := csrUnit.io.FlushTLB
    io.CSRStateMstatus := csrUnit.io.StateMstatus
    io.CSRStateMtvec := csrUnit.io.StateMtvec
    io.CSRStateMepc := csrUnit.io.StateMepc
    io.CSRStateMcause := csrUnit.io.StateMcause
    io.CSRStateTval := csrUnit.io.StateTval
    io.PMPConfig := csrUnit.io.PMPConfig
    io.PMPAddress := csrUnit.io.PMPAddress
    csrUnit.io.Mtime := io.Mtime
    io.Mcounteren := csrUnit.io.McounterenOut
    io.Scounteren := csrUnit.io.ScounterenOut
    io.FetchAccessFaultCommit := csrUnit.io.TrapCommit && !io.MemTrapCommit &&
      !csrUnit.io.IRQCommit && upstreamException && inst.ExceptionCause === 1.U
    io.FetchAccessFaultPC := inst.pc
    io.FetchAccessFaultResponse := inst.AccessFaultResponse
    io.Redirect := instructionCommit && redirect
    // 重定向目标必须与实际提交的下一 PC 一致：MRET 实际目标是 mepc（commitNextPC 分支），
    // 用 actualNextPC 会给顺序 snpc；功能上由 ExceptionTaken 覆盖，此处只修正波形/perf 语义。
    io.RedirectTarget := commitNextPC
    // ecall/mret/上游异常提交,或MEM经CSR后门提交的访存故障
    io.ExceptionTaken := csrUnit.io.ExceptionTaken
    io.ExceptionTarget := csrUnit.io.ExceptionTarget
  }
}
