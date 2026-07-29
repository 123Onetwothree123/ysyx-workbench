package ysyx_26030103
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.ysyx_26030103_Message._
import _root_.ysyx_26030103.ysyx_26030103_CSR._
import _root_.ysyx_26030103.ysyx_26030103_ALU._
class ysyx_26030103_EXU extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ysyx_26030103_IDUMessage))
    val out = Decoupled(new ysyx_26030103_EXUMessage)
    val Redirect = Output(Bool())
    val RedirectTarget = Output(UInt(32.W))
    val ExceptionTaken = Output(Bool())
    val ExceptionTarget = Output(UInt(32.W))
    val Interrupt = Input(Bool())
    // MEMU的反馈: 非空=有指令在访存/完成中; 访存故障提交信号(CSR后门)
    val MEMBusy = Input(Bool())
    val MemTrapCommit = Input(Bool())
    val MemTrapCause = Input(UInt(32.W))
    val MemTrapPC = Input(UInt(32.W))

    val TrapValid = Output(Bool())
    val TrapPC = Output(UInt(32.W))
    val PerfALUOp = Output(Bool())
    val PerfMemOp = Output(Bool())
    val PerfCSROp = Output(Bool())
    val PerfBranchOp = Output(Bool())
    val PerfJalOp = Output(Bool())
    val PerfJalrOp = Output(Bool())
    val PerfExecutionActive = Output(Bool())

    val FenceIFlush = Output(Bool())

    // 统一冲刷输出: 分支/跳转/fence.i重定向或异常(ecall/mret/中断/各类异常)提交时,
    // 冲刷IFU内部状态和IFU→IDU流水寄存器里的年轻指令
    // (MEM故障的冲刷由MEMU发起,通过CSR后门使ExceptionTaken覆盖该路径)
    val FlushIF = Output(Bool())

    // 给IDU做数据冒险检测用(EXU单拍级,指令就是in.bits本身)
    val HazardValid = Output(Bool())
    val HazardRd = Output(UInt(5.W))
    val HazardRegWrite = Output(Bool())
    val HazardMemOp = Output(Bool())
    val PerfIdleNoInput = Output(Bool())
    val PerfTrap = Output(Bool())
    // 转发给IDU: EX阶段生产者的最终写回值,以及该值当前是否可用于转发
    val FwdData = Output(UInt(32.W))
    val FwdReady = Output(Bool())
    // BTB更新: 分支提交时把真实target写回BTB(为后文jal预留,jal只需复用此接口)
    val BTBUpdateValid  = Output(Bool())
    val BTBUpdatePC     = Output(UInt(32.W))
    val BTBUpdateTarget = Output(UInt(32.W))
  })
  val ALUUnit = Module(new ysyx_26030103_ALU)
  val CSRUnit = Module(new ysyx_26030103_CSR)
  CSRUnit.io.clk := clock
  CSRUnit.io.rst := reset.asBool
  CSRUnit.io.Interrupt := io.Interrupt
  val BranchComparatorUnit = Module(new ysyx_26030103_BranchComparator)
  val inst = io.in.bits
  // 上游随指令传来的异常标记(IFU取指错cause=1/IDU非法指令cause=2)
  // 带标记的指令只做异常提交,不得产生任何副作用(访存/CSR写/GPR写/重定向)
  val UpEx = inst.ExceptionValid
  // 带副作用的指令(csr/ecall/ebreak/mret/异常)必须等MEM级排空(在序精确异常):
  // 比它年老的访存可能还没完成,甚至可能是故障要提交异常
  val IsSideEffect = inst.IsCsrrw || inst.IsCsrrs || inst.IsEcall || inst.IsEbreak ||
    inst.IsMret || inst.ExceptionValid
  val BlockForMEM = io.MEMBusy && IsSideEffect
  io.in.ready := io.out.ready && !BlockForMEM
  io.out.valid := io.in.valid && !BlockForMEM
  ALUUnit.io.A := inst.ALU_A
  ALUUnit.io.B := inst.ALU_B
  ALUUnit.io.ALUCtrl := inst.ALUCtrl
  CSRUnit.io.IsCsrrw := inst.IsCsrrw && !UpEx
  CSRUnit.io.IsCsrrs := inst.IsCsrrs && !UpEx
  CSRUnit.io.IsEcall := inst.IsEcall && !UpEx
  CSRUnit.io.IsEbreak := inst.IsEbreak && !UpEx
  CSRUnit.io.IsMret := inst.IsMret && !UpEx
  CSRUnit.io.CSRAddress := inst.CSRAddress
  CSRUnit.io.rs1 := inst.Rs1
  CSRUnit.io.Rs1Data := inst.Rs1Data
  CSRUnit.io.pc := Mux(io.MemTrapCommit, io.MemTrapPC, inst.pc)
  BranchComparatorUnit.io.A := inst.BranchA
  BranchComparatorUnit.io.B := inst.BranchB
  BranchComparatorUnit.io.Funct3 := inst.BranchFunct3
  BranchComparatorUnit.io.IsBranch := inst.IsBranch
  val BranchTarget = inst.pc + inst.Immediate
  val JalTarget = ALUUnit.io.result
  // 和logisim一样，最低位换成常量0
  val JalrTarget = Cat(ALUUnit.io.result(31, 1), 0.U(1.W))
  // fence.i也要产生"重定向": 目标是自己的snpc(即fence.i+4),
  // 借此把流水线里比fence.i年轻的、可能过时的指令全部冲刷并重新取指
  // 实际下一PC: jalr最低位清零由JalrTarget给出;分支not-taken与fence.i都回snpc
  val ActualNextPC = Mux(inst.IsJal, JalTarget,
    Mux(inst.IsJalr, JalrTarget,
      Mux(inst.IsBranch && BranchComparatorUnit.io.Taken, BranchTarget, inst.snpc)))
  // 预测下一PC: IFU用BTB+BTFN给出,随指令传到此;未命中=顺序=snpc
  val PredNextPC = Mux(inst.pred_taken, inst.pred_target, inst.snpc)
  // 预测错误检查: 比较实际与预测的下一PC,不一致则冲刷并重定向到实际目标
  // (jal/jalr: BTB未存表项时pred_taken=false,实际跳转=>必然判错重定向,与改造前行为一致)
  val Mispredict = ActualNextPC =/= PredNextPC
  val Redirect = (Mispredict || inst.IsFenceI) && !UpEx
  // CSR提交(csr写/ecall/ebreak/mret/异常/中断): 只在非访存指令fire时提交,
  // 访存指令一拍流过EXU前往MEMU,不允许中断提交和它们绑定(避免被压掉的load/store留下副作用)
  CSRUnit.io.Enable := (io.in.fire && !inst.MemoryValid) || io.MemTrapCommit
  CSRUnit.io.TrapValid := UpEx || io.MemTrapCommit
  CSRUnit.io.TrapCause := Mux(io.MemTrapCommit, io.MemTrapCause, Cat(0.U(28.W), inst.ExceptionCause))

  io.TrapValid := io.in.fire && CSRUnit.io.IsEbreak
  io.TrapPC := inst.pc
  io.Redirect := io.in.fire && Redirect
  io.RedirectTarget := ActualNextPC
  // ecall/mret/上游异常提交,或MEMU经CSR后门提交的访存故障
  io.ExceptionTaken := CSRUnit.io.ExceptionTaken
  io.ExceptionTarget := CSRUnit.io.ExceptionTarget

  io.out.bits.pc := inst.pc
  io.out.bits.snpc := inst.snpc
  io.out.bits.Rd := inst.Rd
  // 带异常标记的指令不得写回GPR;被中断压掉的指令(IrqCommit)也不得写回
  io.out.bits.RegisterWrite := inst.RegisterWrite && !UpEx && !CSRUnit.io.IrqCommit
  io.out.bits.WBSelect := inst.WBSelect
  io.out.bits.ALUResult := ALUUnit.io.result
  io.out.bits.LoadData := 0.U(32.W) // 由MEMU在访存完成后填写
  io.out.bits.CSRReadData := CSRUnit.io.CSR_rdata
  io.out.bits.MemoryValid := inst.MemoryValid && !UpEx
  io.out.bits.MemoryWrite := inst.MemoryWrite
  io.out.bits.WidthSelect := inst.WidthSelect
  io.out.bits.LoadSigned := inst.LoadSigned
  io.out.bits.StoreData := inst.StoreData
  io.out.bits.ExceptionValid := inst.ExceptionValid
  io.out.bits.ExceptionCause := inst.ExceptionCause

  io.PerfALUOp := !inst.MemoryValid && !inst.IsCsrrw && !inst.IsCsrrs &&
    !inst.IsBranch && !inst.IsJal && !inst.IsJalr
  io.PerfMemOp := inst.MemoryValid
  io.PerfCSROp := inst.IsCsrrw || inst.IsCsrrs
  io.PerfBranchOp := inst.IsBranch || inst.IsJal || inst.IsJalr
  io.PerfJalOp := inst.IsJal
  io.PerfJalrOp := inst.IsJalr
  io.PerfExecutionActive := io.in.valid

  io.FenceIFlush := io.in.fire && inst.IsFenceI && !UpEx
  io.FlushIF := io.Redirect || io.ExceptionTaken

  io.HazardValid := io.in.valid
  io.HazardRd := inst.Rd
  // 带异常标记的指令不会真正写回,不应让IDU白白等它;被中断压掉的指令同理
  io.HazardRegWrite := inst.RegisterWrite && !UpEx && !CSRUnit.io.IrqCommit
  io.HazardMemOp := inst.MemoryValid
  io.PerfIdleNoInput := !io.in.valid
  io.PerfTrap := io.ExceptionTaken
  // 转发给IDU的最终写回值(与WBU写GPR的值一致): ALU结果/snpc/CSR读出
  // (load数据在EXU阶段不可知,由MEMU提供它那一级的转发)
  io.FwdData := Mux(inst.WBSelect === 2.U, inst.snpc,
    Mux(inst.WBSelect === 3.U, CSRUnit.io.CSR_rdata, ALUUnit.io.result))
  // 可转发条件: 会写rd,且不是load(load要等MEMU完成)
  io.FwdReady := io.HazardValid && io.HazardRegWrite && !inst.MemoryValid

  // BTB更新: 所有分支指令提交时都写回PC→target(不管是否taken),
  // 供IFU查BTB命中后用BTFN(target<PC=后向则taken)做方向预测.
  // 扩展点(暂不实现): jal复用此端口(target=JalTarget); jalr/ret需RAS,不能简单复用
  io.BTBUpdateValid  := io.in.fire && inst.IsBranch && !UpEx
  io.BTBUpdatePC     := inst.pc
  io.BTBUpdateTarget := BranchTarget
}
