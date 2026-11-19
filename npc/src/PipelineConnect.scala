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
    idu.io.ExValid := exu.io.HazardValid
    idu.io.ExRd := exu.io.HazardRd
    idu.io.ExRegWrite := exu.io.HazardRegWrite
    idu.io.ExForwardReady := exu.io.ForwardReady
    idu.io.ExForwardData := exu.io.ForwardData
    idu.io.ExMDUHiddenWrites := exu.io.HazardMDUHiddenWrites
    idu.io.WbForwardData := wbu.io.wdata
    idu.io.WbValid := wbu.io.in.valid
    idu.io.WbRd := wbu.io.in.bits.Rd
    idu.io.WbRegWrite := wbu.io.in.bits.RegisterWrite
    idu.io.PipelineMode := true.B
    idu.io.Privilege := exu.io.CSRPrivilege
    idu.io.Mstatus := exu.io.CSRCurrentMstatus
    idu.io.Mcounteren := exu.io.Mcounteren
    idu.io.Scounteren := exu.io.Scounteren

    // 手动连线了
    idu.io.ReadDATA1 := GPR.io.ReadDATA1
    idu.io.ReadDATA2 := GPR.io.ReadDATA2
    GPR.io.Read1SELECT := idu.io.Read1SELECT
    GPR.io.Read2SELECT := idu.io.Read2SELECT
    // 浮点寄存器堆：三个组合读口 + WBU 单写口；另有 DiffTest 专用只读口。
    FPROpt match {
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
        fpr.io.DebugRaddr := io.debug.FPRRaddr
        val DebugFPR = fpr.io.DebugReadData.pad(64)
        io.debug.FPRRdata := DebugFPR(31, 0)
        io.debug.FPRRdataHi := DebugView.highWord(DebugFPR, XLEN)
      case None =>
        idu.io.ReadFPR1 := 0.U
        idu.io.ReadFPR2 := 0.U
        idu.io.ReadFPR3 := 0.U
        io.debug.FPRRdata := 0.U
        io.debug.FPRRdataHi := 0.U
    }
    io.debug.FPValid := config.UseF.B
    idu.io.Frm := exu.io.CSRFrm

    // LSU(MEM级)给IDU做冒险检测和转发
    idu.io.MeValid := LSU.io.HazardValid
    idu.io.MeRd := LSU.io.HazardRd
    idu.io.MeRegWrite := LSU.io.HazardRegWrite
    idu.io.MeMemop := LSU.io.HazardMemOp
    idu.io.MeForwardReady := LSU.io.ForwardReady
    idu.io.MeForwardData := LSU.io.ForwardData
    // LSU等待槽(EX/MEM流水寄存器)也给IDU做冒险检测和转发
    idu.io.Me2Valid := LSU.io.Hazard2Valid
    idu.io.Me2Rd := LSU.io.Hazard2Rd
    idu.io.Me2RegWrite := LSU.io.Hazard2RegWrite
    idu.io.Me2Memop := LSU.io.Hazard2MemOp
    idu.io.Me2ForwardReady := LSU.io.Hazard2ForwardReady
    idu.io.Me2ForwardData := LSU.io.Hazard2ForwardData
    // 浮点转发/冒险端口。
    idu.io.ExFPValid := exu.io.HazardFPValid
    idu.io.ExFPRd := exu.io.HazardFPRd
    idu.io.ExFPRegWrite := exu.io.HazardFPRegWrite
    idu.io.ExFPForwardReady := exu.io.ForwardFPReady
    idu.io.ExFPForwardData := exu.io.ForwardFPData
    idu.io.MeFPValid := LSU.io.HazardValid
    idu.io.MeFPRd := LSU.io.HazardFPRd
    idu.io.MeFPRegWrite := LSU.io.HazardFPRegWrite
    idu.io.MeFPForwardReady := LSU.io.ForwardFPReady
    idu.io.MeFPForwardData := LSU.io.ForwardFPData
    idu.io.Me2FPValid := LSU.io.Hazard2Valid
    idu.io.Me2FPRd := LSU.io.Hazard2FPRd
    idu.io.Me2FPRegWrite := LSU.io.Hazard2FPRegWrite
    idu.io.Me2FPForwardReady := LSU.io.Hazard2ForwardFPReady
    idu.io.Me2FPForwardData := LSU.io.Hazard2ForwardFPData
    idu.io.WbFPValid := wbu.io.in.valid
    idu.io.WbFPRd := wbu.io.in.bits.Rd
    idu.io.WbFPRegWrite := wbu.io.in.bits.FPRegisterWrite
    idu.io.WbFPForwardData := wbu.io.FPWriteData

    GPR.io.WriteSELECT := wbu.io.WriteSELECT
    GPR.io.WriteEN := wbu.io.WriteEN
    GPR.io.wdata := wbu.io.wdata
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
    exu.io.Interrupt := io.interrupt || PLIC.ExternalIRQ
    exu.io.TimerInterrupt := CLINT.io.TimerIRQ
    exu.io.Mtime := CLINT.io.mtime

    idu.io.ExMemop := exu.io.HazardMemOp
  }
}
