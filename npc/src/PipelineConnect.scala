package ysyx_26030103
import chisel3._
import chisel3.util._

import _root_.ysyx_26030103.common._
import _root_.ysyx_26030103.config._
import _root_.ysyx_26030103.cache._
import _root_.ysyx_26030103.infra._
import _root_.ysyx_26030103.ifu._
import _root_.ysyx_26030103.idu._
import _root_.ysyx_26030103.exu._
import _root_.ysyx_26030103.mem._
import _root_.ysyx_26030103.wbu._
import _root_.ysyx_26030103.mmu._

private[ysyx_26030103] trait PipelineConnect { this: ysyx_26030103 =>
  def connectPipeline(): Unit = {
    idu.io.ex_valid := exu.io.HazardValid
    idu.io.ex_rd := exu.io.HazardRd
    idu.io.ex_regWrite := exu.io.HazardRegWrite
    idu.io.ex_fwd_ready := exu.io.FwdReady
    idu.io.ex_fwd_data := exu.io.FwdData
    idu.io.ex_mdu_hidden_writes := exu.io.HazardMDUHiddenWrites
    idu.io.wb_fwd_data := wbu.io.wdata
    idu.io.wb_valid := wbu.io.in.valid
    idu.io.wb_rd := wbu.io.in.bits.Rd
    idu.io.wb_regWrite := wbu.io.in.bits.RegisterWrite
    idu.io.pipeline_mode := true.B
    idu.io.Privilege := exu.io.CSRPrivilege
    idu.io.Mstatus := exu.io.CSRCurrentMstatus
    idu.io.Mcounteren := exu.io.Mcounteren
    idu.io.Scounteren := exu.io.Scounteren

    // 手动连线了
    idu.io.ReadDATA1 := gpr.io.ReadDATA1
    idu.io.ReadDATA2 := gpr.io.ReadDATA2
    gpr.io.Read1SELECT := idu.io.Read1SELECT
    gpr.io.Read2SELECT := idu.io.Read2SELECT
    // 浮点寄存器堆：三个组合读口 + WBU 单写口；另有 DiffTest 专用只读口。
    fprOpt match {
      case Some(fpr) =>
        fpr.io.raddr1 := idu.io.Read1FSELECT
        fpr.io.raddr2 := idu.io.Read2FSELECT
        fpr.io.raddr3 := idu.io.Read3FSELECT
        idu.io.ReadFPR1 := fpr.io.rdata1.pad(64)
        idu.io.ReadFPR2 := fpr.io.rdata2.pad(64)
        idu.io.ReadFPR3 := fpr.io.rdata3.pad(64)
        fpr.io.wen := wbu.io.FPWriteEN
        fpr.io.waddr := wbu.io.FPWriteSELECT
        fpr.io.wdata := wbu.io.FPWriteData
        // 调试口固定按 FLEN 读物理寄存器。FLEN=32 时再零扩展到 64 位，
        // 与 NEMU ABI 的固定 64 位槽位对齐；高 32 位不会带出 NaN-box。
        fpr.io.DebugRaddr := io.debug.fpr_raddr
        val debugFPR = fpr.io.DebugRdata.pad(64)
        io.debug.fpr_rdata := debugFPR(31, 0)
        io.debug.fpr_rdata_hi := DebugView.highWord(debugFPR, XLEN)
      case None =>
        idu.io.ReadFPR1 := 0.U
        idu.io.ReadFPR2 := 0.U
        idu.io.ReadFPR3 := 0.U
        io.debug.fpr_rdata := 0.U
        io.debug.fpr_rdata_hi := 0.U
    }
    io.debug.fp_valid := config.UseF.B
    idu.io.Frm := exu.io.CSRFrm

    // LSU(MEM级)给IDU做冒险检测和转发
    idu.io.me_valid := lsu.io.HazardValid
    idu.io.me_rd := lsu.io.HazardRd
    idu.io.me_regWrite := lsu.io.HazardRegWrite
    idu.io.me_memop := lsu.io.HazardMemOp
    idu.io.me_fwd_ready := lsu.io.FwdReady
    idu.io.me_fwd_data := lsu.io.FwdData
    // LSU等待槽(EX/MEM流水寄存器)也给IDU做冒险检测和转发
    idu.io.me2_valid := lsu.io.Hazard2Valid
    idu.io.me2_rd := lsu.io.Hazard2Rd
    idu.io.me2_regWrite := lsu.io.Hazard2RegWrite
    idu.io.me2_memop := lsu.io.Hazard2MemOp
    idu.io.me2_fwd_ready := lsu.io.Hazard2FwdReady
    idu.io.me2_fwd_data := lsu.io.Hazard2FwdData
    // 浮点转发/冒险端口。
    idu.io.ex_fp_valid := exu.io.HazardFPValid
    idu.io.ex_fp_rd := exu.io.HazardFPRd
    idu.io.ex_fp_regWrite := exu.io.HazardFPRegWrite
    idu.io.ex_fp_fwd_ready := exu.io.FwdFPReady
    idu.io.ex_fp_fwd_data := exu.io.FwdFPData
    idu.io.me_fp_valid := lsu.io.HazardValid
    idu.io.me_fp_rd := lsu.io.HazardFPRd
    idu.io.me_fp_regWrite := lsu.io.HazardFPRegWrite
    idu.io.me_fp_fwd_ready := lsu.io.FwdFPReady
    idu.io.me_fp_fwd_data := lsu.io.FwdFPData
    idu.io.me2_fp_valid := lsu.io.Hazard2Valid
    idu.io.me2_fp_rd := lsu.io.Hazard2FPRd
    idu.io.me2_fp_regWrite := lsu.io.Hazard2FPRegWrite
    idu.io.me2_fp_fwd_ready := lsu.io.Hazard2FwdFPReady
    idu.io.me2_fp_fwd_data := lsu.io.Hazard2FwdFPData
    idu.io.wb_fp_valid := wbu.io.in.valid
    idu.io.wb_fp_rd := wbu.io.in.bits.Rd
    idu.io.wb_fp_regWrite := wbu.io.in.bits.FPRegisterWrite
    idu.io.wb_fp_fwd_data := wbu.io.FPWriteData

    gpr.io.WriteSELECT := wbu.io.WriteSELECT
    gpr.io.WriteEN := wbu.io.WriteEN
    gpr.io.wdata := wbu.io.wdata
    // FP 指令在 WBU 真正退休时才置 FS=Dirty（故障 load/store 不置）。
    exu.io.FPStateDirtyCommit := wbu.io.FPCommit
    // minstret 按 WBU 真实退休计数：load/store 只有在 MEM 完成后才退休，
    // 故障/被中断取消的指令 Retire=0，不计入。
    exu.io.RetireCommit := wbu.io.in.fire && wbu.io.in.bits.Retire
    // minstret 自增过滤掉写 minstret/minstreth 的指令自身；该指令仍然退休，
    // PMU Instructions 事件继续使用上面的完整 RetireCommit。
    exu.io.MinstretRetireCommit := wbu.io.in.fire && wbu.io.in.bits.Retire &&
      !wbu.io.in.bits.WritesMinstret
    // 临时新加的处理中断的
    exu.io.Interrupt := io.interrupt || plic.external_irq
    exu.io.TimerInterrupt := clint.io.timer_irq
    exu.io.Mtime := clint.io.mtime

    idu.io.ex_memop := exu.io.HazardMemOp
  }
}
