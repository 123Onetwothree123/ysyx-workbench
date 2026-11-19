package ysyx_26030103.exu
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._
import _root_.ysyx_26030103.config._
import _root_.ysyx_26030103.fpu._

// 性能计数事件打包（电平信号 + 时钟沿前事件类型）。
private[exu] trait EXUPerf { this: ysyx_26030103_EXU =>
  def perfBody(): Unit = {
    // 性能事件描述的是当前正发往下一级的指令，而不是排出待处理MDU结果时
    // 恰好呈现的任何更年轻输入。
    val activeCommit = Mux(
      PendingMDU,
      MDUResponseFire && !io.MemTrapCommit && !CSRUnit.io.IRQCommit,
      io.out.fire && !activeInstructionTrapValid && !io.MemTrapCommit &&
        !CSRUnit.io.IRQCommit
    )

    val perfALUEligible = activeCommit &&
      !activeInst.MemoryValid && !ActiveIsMDU && !activeInst.IsCSRrw &&
      !activeInst.IsCSRrs && !activeInst.IsCSRrc && !activeInst.IsBranch && !activeInst.IsJal &&
      !activeInst.IsJalr && !activeInst.IsEcall && !activeInst.IsEbreak &&
      !activeInst.IsMret && !activeInst.IsSimHalt && !activeInst.IsFence &&
      !activeInst.IsFenceI && !activeInst.ExceptionValid
    val perfEventKind = Mux(
      !activeCommit,
      0.U,
      Mux(
        ActiveIsMDU,
        7.U,
        Mux(
          activeInst.MemoryValid,
          2.U,
          Mux(
            activeInst.IsCSRrw || activeInst.IsCSRrs || activeInst.IsCSRrc,
            3.U,
            Mux(
              activeInst.IsJal,
              5.U,
              Mux(
                activeInst.IsJalr,
                6.U,
                Mux(activeInst.IsBranch, 4.U, Mux(perfALUEligible, 1.U, 0.U))
              )
            )
          )
        )
      )
    )
    // 类型电平用于活跃周期统计：PendingMDU 时 io.in.bits 可能已是被反压的年轻指令，
    // 必须和输出数据一样用 activeInst，否则会把 MDU 等待周期错记到年轻 ALU/MEM/CSR/branch。
    val ActiveALUClass =
      !activeInst.MemoryValid && !ActiveIsMDU && !activeInst.IsCSRrw &&
        !activeInst.IsCSRrs && !activeInst.IsCSRrc && !activeInst.IsBranch && !activeInst.IsJal &&
        !activeInst.IsJalr && !activeInst.IsEcall && !activeInst.IsEbreak &&
        !activeInst.IsMret && !activeInst.IsSimHalt && !activeInst.IsFence &&
        !activeInst.IsFenceI && !activeInstructionTrapValid
    io.PerfALUOp := ActiveALUClass
    io.PerfMemOp := activeInst.MemoryValid && !activeInstructionTrapValid
    io.PerfCSROp := activeInst.IsCSRrw || activeInst.IsCSRrs || activeInst.IsCSRrc
    io.PerfBranchOp := activeInst.IsBranch || activeInst.IsJal || activeInst.IsJalr
    io.PerfJalOp := activeInst.IsJal
    io.PerfJalrOp := activeInst.IsJalr
    io.PerfBranchMispredict := instructionCommit && mispredict
    io.PerfBranchTaken := instructionCommit && branchTaken
    io.PerfFPURequest := FPURequestFire
    io.PerfFPUDone := FPResponseFire
    io.PerfLoadOp := activeInst.MemoryValid && !activeInst.MemoryWrite &&
      !activeInst.IsAtomic && !activeInstructionTrapValid
    io.PerfStoreOp := activeInst.MemoryValid && activeInst.MemoryWrite &&
      !activeInst.IsAtomic && !activeInstructionTrapValid
    io.PerfAmoOp := activeInst.IsAtomic && !activeInstructionTrapValid
    io.PerfBranchNotTaken := instructionCommit && inst.IsBranch && !branchTaken
    io.PerfFPUActive := FPPending
    io.PerfFenceOp := activeInst.IsFence || activeInst.IsFenceI
    io.PerfRvcInst := activeInst.IsCompressed
    io.PerfSystemOp := activeInst.IsEcall || activeInst.IsEbreak ||
      activeInst.IsMret || activeInst.IsSret || activeInst.IsSfenceVma
    io.PerfInterruptTaken := CSRUnit.io.IRQCommit
    io.PerfCSRRead := activeInst.IsCSRrw || activeInst.IsCSRrs ||
      activeInst.IsCSRrc
    io.PerfCSRWrite := activeInst.IsCSRrw ||
      ((activeInst.IsCSRrs || activeInst.IsCSRrc) && activeInst.Rs1 =/= 0.U)
    io.PerfRedirectBranch := instructionCommit && mispredict
    io.PerfRedirectFenceI := instructionCommit &&
      (inst.IsFenceI || (if (hasS) inst.IsSfenceVma else false.B))
    io.PerfRedirectTrap := CSRUnit.io.ExceptionTaken
    io.PerfRedirectMret := instructionCommit &&
      (inst.IsMret || (if (hasS) inst.IsSret else false.B))
    io.PerfFPUStall := FPPending && !FPResponseValid
    io.PerfIllegalInst :=
      CSRUnit.io.TrapCommit && CSRUnit.io.CommittedCause === 2.U
    io.PerfFPUDivSqrt := FPPending &&
      (FPInstReg.FPOp === ysyx_26030103_FPUOp.FDiv ||
        FPInstReg.FPOp === ysyx_26030103_FPUOp.FSqrt)
    io.PerfFPUFma := FPPending &&
      (FPInstReg.FPOp === ysyx_26030103_FPUOp.FFma ||
        FPInstReg.FPOp === ysyx_26030103_FPUOp.FFms ||
        FPInstReg.FPOp === ysyx_26030103_FPUOp.FFnma ||
        FPInstReg.FPOp === ysyx_26030103_FPUOp.FFnms)
    io.PerfFPUException := FPResponseFire && FPResponseBits.fflags =/= 0.U
    io.PerfCSRIllegal :=
      CSRUnit.io.TrapCommit && CSRUnit.io.CommittedCause === 2.U &&
        (activeInst.IsCSRrw || activeInst.IsCSRrs || activeInst.IsCSRrc)
    io.PerfIRQPending := CSRUnit.io.IRQPending
    io.PerfDivIterations := MDUPerfDivIterations
    io.PerfDivEarlyOut := MDUPerfDivEarlyOut
    io.PerfEventKind := perfEventKind
    io.PerfMDURequest := MDURequestFire
    io.PerfMDUDone := activeCommit && ActiveIsMDU && !activeInst.ExceptionValid
    io.PerfMDUOp := ActiveMDUOp
    // MDUActive 含首次发射拍；MDUWait 只统计等 MDU 本身的周期。
    // 结果已 valid 却因 EX/MEM/LSU 反压未接收，应归到下游 stall，不算 MDU 计算延迟。
    val WaitingForFirstMDUResult =
      !PendingMDU && io.in.valid && IsMDUInstruction && !MDUInputBlockedByIRQ
    val WaitingForPendingMDUResult = PendingMDU && !MDUResponseValid
    val MDUActiveLevel = PendingMDU || MDURequestFire
    val MDUIsMul = ActiveMDUOp < 4.U || ActiveMDUOp === 8.U
    io.PerfDivDone := activeCommit && ActiveIsMDU && !MDUIsMul
    io.PerfMDUActive := MDUActiveLevel
    io.PerfMulActive := MDUActiveLevel && MDUIsMul
    io.PerfDivActive := MDUActiveLevel && !MDUIsMul
    io.PerfMDUWait := !io.out.fire &&
      (WaitingForFirstMDUResult || WaitingForPendingMDUResult)
    io.PerfExecutionActive := io.in.valid || PendingMDU
  }
}
