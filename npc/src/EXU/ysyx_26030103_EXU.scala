package ysyx_26030103.exu
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._
import _root_.ysyx_26030103.config._
class ysyx_26030103_EXU(
    val config: ysyx_26030103_NPCConfig = ysyx_26030103_NPCConfig()
) extends Module {
  private val hasS = config.MMU.hasS
  private val XLEN = config.XLEN
  // RV64 W 型运算的 32 位结果按 ISA 要求符号扩展到 XLEN。
  private def Sext32(Value: UInt): UInt =
    if (XLEN == 32) Value else Cat(Fill(XLEN - 32, Value(31)), Value)
  private def Zext32(Value: UInt): UInt =
    if (XLEN == 32) Value else Cat(0.U((XLEN - 32).W), Value)
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ysyx_26030103_IDUMessage(XLEN)))
    val out = Decoupled(new ysyx_26030103_EXUMessage(XLEN))
    val Redirect = Output(Bool())
    val RedirectTarget = Output(UInt(XLEN.W))
    val ExceptionTaken = Output(Bool())
    val ExceptionTarget = Output(UInt(XLEN.W))
    val Interrupt = Input(Bool())
    val TimerInterrupt = Input(Bool())
    // 当EXU没有有效输入、且所有更老指令都已排空时，中断仍必须在当前
    // 架构指令边界提交。该PC由顶层的提交态PC提供，不能使用可能已因
    // 预取而前移的任意流水级bits。
    val IdleIrqPC = Input(UInt(XLEN.W))
    // MEM的反馈分成两类：MEMBusy只表示年长访存事务，用于
    // branch/fence等只需等待“可能故障的访存”的场景；PipelineBusy
    // 表示任意年长指令仍在MEM流水级，用于IRQ/CSR/异常的精确顺序。
    val MEMBusy = Input(Bool())
    val PipelineBusy = Input(Bool())
    val MemTrapCommit = Input(Bool())
    val MemTrapCause = Input(UInt(32.W))
    val MemTrapPC = Input(UInt(XLEN.W))
    val MemTrapTval = Input(UInt(XLEN.W))

    // custom-0 仿真 halt 的提交事件；架构 EBREAK 不走这条通路。
    val SimHaltValid = Output(Bool())
    val SimHaltPC = Output(UInt(XLEN.W))
    // 精确架构 trap 提交事件（不包含 MRET），用于 DiffTest
    // 和调试端在正确指令边界观察 PC 改变。
    val TrapCommit = Output(Bool())
    val TrapPC = Output(UInt(XLEN.W))
    val TrapTarget = Output(UInt(XLEN.W))
    val TrapCause = Output(UInt(32.W))
    val CSRPrivilege = Output(UInt(2.W))
    val CSRSatp = Output(UInt(XLEN.W))
    val CSRCurrentMstatus = Output(UInt(XLEN.W))
    val CSRFlushTLB = Output(Bool())
    val CSRStateMstatus = Output(UInt(XLEN.W))
    val CSRStateMtvec = Output(UInt(XLEN.W))
    val CSRStateMepc = Output(UInt(XLEN.W))
    val CSRStateMcause = Output(UInt(XLEN.W))
    val PmpCfg = Output(Vec(16, UInt(8.W)))
    val PmpAddr = Output(Vec(16, UInt(XLEN.W)))
    val Mtime = Input(UInt(64.W))
    val Mcounteren = Output(UInt(32.W))
    val Scounteren = Output(UInt(32.W))
    // 取指 access fault 只在对应指令真正提交异常时上报。
    val FetchAccessFaultCommit = Output(Bool())
    val FetchAccessFaultPC = Output(UInt(XLEN.W))
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
    // 0=无事件，1=ALU，2=MEM，3=CSR，4=BRANCH，5=JAL，6=JALR，7=MDU。
    // 这是一个时钟沿前事件；现有的perf_*类型信号仍作为电平信号，
    // 用于统计活跃周期。
    val PerfEventKind = Output(UInt(3.W))
    val PerfMDUActive = Output(Bool())
    val PerfMDUWait = Output(Bool())
    val PerfExecutionActive = Output(Bool())

    val FenceIFlush = Output(Bool())
    // 任一条 CSR 指令真正提交（含 sfence.vma）。顶层用它保守地让
    // LR  reservation 失效，覆盖上下文切换/页表切换等场景。
    val CSRCommit = Output(Bool())

    // 统一冲刷输出: 分支/跳转/fence.i重定向或异常(ecall/mret/中断/各类异常)提交时,
    // 冲刷IFU内部状态和IFU→IDU流水寄存器里的年轻指令
    // (MEM故障的冲刷由MEM发起,通过CSR后门使ExceptionTaken覆盖该路径)
    val FlushIF = Output(Bool())

    // 给IDU做数据冒险检测用(EXU单拍级,指令就是in.bits本身)
    val HazardValid = Output(Bool())
    val HazardRd = Output(UInt(5.W))
    val HazardRegWrite = Output(Bool())
    val HazardMemOp = Output(Bool())
    // 属于队首之外已排队MDU指令的写入。
    // IDU尚不能转发这些值，必须阻塞与之匹配的消费者。
    val HazardMDUHiddenWrites = Output(UInt(32.W))
    val PerfIdleNoInput = Output(Bool())
    val PerfTrap = Output(Bool())
    // 转发给IDU: EX阶段生产者的最终写回值,以及该值当前是否可用于转发
    val FwdData = Output(UInt(XLEN.W))
    val FwdReady = Output(Bool())
    // BTB更新: 分支提交时把真实target写回分支BTB
    val BTBUpdateValid = Output(Bool())
    val BTBUpdatePC = Output(UInt(XLEN.W))
    val BTBUpdateTarget = Output(UInt(XLEN.W))
    // jal BTB更新: jal/call/ret提交时写回独立jal BTB(方案B), 带2位类型
    val JalBTBUpdateValid = Output(Bool())
    val JalBTBUpdatePC = Output(UInt(XLEN.W))
    val JalBTBUpdateTarget = Output(UInt(XLEN.W))
    val JalBTBUpdateKind =
      Output(UInt(2.W)) // ysyx_26030103_BTBKind：普通跳转/调用/返回
    // RAS更新: call提交压栈(返回地址=snpc), ret提交弹栈
    val RASPushValid = Output(Bool())
    val RASPushAddr = Output(UInt(XLEN.W))
    val RASPopValid = Output(Bool())
  })
  val ALUUnit = Module(new ysyx_26030103_ALU(XLEN))
  val CSRUnit = Module(new ysyx_26030103_CSR(config.MMU, XLEN, config.UseA))
  CSRUnit.io.clk := clock
  CSRUnit.io.rst := reset.asBool
  CSRUnit.io.Interrupt := io.Interrupt
  CSRUnit.io.TimerInterrupt := io.TimerInterrupt
  val BranchComparatorUnit = Module(new ysyx_26030103_BranchComparator(XLEN))
  val inst = io.in.bits
  // M扩展关闭时，这些信号全部折叠为常量；MDU模块和顺序
  // 队列只在elaboration期的UseM分支中生成。这避免了RISCV_M=n时
  // 仅靠译码器“不使用”但仍然生成完整乘除法器的问题。
  val PendingMDU = WireDefault(false.B)
  val PendingMDUInst = WireDefault(0.U.asTypeOf(chiselTypeOf(io.in.bits)))
  val MDUReqReady = WireDefault(false.B)
  val MDUReqFire = WireDefault(false.B)
  val MDURespFire = WireDefault(false.B)
  val MDURespValid = WireDefault(false.B)
  val MDURespResult = WireDefault(0.U(XLEN.W))
  val MDUHiddenWriteMask = WireDefault(0.U(32.W))
  // RV64 W 型 M 扩展指令映射到对应的 XLEN 基础运算：
  //   MULW/DIVW/REMW 先符号扩展低 32 位操作数；
  //   DIVUW/REMUW 先零扩展低 32 位操作数；
  // 结果再由 Sext32 把低 32 位符号扩展回 XLEN。
  val IsWordMDU = ysyx_26030103_MDUOp.IsWordOp(inst.MDUOp)
  val WordMDUUnsigned = inst.MDUOp === ysyx_26030103_MDUOp.DIVUW ||
    inst.MDUOp === ysyx_26030103_MDUOp.REMUW
  val MDUBaseOp = MuxLookup(inst.MDUOp, inst.MDUOp)(
    Seq(
      ysyx_26030103_MDUOp.MULW -> ysyx_26030103_MDUOp.MUL,
      ysyx_26030103_MDUOp.DIVW -> ysyx_26030103_MDUOp.DIV,
      ysyx_26030103_MDUOp.DIVUW -> ysyx_26030103_MDUOp.DIVU,
      ysyx_26030103_MDUOp.REMW -> ysyx_26030103_MDUOp.REM,
      ysyx_26030103_MDUOp.REMUW -> ysyx_26030103_MDUOp.REMU
    )
  )
  val MDUWordLHS =
    if (XLEN == 32) inst.ALU_A
    else Mux(WordMDUUnsigned, Zext32(inst.ALU_A(31, 0)),
      Sext32(inst.ALU_A(31, 0)))
  val MDUWordRHS =
    if (XLEN == 32) inst.ALU_B
    else Mux(WordMDUUnsigned, Zext32(inst.ALU_B(31, 0)),
      Sext32(inst.ALU_B(31, 0)))
  val MDUReqLHS = if (XLEN == 32) inst.ALU_A
    else Mux(IsWordMDU, MDUWordLHS, inst.ALU_A)
  val MDUReqRHS = if (XLEN == 32) inst.ALU_B
    else Mux(IsWordMDU, MDUWordRHS, inst.ALU_B)
  val MDUReqOp = if (XLEN == 32) inst.MDUOp else Mux(IsWordMDU, MDUBaseOp, inst.MDUOp)
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
  val JalrTarget = Cat(ALUUnit.io.result(XLEN - 1, 1), 0.U(1.W))
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
    (if (config.UseM) inst.IsMDU else false.B) && !InstructionTrapValid &&
      !io.MemTrapCommit

  // 带标记或EXU动态发现异常的指令只做异常提交，不得产生
  // 访存/CSR写/GPR写/重定向/预测器训练等副作用。
  // CSR/异常/IRQ会立即改变架构状态，必须等任意年长MEM级指令
  // 越过该精确边界。branch/JAL/JALR只需等待年长访存：普通ALU
  // 不会故障，不应该让控制转移多停一拍；但年长load/store仍可能
  // 提交精确故障，在它完成前不能留下无法回滚的BTB/RAS状态。
  val RequiresPipelineDrain =
    inst.IsCsrrw || inst.IsCsrrs || inst.IsCsrrc || inst.IsEcall || inst.IsEbreak ||
      inst.IsSimHalt || inst.IsMret ||
      (if (hasS) inst.IsSret || inst.IsSfenceVma else false.B) ||
      InstructionTrapValid
  val RequiresMemoryDrain =
    inst.IsFenceI || inst.IsFence || inst.IsBranch || inst.IsJal || inst.IsJalr
  // MEMBusy在顶层是PipelineBusy的子集。这里仍取或作为防御，
  // 也让叶子EXU回归可以只驱动MEMBusy来表示年长访存。
  val OlderPipelineBusy = io.PipelineBusy || io.MEMBusy
  // IRQ是动态精确副作用：只要任意年长MEM级指令未越过，
  // 就不能在当前指令边界提交IRQ。
  val BlockIrqForPipeline = OlderPipelineBusy && CSRUnit.io.IrqPending
  val BlockForOlder =
    (OlderPipelineBusy && RequiresPipelineDrain) ||
      (io.MEMBusy && RequiresMemoryDrain) || BlockIrqForPipeline

  // 过去IRQ提交完全依赖io.in.fire，取指若长期不返回便没有提交机会。
  // 仅在EXU输入、MDU队列及所有更老流水级都为空时，才可使用顶层给出的
  // 精确架构PC建立一个空流水指令边界。MemTrap属于更老指令，优先级更高。
  val IdleIrqCommit =
    !io.in.valid && !PendingMDU && !OlderPipelineBusy &&
      CSRUnit.io.IrqPending && !io.MemTrapCommit

  val BlockPendingMDUForMEM =
    OlderPipelineBusy && CSRUnit.io.IrqPending
  // IRQ可能在指令边界结束当前输入。应消耗该M扩展输入以便提交中断，但不启动
  // MDU请求；一旦存在更老的MDU任务，就保持该输入，直到队列排空。
  val MDUInputBlockedByIRQ =
    IsMDUInstruction && CSRUnit.io.IrqPending && !io.MemTrapCommit
  // 尚未发射的M扩展指令只有在所有更老MEM指令都排空后，才能被消耗并在IRQ后重放。
  // 否则，MDU专用ready路径会绕过BlockIrqForPipeline，破坏精确陷阱顺序。
  val MDUInputKilledByIRQ =
    MDUInputBlockedByIRQ && !PendingMDU && !OlderPipelineBusy

  if (config.UseM) {
    val MDUUnit = Module(new ysyx_26030103_MDU(config))
    // MDU请求保存在顺序指令队列中。这样，即使最老结果仍在
    // 等待LSU/WBU级，流水乘法器也能接收连续的M扩展指令。
    val MDUQueueDepth = config.MDUMaxInflight
    val MDUQueuePtrWidth = log2Ceil(MDUQueueDepth).max(1)
    val MDUQueueCountWidth = log2Ceil(MDUQueueDepth + 1)
    val MDUInstQueue = RegInit(
      VecInit(Seq.fill(MDUQueueDepth)(0.U.asTypeOf(chiselTypeOf(io.in.bits))))
    )
    val MDUQueueHead = RegInit(0.U(MDUQueuePtrWidth.W))
    val MDUQueueTail = RegInit(0.U(MDUQueuePtrWidth.W))
    val MDUQueueCount = RegInit(0.U(MDUQueueCountWidth.W))
    PendingMDU := MDUQueueCount =/= 0.U
    PendingMDUInst := MDUInstQueue(MDUQueueHead)

    // MDU的Flush必须同时屏蔽旧Resp；访存故障提交时，所有已经
    // 接收的年轻M指令都属于待取消事务。
    MDUUnit.io.Flush := io.MemTrapCommit
    MDUUnit.io.Req.bits.LHS := MDUReqLHS
    MDUUnit.io.Req.bits.RHS := MDUReqRHS
    MDUUnit.io.Req.bits.MDUOp := MDUReqOp

    val MDURespReady = PendingMDU && io.out.ready &&
      !io.MemTrapCommit && !BlockPendingMDUForMEM
    MDUUnit.io.Resp.ready := MDURespReady
    MDURespValid := MDUUnit.io.Resp.valid
    MDURespResult := MDUUnit.io.Resp.bits.Result
    MDURespFire := MDUUnit.io.Resp.valid && MDURespReady
    val MDUQueueHasSpace = MDUQueueCount < MDUQueueDepth.U || MDURespFire
    MDUReqReady := IsMDUInstruction && !MDUInputBlockedByIRQ &&
      MDUQueueHasSpace && MDUUnit.io.Req.ready
    MDUUnit.io.Req.valid := io.in.valid && IsMDUInstruction &&
      !MDUInputBlockedByIRQ && MDUQueueHasSpace
    MDUReqFire := MDUUnit.io.Req.valid && MDUUnit.io.Req.ready
    assert(!MDUReqFire || MDUQueueHasSpace, "EXU MDU instruction queue overflow")
    assert(
      !MDURespFire || PendingMDU,
      "EXU received an MDU response without an instruction"
    )
    assert(MDUQueueCount <= MDUQueueDepth.U, "EXU MDU instruction queue overflowed")

    val NextMDUQueueTail = Mux(
      MDUQueueTail === (MDUQueueDepth - 1).U,
      0.U,
      MDUQueueTail + 1.U
    )
    val NextMDUQueueHead = Mux(
      MDUQueueHead === (MDUQueueDepth - 1).U,
      0.U,
      MDUQueueHead + 1.U
    )
    when(io.MemTrapCommit) {
      MDUQueueCount := 0.U
      MDUQueueHead := 0.U
      MDUQueueTail := 0.U
    }.elsewhen(MDUReqFire || MDURespFire) {
      when(MDUReqFire) {
        MDUInstQueue(MDUQueueTail) := inst
        MDUQueueTail := NextMDUQueueTail
      }
      when(MDURespFire) {
        MDUQueueHead := NextMDUQueueHead
      }
      when(MDUReqFire && !MDURespFire) {
        MDUQueueCount := MDUQueueCount + 1.U
      }.elsewhen(!MDUReqFire && MDURespFire) {
        MDUQueueCount := MDUQueueCount - 1.U
      }
    }

    // 现有的单一冒险/转发端口描述MDU队首。为每个更年轻的已排队写入者，
    // 以及当前正在等待入队的MDU输入构建掩码。
    var HiddenMDUWriteMask: UInt = 0.U(32.W)
    for (Offset <- 1 until MDUQueueDepth) {
      val ExtendedHead = Cat(0.U(1.W), MDUQueueHead)
      val RawIndex = ExtendedHead + Offset.U((MDUQueuePtrWidth + 1).W)
      val WrappedIndex = Mux(
        RawIndex >= MDUQueueDepth.U,
        RawIndex - MDUQueueDepth.U,
        RawIndex
      )
      val Entry = MDUInstQueue(WrappedIndex(MDUQueuePtrWidth - 1, 0))
      val EntryWrites = Offset.U < MDUQueueCount && Entry.RegisterWrite &&
        Entry.Rd =/= 0.U
      HiddenMDUWriteMask = HiddenMDUWriteMask |
        Mux(EntryWrites, UIntToOH(Entry.Rd, 32), 0.U(32.W))
    }
    val CurrentMDUWrite = PendingMDU && io.in.valid && IsMDUInstruction &&
      inst.RegisterWrite && inst.Rd =/= 0.U
    val CurrentMDUWriteMask =
      Mux(CurrentMDUWrite, UIntToOH(inst.Rd, 32), 0.U(32.W))
    MDUHiddenWriteMask := HiddenMDUWriteMask | CurrentMDUWriteMask
  }

  io.in.ready := Mux(
    IsMDUInstruction,
    Mux(MDUInputKilledByIRQ, true.B, MDUReqReady),
    // 待处理的MDU结果会占用本周期唯一的输出槽位。更年轻的非MDU指令必须留在
    // IDU->EXU寄存器中，直到该结果发出；若在这里接收它，就会在未产生其EXU输出
    // 的情况下将其消耗。
    !PendingMDU && io.out.ready && !BlockForOlder
  )
  io.out.valid := Mux(
    PendingMDU,
    MDURespValid && !io.MemTrapCommit && !BlockPendingMDUForMEM,
    io.in.valid && !IsMDUInstruction && !PendingMDU && !BlockForOlder
  )

  val ActiveInst = Wire(chiselTypeOf(io.in.bits))
  ActiveInst := inst
  when(PendingMDU) {
    ActiveInst := PendingMDUInst
  }
  val ActiveIsMDU = if (config.UseM) ActiveInst.IsMDU else false.B
  val ActiveMDUOp =
    if (config.UseM) ActiveInst.MDUOp else 0.U(ysyx_26030103_MDUOp.Width.W)
  val ActiveInstructionTrapValid = Mux(
    PendingMDU,
    ActiveInst.ExceptionValid,
    InstructionTrapValid
  )
  val ActiveExceptionCause = Mux(
    PendingMDU,
    ActiveInst.ExceptionCause,
    Mux(
      InstructionAddressMisaligned,
      0.U,
      ActiveInst.ExceptionCause
    )
  )
  // Decoupled.bits在valid=0时没有语义。空流水IRQ会独立拉高CSR Enable，
  // 因此所有“当前指令”控制必须显式由valid门控，不能让上一个MRET/CSR
  // 留下的bits与IRQ同时产生副作用。
  val ValidInstructionForCSR =
    io.in.valid && !PendingMDU && !InstructionTrapValid
  CSRUnit.io.IsCsrrw := ValidInstructionForCSR && inst.IsCsrrw
  CSRUnit.io.IsCsrrs := ValidInstructionForCSR && inst.IsCsrrs
  CSRUnit.io.IsCsrrc := ValidInstructionForCSR && inst.IsCsrrc
  CSRUnit.io.IsEcall := ValidInstructionForCSR && inst.IsEcall
  CSRUnit.io.IsEbreak := ValidInstructionForCSR && inst.IsEbreak
  CSRUnit.io.IsMret := ValidInstructionForCSR && inst.IsMret
  CSRUnit.io.IsSret :=
    ValidInstructionForCSR && (if (hasS) inst.IsSret else false.B)
  CSRUnit.io.IsSfenceVma :=
    ValidInstructionForCSR && (if (hasS) inst.IsSfenceVma else false.B)
  CSRUnit.io.CSRAddress := inst.CSRAddress
  CSRUnit.io.rs1 := inst.Rs1
  CSRUnit.io.Rs1Data := inst.Rs1Data
  val TrapBoundaryPC = Mux(
    io.MemTrapCommit,
    io.MemTrapPC,
    Mux(IdleIrqCommit, io.IdleIrqPC, inst.pc)
  )
  CSRUnit.io.pc := TrapBoundaryPC
  CSRUnit.io.TrapTval := Mux(io.MemTrapCommit, io.MemTrapTval,
    Mux(inst.MemoryValid, ALUUnit.io.result, inst.pc))
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
  val CommitNextPC =
    if (hasS) Mux(inst.IsMret || inst.IsSret,
      CSRUnit.io.ExceptionTarget, ActualNextPC)
    else Mux(inst.IsMret, CSRUnit.io.ExceptionTarget, ActualNextPC)
  // 预测下一PC: IFU用BTB(分支BTFN+独立jal BTB)给出,随指令传到此;未命中=顺序=snpc
  val PredNextPC = Mux(inst.pred_taken, inst.pred_target, inst.snpc)
  // 预测错误检查: 比较实际与预测的下一PC,不一致则冲刷并重定向到实际目标
  // (jal: jal BTB命中免冲刷; ret: Ret表项+RAS栈顶预测; 其他jalr: 无预测=>必然判错重定向)
  val Mispredict = ActualNextPC =/= PredNextPC
  // MRET 的实际下一PC是mepc，不参与这里的顺序PC比较：它的重定向完全由
  // ExceptionTaken 承担。此前陈旧BTB表项会让 MRET 走 Mispredict 产生一次
  // 伪 Redirect（目标虽已修正为mepc，但事件语义/计数仍会误导）。
  val Redirect =
    (Mispredict || inst.IsFenceI) && !InstructionTrapValid &&
      !inst.IsMret && (if (hasS) !inst.IsSret else true.B)
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
  val InstructionCSRCommit = io.in.fire && !PendingMDU &&
    (!IsMemoryForCommit || CSRUnit.io.IrqPending) && !io.MemTrapCommit
  CSRUnit.io.Enable := InstructionCSRCommit || IdleIrqCommit
  CSRUnit.io.MemTrap := io.MemTrapCommit
  CSRUnit.io.TrapValid :=
    io.in.valid && !PendingMDU && InstructionTrapValid
  CSRUnit.io.TrapCause := Mux(
    io.MemTrapCommit,
    io.MemTrapCause,
    Mux(
      InstructionAddressMisaligned,
      0.U(32.W),
      Cat(0.U((XLEN - 4).W), inst.ExceptionCause)
    )
  )

  // 当前EXU指令只有在真正被接受、且没有被更老的访存故障或中断
  // 抢占时才算提交。预测器和当前指令产生的控制副作用统一使用它。
  val InstructionCommit =
    io.in.fire && !PendingMDU && !InstructionTrapValid && !io.MemTrapCommit &&
      !CSRUnit.io.IrqCommit
  // 性能事件描述的是当前正发往下一级的指令，而不是排出待处理MDU结果时
  // 恰好呈现的任何更年轻输入。
  val ActiveCommit = Mux(
    PendingMDU,
    MDURespFire && !io.MemTrapCommit && !CSRUnit.io.IrqCommit,
    io.out.fire && !ActiveInstructionTrapValid && !io.MemTrapCommit &&
      !CSRUnit.io.IrqCommit
  )

  io.SimHaltValid := InstructionCommit && inst.IsSimHalt
  io.SimHaltPC := inst.pc
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
  io.PmpCfg := CSRUnit.io.PmpCfg
  io.PmpAddr := CSRUnit.io.PmpAddr
  CSRUnit.io.Mtime := io.Mtime
  io.Mcounteren := CSRUnit.io.McounterenOut
  io.Scounteren := CSRUnit.io.ScounterenOut
  io.FetchAccessFaultCommit := CSRUnit.io.TrapCommit && !io.MemTrapCommit &&
    !CSRUnit.io.IrqCommit && UpEx && inst.ExceptionCause === 1.U
  io.FetchAccessFaultPC := inst.pc
  io.FetchAccessFaultResp := inst.AccessFaultResp
  io.Redirect := InstructionCommit && Redirect
  // 重定向目标必须与实际提交的下一PC一致：MRET 的实际目标是 mepc
  // （CommitNextPC 的 MRET 分支），若用 ActualNextPC 会给出顺序 snpc。
  // 功能上 MRET 由 ExceptionTaken 优先分支覆盖，此处只修正
  // RedirectTarget 的语义（消费方/波形/perf 归因不再看到错值）。
  io.RedirectTarget := CommitNextPC
  // ecall/mret/上游异常提交,或MEM经CSR后门提交的访存故障
  io.ExceptionTaken := CSRUnit.io.ExceptionTaken
  io.ExceptionTarget := CSRUnit.io.ExceptionTarget

  io.out.bits.Instruction := ActiveInst.Instruction
  // 同步异常指令不退休；IRQ抢占的指令会从mepc重做，也不退休。
  // load/store先携带退休意图进入MEM，若总线故障则由LSU清除。
  io.out.bits.Retire := !ActiveInstructionTrapValid && !ActiveInst.IsEcall &&
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
    ActiveInst.RegisterWrite && !ActiveInstructionTrapValid &&
      !CSRUnit.io.IrqCommit && !io.MemTrapCommit
  io.out.bits.WBSelect := ActiveInst.WBSelect
  // RV64 W 型 M 扩展结果只保留低 32 位并按 bit31 符号扩展。
  val MDUResultFinal =
    if (XLEN == 64)
      Mux(ysyx_26030103_MDUOp.IsWordOp(ActiveInst.MDUOp),
        Sext32(MDURespResult(31, 0)), MDURespResult)
    else MDURespResult
  io.out.bits.ALUResult := Mux(PendingMDU, MDUResultFinal, ALUUnit.io.result)
  io.out.bits.VAddr := ALUUnit.io.result
  io.out.bits.LoadData := 0.U(XLEN.W) // 由MEM在访存完成后填写
  io.out.bits.CSRReadData := CSRUnit.io.CSR_rdata
  io.out.bits.CSRStateMstatus := CSRUnit.io.StateMstatus
  io.out.bits.CSRStateMtvec := CSRUnit.io.StateMtvec
  io.out.bits.CSRStateMepc := CSRUnit.io.StateMepc
  io.out.bits.CSRStateMcause := CSRUnit.io.StateMcause
  io.out.bits.MemoryValid :=
    ActiveInst.MemoryValid && !ActiveInstructionTrapValid &&
      !CSRUnit.io.IrqCommit && !io.MemTrapCommit
  io.out.bits.MemoryWrite :=
    ActiveInst.MemoryWrite && !ActiveInstructionTrapValid &&
      !CSRUnit.io.IrqCommit && !io.MemTrapCommit
  io.out.bits.WidthSelect := ActiveInst.WidthSelect
  io.out.bits.LoadSigned := ActiveInst.LoadSigned
  io.out.bits.StoreData := ActiveInst.StoreData
  // A 扩展：把译码得到的原子标记/操作号透传到 LSU。
  io.out.bits.IsAtomic := ActiveInst.IsAtomic
  io.out.bits.AtomicOp := ActiveInst.AtomicOp
  io.out.bits.ExceptionValid := ActiveInstructionTrapValid
  io.out.bits.ExceptionCause := ActiveExceptionCause

  val PerfAluEligible = ActiveCommit &&
    !ActiveInst.MemoryValid && !ActiveIsMDU && !ActiveInst.IsCsrrw &&
    !ActiveInst.IsCsrrs && !ActiveInst.IsCsrrc && !ActiveInst.IsBranch && !ActiveInst.IsJal &&
    !ActiveInst.IsJalr && !ActiveInst.IsEcall && !ActiveInst.IsEbreak &&
    !ActiveInst.IsMret && !ActiveInst.IsSimHalt && !ActiveInst.IsFence &&
    !ActiveInst.IsFenceI && !ActiveInst.ExceptionValid
  val PerfEventKind = Mux(
    !ActiveCommit,
    0.U,
    Mux(
      ActiveIsMDU,
      7.U,
      Mux(
        ActiveInst.MemoryValid,
        2.U,
        Mux(
          ActiveInst.IsCsrrw || ActiveInst.IsCsrrs || ActiveInst.IsCsrrc,
          3.U,
          Mux(
            ActiveInst.IsJal,
            5.U,
            Mux(
              ActiveInst.IsJalr,
              6.U,
              Mux(ActiveInst.IsBranch, 4.U, Mux(PerfAluEligible, 1.U, 0.U))
            )
          )
        )
      )
    )
  )
  // 类型电平用于活跃周期统计。PendingMDU时io.in.bits可能已经是
  // 被反压的年轻指令，因此必须与输出数据一样使用ActiveInst，
  // 否则会把MDU等待周期错记到年轻ALU/MEM/CSR/branch上。
  val ActiveALUClass =
    !ActiveInst.MemoryValid && !ActiveIsMDU && !ActiveInst.IsCsrrw &&
      !ActiveInst.IsCsrrs && !ActiveInst.IsCsrrc && !ActiveInst.IsBranch && !ActiveInst.IsJal &&
      !ActiveInst.IsJalr && !ActiveInst.IsEcall && !ActiveInst.IsEbreak &&
      !ActiveInst.IsMret && !ActiveInst.IsSimHalt && !ActiveInst.IsFence &&
      !ActiveInst.IsFenceI && !ActiveInstructionTrapValid
  io.PerfALUOp := ActiveALUClass
  io.PerfMemOp := ActiveInst.MemoryValid && !ActiveInstructionTrapValid
  io.PerfCSROp := ActiveInst.IsCsrrw || ActiveInst.IsCsrrs || ActiveInst.IsCsrrc
  io.PerfBranchOp := ActiveInst.IsBranch || ActiveInst.IsJal || ActiveInst.IsJalr
  io.PerfJalOp := ActiveInst.IsJal
  io.PerfJalrOp := ActiveInst.IsJalr
  io.PerfEventKind := PerfEventKind
  io.PerfMDUReq := MDUReqFire
  io.PerfMDUDone := ActiveCommit && ActiveIsMDU && !ActiveInst.ExceptionValid
  io.PerfMDUOp := ActiveMDUOp
  // MDUActive包含首次发射拍。MDUWait只统计等待MDU本身的周期；
  // 若结果已valid却因EX/MEM/LSU反压未接收，应归到LSU/下游stall，
  // 不再误算成MDU计算延迟。
  val WaitingForFirstMDUResult =
    !PendingMDU && io.in.valid && IsMDUInstruction && !MDUInputBlockedByIRQ
  val WaitingForPendingMDUResult = PendingMDU && !MDURespValid
  io.PerfMDUActive := PendingMDU || MDUReqFire
  io.PerfMDUWait := !io.out.fire &&
    (WaitingForFirstMDUResult || WaitingForPendingMDUResult)
  io.PerfExecutionActive := io.in.valid || PendingMDU

  // 这些是“当前EXU指令”的副作用。中断接受或更老访存故障提交时，
  // 当前指令会被重做/丢弃，不能训练预测器，也不能产生重定向或FenceI刷新。
  io.FenceIFlush := InstructionCommit && inst.IsFenceI
  // CSR 指令提交视为上下文/地址空间可能切换，保守地清除 LR reservation。
  io.CSRCommit := InstructionCommit &&
    (inst.IsCsrrw || inst.IsCsrrs || inst.IsCsrrc ||
      (if (hasS) inst.IsSfenceVma else false.B))
  io.FlushIF := io.Redirect || io.ExceptionTaken

  io.HazardValid := io.in.valid || PendingMDU
  io.HazardRd := ActiveInst.Rd
  // 带异常标记的指令不会真正写回,不应让IDU白白等它;被中断压掉的指令同理
  io.HazardRegWrite :=
    ActiveInst.RegisterWrite && !ActiveInstructionTrapValid &&
      !CSRUnit.io.IrqCommit && !io.MemTrapCommit
  io.HazardMemOp := ActiveInst.MemoryValid
  io.HazardMDUHiddenWrites := MDUHiddenWriteMask
  io.PerfIdleNoInput := !io.in.valid && !PendingMDU
  io.PerfTrap := CSRUnit.io.TrapCommit
  // 转发给IDU的最终写回值(与WBU写GPR的值一致): ALU结果/snpc/CSR读出
  // (load数据在EXU阶段不可知,由MEM提供它那一级的转发)
  io.FwdData := Mux(
    PendingMDU,
    MDUResultFinal,
    Mux(
      ActiveInst.WBSelect === 2.U,
      ActiveInst.snpc,
      Mux(ActiveInst.WBSelect === 3.U, CSRUnit.io.CSR_rdata, ALUUnit.io.result)
    )
  )
  // 可转发条件: 会写rd,且不是load(load要等MEM完成)
  io.FwdReady := io.HazardValid && io.HazardRegWrite && !ActiveInst.MemoryValid &&
    (!ActiveIsMDU || MDURespValid)

  // 分支BTB只保存BTFN有可能预测taken的后向条件分支。前向表项即使命中，
  // PredictorSelect仍会选择顺序PC；将它写入只会占用容量并驱逐有用的
  // 后向分支。InstructionCommit继续保证异常/IRQ/更老MEM fault不会训练表项。
  io.BTBUpdateValid :=
    InstructionCommit && inst.IsBranch && BranchTarget < inst.pc
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
  // jal BTB更新: jal提交时写回静态目标；普通间接call(rd=x1/x5且不pop)
  // 写回本次解析出的实际JALR目标，后续稳定函数指针调用即可命中；ret及
  // 协程切换仍保存静态immediate，由预测器与动态RAS栈顶相加。
  // 不带link提示的普通间接jalr目标可能频繁变化，仍不入表。
  val IndirectCall = inst.IsJalr && RdIsLink && !RASPop
  io.JalBTBUpdateValid :=
    InstructionCommit && (inst.IsJal || RASPop || IndirectCall)
  io.JalBTBUpdatePC := inst.pc
  io.JalBTBUpdateTarget := Mux(
    inst.IsJal,
    JalTarget,
    Mux(RASPop, inst.Immediate, JalrTarget)
  )
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
