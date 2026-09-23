package ysyx_26030103.exu
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._
class ysyx_26030103_EXU(
    val config: ysyx_26030103_NPCConfig = ysyx_26030103_NPCConfig()
) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ysyx_26030103_IDUMessage))
    val out = Decoupled(new ysyx_26030103_EXUMessage)
    val Redirect = Output(Bool())
    val RedirectTarget = Output(UInt(32.W))
    val ExceptionTaken = Output(Bool())
    val ExceptionTarget = Output(UInt(32.W))
    val Interrupt = Input(Bool())
    // MEM的反馈: 非空=有指令在访存/完成中; 访存故障提交信号(CSR后门)
    val MEMBusy = Input(Bool())
    val MemTrapCommit = Input(Bool())
    val MemTrapCause = Input(UInt(32.W))
    val MemTrapPC = Input(UInt(32.W))

    // custom-0 仿真 halt 的提交事件；架构 EBREAK 不走这条通路。
    val SimHaltValid = Output(Bool())
    val SimHaltPC = Output(UInt(32.W))
    // 精确架构 trap 提交事件（不包含 MRET），用于 DiffTest
    // 和调试端在正确指令边界观察 PC 改变。
    val TrapCommit = Output(Bool())
    val TrapPC = Output(UInt(32.W))
    val TrapTarget = Output(UInt(32.W))
    val TrapCause = Output(UInt(32.W))
    // 取指 access fault 只在对应指令真正提交异常时上报。
    val FetchAccessFaultCommit = Output(Bool())
    val FetchAccessFaultPC = Output(UInt(32.W))
    val FetchAccessFaultResp = Output(UInt(2.W))
    val PerfALUOp = Output(Bool())
    val PerfMemOp = Output(Bool())
    val PerfCSROp = Output(Bool())
    val PerfBranchOp = Output(Bool())
    val PerfJalOp = Output(Bool())
    val PerfJalrOp = Output(Bool())
    val PerfMDUReq = Output(Bool())
    val PerfMDUDone = Output(Bool())
    val PerfMDUOp = Output(UInt(ysyx_26030103_MDUOp.Width.W))
    val PerfMDUActive = Output(Bool())
    val PerfMDUWait = Output(Bool())
    val PerfExecutionActive = Output(Bool())

    val FenceIFlush = Output(Bool())

    // 统一冲刷输出: 分支/跳转/fence.i重定向或异常(ecall/mret/中断/各类异常)提交时,
    // 冲刷IFU内部状态和IFU→IDU流水寄存器里的年轻指令
    // (MEM故障的冲刷由MEM发起,通过CSR后门使ExceptionTaken覆盖该路径)
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
    // BTB更新: 分支提交时把真实target写回分支BTB
    val BTBUpdateValid = Output(Bool())
    val BTBUpdatePC = Output(UInt(32.W))
    val BTBUpdateTarget = Output(UInt(32.W))
    // jal BTB更新: jal/ret提交时把真实target写回独立jal BTB(方案B), 带2位类型
    val JalBTBUpdateValid = Output(Bool())
    val JalBTBUpdatePC = Output(UInt(32.W))
    val JalBTBUpdateTarget = Output(UInt(32.W))
    val JalBTBUpdateKind =
      Output(UInt(2.W)) // ysyx_26030103_BTBKind: Jal/Call/Ret
    // RAS更新: call提交压栈(返回地址=snpc), ret提交弹栈
    val RASPushValid = Output(Bool())
    val RASPushAddr = Output(UInt(32.W))
    val RASPopValid = Output(Bool())
  })
  val ALUUnit = Module(new ysyx_26030103_ALU)
  val MDUUnit = Module(new ysyx_26030103_MDU(config))
  val CSRUnit = Module(new ysyx_26030103_CSR)
  CSRUnit.io.clk := clock
  CSRUnit.io.rst := reset.asBool
  CSRUnit.io.Interrupt := io.Interrupt
  val BranchComparatorUnit = Module(new ysyx_26030103_BranchComparator)
  val inst = io.in.bits
  // MDU 只有一个在途事务。M 指令先由 EXU 内部接收 Req，期间保持
  // IDU->EXU 的输入不 fire；Resp 与 EXU 输出同拍握手时才消费该输入。
  val PendingMDU = RegInit(false.B)
  val PendingMDUInst = Reg(chiselTypeOf(io.in.bits))
  // 上游随指令传来的异常标记(IFU取指错cause=1/IDU非法指令cause=2)
  val UpEx = inst.ExceptionValid

  // IALIGN=32: 只有实际发生转移的 branch/JAL/JALR 才检查目标低两位。
  // JALR 先按 ISA 清 bit0，若 bit1 仍为 1 则产生 instruction-address-misaligned(cause=0)。
  ALUUnit.io.A := inst.ALU_A
  ALUUnit.io.B := inst.ALU_B
  ALUUnit.io.ALUCtrl := inst.ALUCtrl
  BranchComparatorUnit.io.A := inst.BranchA
  BranchComparatorUnit.io.B := inst.BranchB
  BranchComparatorUnit.io.Funct3 := inst.BranchFunct3
  BranchComparatorUnit.io.IsBranch := inst.IsBranch
  val BranchTaken = inst.IsBranch && BranchComparatorUnit.io.Taken
  val BranchTarget = inst.pc + inst.Immediate
  val JalTarget = ALUUnit.io.result
  val JalrTarget = Cat(ALUUnit.io.result(31, 1), 0.U(1.W))
  val ControlTransferTaken = BranchTaken || inst.IsJal || inst.IsJalr
  val ControlTransferTarget = Mux(
    inst.IsJal,
    JalTarget,
    Mux(inst.IsJalr, JalrTarget, BranchTarget)
  )
  val InstructionAddressMisaligned =
    !UpEx && ControlTransferTaken && ControlTransferTarget(1, 0) =/= 0.U
  val InstructionTrapValid = UpEx || InstructionAddressMisaligned
  val IsMemoryForCommit = inst.MemoryValid && !InstructionTrapValid
  val IsMDUInstruction =
    inst.IsMDU && !InstructionTrapValid && !io.MemTrapCommit

  // 带标记或EXU动态发现异常的指令只做异常提交，不得产生
  // 访存/CSR写/GPR写/重定向/预测器训练等副作用。
  // 带副作用的指令(csr/ecall/ebreak/mret/fence.i/异常)必须等MEM级排空(在序精确异常):
  // 比它年老的访存可能还没完成,甚至可能是故障要提交异常;
  // fence.i也要等写缓冲排空, 否则新取指可能读到store落内存之前的旧指令
  val IsSideEffect =
    inst.IsCsrrw || inst.IsCsrrs || inst.IsEcall || inst.IsEbreak ||
      inst.IsSimHalt || inst.IsMret || InstructionTrapValid || inst.IsFenceI ||
      inst.IsFence || inst.IsBranch || inst.IsJal || inst.IsJalr
  // IRQ是动态副作用：MEM级尚未排空时必须阻塞所有当前指令，
  // 包括load/store。否则连续访存流可以不断进入MEM，使已使能IRQ无界推迟。
  // branch/JAL/JALR也等老MEM结果确定后再更新BTB/RAS，避免老load fault
  // 冲流后留下无法恢复的预测器状态。
  val BlockIrqForMEM = io.MEMBusy && CSRUnit.io.IrqPending
  val BlockForMEM = (io.MEMBusy && IsSideEffect) || BlockIrqForMEM

  // MDU 的 Flush 必须同时屏蔽旧 Resp；访存故障提交时，正在 EXU 中等待的
  // M 指令属于年轻指令，不能在故障冲刷后泄漏到 MEM/WB。
  MDUUnit.io.Flush := io.MemTrapCommit
  MDUUnit.io.Req.valid := io.in.valid && IsMDUInstruction && !PendingMDU
  MDUUnit.io.Req.bits.LHS := inst.ALU_A
  MDUUnit.io.Req.bits.RHS := inst.ALU_B
  MDUUnit.io.Req.bits.MDUOp := inst.MDUOp

  // Resp.ready 只在输出可以提交时拉高，使 MDU 结果在 backpressure 下保持；
  // 输入 ready 也只在这一拍拉高，从而令 Resp.fire == exu.out.fire == in.fire。
  // Pending MDU的完成拍同样是IRQ提交点，不能绕过MEM排空约束。
  val BlockPendingMDUForMEM =
    io.MEMBusy && CSRUnit.io.IrqPending
  MDUUnit.io.Resp.ready := PendingMDU && io.out.ready &&
    !io.MemTrapCommit && !BlockPendingMDUForMEM
  val MDUReqFire = MDUUnit.io.Req.valid && MDUUnit.io.Req.ready
  val MDURespFire = MDUUnit.io.Resp.valid && MDUUnit.io.Resp.ready

  io.in.ready := Mux(
    PendingMDU,
    MDUUnit.io.Resp.valid && io.out.ready && !io.MemTrapCommit &&
      !BlockPendingMDUForMEM,
    Mux(IsMDUInstruction, false.B, io.out.ready && !BlockForMEM)
  )
  io.out.valid := Mux(
    PendingMDU,
    MDUUnit.io.Resp.valid && !io.MemTrapCommit && !BlockPendingMDUForMEM,
    io.in.valid && !IsMDUInstruction && !BlockForMEM
  )

  when(io.MemTrapCommit) {
    PendingMDU := false.B
  }.elsewhen(MDUReqFire) {
    PendingMDUInst := inst
    PendingMDU := true.B
  }.elsewhen(MDURespFire) {
    PendingMDU := false.B
  }

  val ActiveInst = Wire(chiselTypeOf(io.in.bits))
  ActiveInst := inst
  when(PendingMDU) {
    ActiveInst := PendingMDUInst
  }
  CSRUnit.io.IsCsrrw := inst.IsCsrrw && !InstructionTrapValid
  CSRUnit.io.IsCsrrs := inst.IsCsrrs && !InstructionTrapValid
  CSRUnit.io.IsEcall := inst.IsEcall && !InstructionTrapValid
  CSRUnit.io.IsEbreak := inst.IsEbreak && !InstructionTrapValid
  CSRUnit.io.IsMret := inst.IsMret && !InstructionTrapValid
  CSRUnit.io.CSRAddress := inst.CSRAddress
  CSRUnit.io.rs1 := inst.Rs1
  CSRUnit.io.Rs1Data := inst.Rs1Data
  CSRUnit.io.pc := Mux(io.MemTrapCommit, io.MemTrapPC, inst.pc)
  // fence.i也要产生"重定向": 目标是自己的snpc(即fence.i+4),
  // 借此把流水线里比fence.i年轻的、可能过时的指令全部冲刷并重新取指
  // 实际下一PC: jalr最低位清零由JalrTarget给出;分支not-taken与fence.i都回snpc
  val ActualNextPC = Mux(
    inst.IsJal,
    JalTarget,
    Mux(
      inst.IsJalr,
      JalrTarget,
      Mux(
        BranchTaken,
        BranchTarget,
        inst.snpc
      )
    )
  )
  val CommitNextPC = Mux(inst.IsMret, CSRUnit.io.ExceptionTarget, ActualNextPC)
  // 预测下一PC: IFU用BTB(分支BTFN+独立jal BTB)给出,随指令传到此;未命中=顺序=snpc
  val PredNextPC = Mux(inst.pred_taken, inst.pred_target, inst.snpc)
  // 预测错误检查: 比较实际与预测的下一PC,不一致则冲刷并重定向到实际目标
  // (jal: jal BTB命中免冲刷; ret: Ret表项+RAS栈顶预测; 其他jalr: 无预测=>必然判错重定向)
  val Mispredict = ActualNextPC =/= PredNextPC
  val Redirect =
    (Mispredict || inst.IsFenceI) && !InstructionTrapValid
  // CSR提交(csr写/ecall/ebreak/mret/异常/中断): 普通访存不访问CSR单元；
  // 但已使能IRQ可以在load/store执行前的指令边界提交，并压掉该访存。
  // 带异常标记的访存指令(取指错/非法编码)必须在EXU提交异常, 提交视角下不算访存指令,
  // 否则会落入"等MEM级提交"分支而把异常吞掉
  // 指令级提交只认本拍fire; MEM级故障走独立的MemTrap后门, 这样MemTrap当拍
  // EXU里被冲刷的指令(csrrw/mret)不会产生CSR副作用, 异常目标也不会被mret劫持
  // MEM故障是更老指令的精确异常；当它提交时，当前EXU指令即使因
  // FlushEXMEM而被“放行”也不能再提交自己的CSR/MRET副作用。
  // 普通load/store不经由CSR单元提交，但IRQ pending时必须允许IRQ
  // 在该访存执行前的指令边界提交；后面会用IrqCommit压掉访存副作用。
  CSRUnit.io.Enable := io.in.fire && (!IsMemoryForCommit || CSRUnit.io.IrqPending) &&
    !io.MemTrapCommit
  CSRUnit.io.MemTrap := io.MemTrapCommit
  CSRUnit.io.TrapValid := InstructionTrapValid
  CSRUnit.io.TrapCause := Mux(
    io.MemTrapCommit,
    io.MemTrapCause,
    Mux(
      InstructionAddressMisaligned,
      0.U(32.W),
      Cat(0.U(28.W), inst.ExceptionCause)
    )
  )

  // 当前EXU指令只有在真正被接受、且没有被更老的访存故障或中断
  // 抢占时才算提交。预测器和当前指令产生的控制副作用统一使用它。
  val InstructionCommit =
    io.in.fire && !InstructionTrapValid && !io.MemTrapCommit &&
      !CSRUnit.io.IrqCommit

  io.SimHaltValid := InstructionCommit && inst.IsSimHalt
  io.SimHaltPC := inst.pc
  io.TrapCommit := CSRUnit.io.TrapCommit
  io.TrapPC := Mux(io.MemTrapCommit, io.MemTrapPC, inst.pc)
  io.TrapTarget := CSRUnit.io.ExceptionTarget
  io.TrapCause := CSRUnit.io.CommittedCause
  io.FetchAccessFaultCommit := CSRUnit.io.TrapCommit && !io.MemTrapCommit &&
    !CSRUnit.io.IrqCommit && UpEx && inst.ExceptionCause === 1.U
  io.FetchAccessFaultPC := inst.pc
  io.FetchAccessFaultResp := inst.AccessFaultResp
  io.Redirect := InstructionCommit && Redirect
  io.RedirectTarget := ActualNextPC
  // ecall/mret/上游异常提交,或MEM经CSR后门提交的访存故障
  io.ExceptionTaken := CSRUnit.io.ExceptionTaken
  io.ExceptionTarget := CSRUnit.io.ExceptionTarget

  io.out.bits.Instruction := ActiveInst.Instruction
  // 同步异常指令不退休；IRQ抢占的指令会从mepc重做，也不退休。
  // load/store先携带退休意图进入MEM，若总线故障则由LSU清除。
  io.out.bits.Retire := !InstructionTrapValid && !ActiveInst.IsEcall &&
    !ActiveInst.IsEbreak && !ActiveInst.IsSimHalt && !CSRUnit.io.IrqCommit &&
    !io.MemTrapCommit
  io.out.bits.pc := ActiveInst.pc
  io.out.bits.snpc := ActiveInst.snpc
  // Pending MDU 不可能是控制转移；其它指令使用本拍已
  // 计算的实际后继 PC，MRET 则使用 mepc。
  io.out.bits.NextPC := Mux(PendingMDU, ActiveInst.snpc, CommitNextPC)
  io.out.bits.Rd := ActiveInst.Rd
  // 带异常标记的指令不得写回GPR;被中断压掉的指令(IrqCommit)也不得写回
  io.out.bits.RegisterWrite :=
    ActiveInst.RegisterWrite && !InstructionTrapValid && !CSRUnit.io.IrqCommit &&
      !io.MemTrapCommit
  io.out.bits.WBSelect := ActiveInst.WBSelect
  io.out.bits.ALUResult := Mux(PendingMDU, MDUUnit.io.Resp.bits.Result, ALUUnit.io.result)
  io.out.bits.LoadData := 0.U(32.W) // 由MEM在访存完成后填写
  io.out.bits.CSRReadData := CSRUnit.io.CSR_rdata
  io.out.bits.MemoryValid :=
    ActiveInst.MemoryValid && !InstructionTrapValid && !CSRUnit.io.IrqCommit &&
      !io.MemTrapCommit
  io.out.bits.MemoryWrite := ActiveInst.MemoryWrite && !InstructionTrapValid &&
    !CSRUnit.io.IrqCommit && !io.MemTrapCommit
  io.out.bits.WidthSelect := ActiveInst.WidthSelect
  io.out.bits.LoadSigned := ActiveInst.LoadSigned
  io.out.bits.StoreData := ActiveInst.StoreData
  io.out.bits.ExceptionValid := InstructionTrapValid
  io.out.bits.ExceptionCause := Mux(
    InstructionAddressMisaligned,
    0.U(4.W),
    ActiveInst.ExceptionCause
  )

  io.PerfALUOp := !inst.MemoryValid && !inst.IsMDU && !inst.IsCsrrw &&
    !inst.IsCsrrs && !inst.IsBranch && !inst.IsJal && !inst.IsJalr
  io.PerfMemOp := inst.MemoryValid
  io.PerfCSROp := inst.IsCsrrw || inst.IsCsrrs
  io.PerfBranchOp := inst.IsBranch || inst.IsJal || inst.IsJalr
  io.PerfJalOp := inst.IsJal
  io.PerfJalrOp := inst.IsJalr
  io.PerfMDUReq := MDUReqFire
  io.PerfMDUDone := io.out.fire && ActiveInst.IsMDU && !ActiveInst.ExceptionValid && !io.MemTrapCommit
  io.PerfMDUOp := ActiveInst.MDUOp
  io.PerfMDUActive := PendingMDU
  io.PerfMDUWait := PendingMDU && !(MDUUnit.io.Resp.valid && MDUUnit.io.Resp.ready)
  io.PerfExecutionActive := io.in.valid || PendingMDU

  // 这些是“当前EXU指令”的副作用。中断接受或更老访存故障提交时，
  // 当前指令会被重做/丢弃，不能训练预测器，也不能产生重定向或FenceI刷新。
  io.FenceIFlush := InstructionCommit && inst.IsFenceI
  io.FlushIF := io.Redirect || io.ExceptionTaken

  io.HazardValid := io.in.valid || PendingMDU
  io.HazardRd := ActiveInst.Rd
  // 带异常标记的指令不会真正写回,不应让IDU白白等它;被中断压掉的指令同理
  io.HazardRegWrite :=
    ActiveInst.RegisterWrite && !InstructionTrapValid && !CSRUnit.io.IrqCommit &&
      !io.MemTrapCommit
  io.HazardMemOp := ActiveInst.MemoryValid
  io.PerfIdleNoInput := !io.in.valid && !PendingMDU
  io.PerfTrap := CSRUnit.io.TrapCommit
  // 转发给IDU的最终写回值(与WBU写GPR的值一致): ALU结果/snpc/CSR读出
  // (load数据在EXU阶段不可知,由MEM提供它那一级的转发)
  io.FwdData := Mux(
    PendingMDU,
    MDUUnit.io.Resp.bits.Result,
    Mux(
      ActiveInst.WBSelect === 2.U,
      ActiveInst.snpc,
      Mux(ActiveInst.WBSelect === 3.U, CSRUnit.io.CSR_rdata, ALUUnit.io.result)
    )
  )
  // 可转发条件: 会写rd,且不是load(load要等MEM完成)
  io.FwdReady := io.HazardValid && io.HazardRegWrite && !ActiveInst.MemoryValid &&
    (!IsMDUInstruction || MDUUnit.io.Resp.valid)

  // BTB更新: 所有分支指令提交时都写回PC→target(不管是否taken),
  // 供IFU查BTB命中后用BTFN(target<PC=后向则taken)做方向预测.
  io.BTBUpdateValid := InstructionCommit && inst.IsBranch
  io.BTBUpdatePC := inst.pc
  io.BTBUpdateTarget := BranchTarget
  // RISC-V RAS hint同时使用x1(ra)与x5(alternate link register)。JAL在rd为
  // link register时push；JALR按rd/rs1 hint表产生push、pop，异名link register
  // (x1<-x5或x5<-x1)是协程切换，需要同拍pop后push。
  val RdIsLink = inst.Rd === 1.U || inst.Rd === 5.U
  val Rs1IsLink = inst.Rs1 === 1.U || inst.Rs1 === 5.U
  val RASPush = (inst.IsJal && RdIsLink) || (inst.IsJalr && RdIsLink)
  val RASPop =
    inst.IsJalr && Rs1IsLink && (!RdIsLink || inst.Rd =/= inst.Rs1)
  // jal BTB更新: jal提交时写回PC→JalTarget(Jal/Call表项, 目标静态);
  // ret提交时目标字段保存静态JALR immediate，预测时与动态RAS栈顶相加。
  // 间接jalr(非call非ret)目标多变, 不入表
  io.JalBTBUpdateValid := InstructionCommit && (inst.IsJal || RASPop)
  io.JalBTBUpdatePC := inst.pc
  io.JalBTBUpdateTarget := Mux(inst.IsJal, JalTarget, inst.Immediate)
  io.JalBTBUpdateKind := Mux(
    RASPop,
    ysyx_26030103_BTBKind.Ret,
    Mux(RdIsLink, ysyx_26030103_BTBKind.Call, ysyx_26030103_BTBKind.Jal)
  )
  // RAS更新：协程JALR会同时拉高push/pop，RAS内部按pop-then-push处理。
  io.RASPushValid := InstructionCommit && RASPush
  io.RASPushAddr := inst.snpc
  io.RASPopValid := InstructionCommit && RASPop
}
