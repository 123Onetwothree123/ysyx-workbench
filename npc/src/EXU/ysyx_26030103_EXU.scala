package ysyx_26030103.exu
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._
import _root_.ysyx_26030103.config._
import _root_.ysyx_26030103.fpu._
class ysyx_26030103_EXU(
    val config: ysyx_26030103_NPCConfig = ysyx_26030103_NPCConfig()
) extends Module
    with EXUMDUQueue with EXUFPUControl with EXUPerf with EXUPredictorUpdate
    with EXUStatus {
  private[exu] val hasS = config.MMU.hasS
  private val XLEN = config.XLEN
  private val useF = config.UseF
  // RV64 W 型运算的 32 位结果按 ISA 要求符号扩展到 XLEN。
  private def sext32(value: UInt): UInt =
    if (XLEN == 32) value else Cat(Fill(XLEN - 32, value(31)), value)
  private def zext32(value: UInt): UInt =
    if (XLEN == 32) value else Cat(0.U((XLEN - 32).W), value)
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ysyx_26030103_IDUMessage(XLEN)))
    val out = Decoupled(new ysyx_26030103_EXUMessage(XLEN))
    val Redirect = Output(Bool())
    val RedirectTarget = Output(UInt(XLEN.W))
    val ExceptionTaken = Output(Bool())
    val ExceptionTarget = Output(UInt(XLEN.W))
    val Interrupt = Input(Bool())
    val TimerInterrupt = Input(Bool())
    // 无有效输入且更老指令已排空时，中断仍必须在当前架构指令边界提交；
    // 该 PC 用顶层提交态 PC，不能用可能已因预取而前移的流水级 bits。
    val IdleIRQPC = Input(UInt(XLEN.W))
    // WBU 退休事件：minstret 在退休级精确计数，EXU 提交拍不能作为依据（MEM 故障会取消退休）。
    val RetireCommit = Input(Bool())
    // 过滤掉“本指令自身写 minstret”的退休事件，只驱动 minstret 自增；RetireCommit 仍是完整退休事件。
    val MinstretRetireCommit = Input(Bool())
    // 架构 PMU 事件总线；顶层把缓存/TLB/AXI/中断等事件汇总后送入 CSR。
    val PMUEvents = Input(UInt(_root_.ysyx_26030103.common.ysyx_26030103_PMUEventID.BusWidth.W))
    // MEMBusy 只表示年长访存事务（branch/fence 只需等“可能故障的访存”）；
    // PipelineBusy 表示任意年长指令仍在 MEM 级（IRQ/CSR/异常的精确顺序）。
    val MEMBusy = Input(Bool())
    val PipelineBusy = Input(Bool())
    val MemTrapCommit = Input(Bool())
    val MemTrapCause = Input(UInt(32.W))
    val MemTrapPC = Input(UInt(XLEN.W))
    val MemTrapTval = Input(UInt(XLEN.W))

    // custom-0 仿真 halt 的提交事件；架构 EBREAK 不走这条通路。
    val SimHaltValid = Output(Bool())
    val SimHaltPC = Output(UInt(XLEN.W))
    // 精确架构 trap 提交事件（不含 MRET），供 DiffTest/调试端在正确指令边界观察 PC。
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
    // 本次 trap 写入 mtval/stval 的最终值，供顶层在 TrapCommit 拍锁存。
    val CSRStateTval = Output(UInt(XLEN.W))
    val PMPConfig = Output(Vec(16, UInt(8.W)))
    val PMPAddress = Output(Vec(16, UInt(XLEN.W)))
    val Mtime = Input(UInt(64.W))
    val Mcounteren = Output(UInt(32.W))
    val Scounteren = Output(UInt(32.W))
    // 取指 access fault 只在对应指令真正提交异常时上报。
    val FetchAccessFaultCommit = Output(Bool())
    val FetchAccessFaultPC = Output(UInt(XLEN.W))
    val FetchAccessFaultResponse = Output(UInt(2.W))
    val PerfALUOp = Output(Bool())
    val PerfMemOp = Output(Bool())
    val PerfCSROp = Output(Bool())
    val PerfBranchOp = Output(Bool())
    val PerfJalOp = Output(Bool())
    val PerfJalrOp = Output(Bool())
    val PerfMDURequest = Output(Bool())
    val PerfMDUDone = Output(Bool())
    val PerfMDUOp = Output(UInt(ysyx_26030103_MDUOp.Width.W))
    // 0=无事件，1=ALU，2=MEM，3=CSR，4=BRANCH，5=JAL，6=JALR，7=MDU。
    // 时钟沿前事件；perf_* 类型信号仍是电平信号，用于统计活跃周期。
    val PerfEventKind = Output(UInt(3.W))
    val PerfMDUActive = Output(Bool())
    val PerfMDUWait = Output(Bool())
    val PerfExecutionActive = Output(Bool())
    val PerfBranchMispredict = Output(Bool())
    val PerfBranchTaken = Output(Bool())
    val PerfFPURequest = Output(Bool())
    val PerfFPUDone = Output(Bool())
    val PerfLoadOp = Output(Bool())
    val PerfStoreOp = Output(Bool())
    val PerfAmoOp = Output(Bool())
    val PerfBranchNotTaken = Output(Bool())
    val PerfFPUActive = Output(Bool())
    val PerfFenceOp = Output(Bool())
    val PerfRvcInst = Output(Bool())
    val PerfSystemOp = Output(Bool())
    val PerfInterruptTaken = Output(Bool())
    val PerfCSRRead = Output(Bool())
    val PerfCSRWrite = Output(Bool())
    val PerfMulActive = Output(Bool())
    val PerfDivActive = Output(Bool())
    val PerfRedirectBranch = Output(Bool())
    val PerfRedirectFenceI = Output(Bool())
    val PerfRedirectTrap = Output(Bool())
    val PerfRedirectMret = Output(Bool())
    val PerfFPUStall = Output(Bool())
    val PerfIllegalInst = Output(Bool())
    val PerfFPUDivSqrt = Output(Bool())
    val PerfFPUFma = Output(Bool())
    val PerfFPUException = Output(Bool())
    val PerfCSRIllegal = Output(Bool())
    val PerfIRQPending = Output(Bool())
    val PerfDivIterations = Output(UInt(16.W))
    val PerfDivEarlyOut = Output(Bool())
    val PerfDivDone = Output(Bool())

    val FenceIFlush = Output(Bool())
    // 任一 CSR 指令真正提交（含 sfence.vma）；顶层用它保守地让 LR reservation 失效。
    val CSRCommit = Output(Bool())

    // 统一冲刷输出: 分支/跳转/fence.i 重定向或异常提交时冲刷 IFU 内部状态和
    // IFU→IDU 流水寄存器;MEM 故障的冲刷由 MEM 发起,经 CSR 后门走 ExceptionTaken。
    val FlushIF = Output(Bool())

    // 给IDU做数据冒险检测用(EXU单拍级,指令就是in.bits本身)
    val HazardValid = Output(Bool())
    val HazardRd = Output(UInt(5.W))
    val HazardRegWrite = Output(Bool())
    val HazardMemOp = Output(Bool())
    // 队首之外已排队 MDU 指令的写入；IDU 不能转发这些值，必须阻塞匹配的消费者。
    val HazardMDUHiddenWrites = Output(UInt(32.W))
    val PerfIdleNoInput = Output(Bool())
    val PerfTrap = Output(Bool())
    // 转发给IDU: EX阶段生产者的最终写回值,以及该值当前是否可用于转发
    val ForwardData = Output(UInt(XLEN.W))
    val ForwardReady = Output(Bool())
    // 浮点寄存器写口/冒险/转发（与 GPR 端口独立）。
    val HazardFPValid = Output(Bool())
    val HazardFPRd = Output(UInt(5.W))
    val HazardFPRegWrite = Output(Bool())
    val ForwardFPData = Output(UInt(64.W))
    val ForwardFPReady = Output(Bool())
    // 当前 frm 给 IDU 解析动态舍入；Fcsr 是 fflags/frm 的实时 8 位视图（WBU 退休沿锁存供 DiffTest）。
    val CSRFrm = Output(UInt(3.W))
    val CSRFcsr = Output(UInt(8.W))
    // 浮点运算提交时累积 fflags（EXU 提交点）；WBU 退休任意 FP 指令时置 mstatus.FS=Dirty。
    val FPStateDirtyCommit = Input(Bool())
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
    val RASPushAddress = Output(UInt(XLEN.W))
    val RASPopValid = Output(Bool())
  })
  // 保留 CSR 快照端口，供叶子 Verilator 探针读取。
  dontTouch(io.CSRStateMepc)
  val ALUUnit = Module(ysyx_26030103_ALU(config))
  val CSRUnit = Module(new ysyx_26030103_CSR(
    config.MMU, XLEN, config.UseA, config.UseC, config.UseF, config.UseD,
    config.UseM, config.RISCVArchPMU, config.RiscvArchPMUZihpm,
    config.RiscvArchPMUSscofpmf))
  CSRUnit.io.clock := clock
  CSRUnit.io.reset := reset.asBool
  CSRUnit.io.MinstretRetireCommit := io.MinstretRetireCommit
  CSRUnit.io.PMUEvents := io.PMUEvents
  CSRUnit.io.Interrupt := io.Interrupt
  CSRUnit.io.TimerInterrupt := io.TimerInterrupt
  val branchComparatorUnit = Module(new ysyx_26030103_BranchComparator(XLEN))
  val inst = io.in.bits
  // FPU 是 EXU 内的独立执行单元：FPU 指令原地等响应、不消耗后续指令；FPPending/FPInstReg 存唯一在途指令。
  val FPUUnitOpt =
    if (useF) Some(Module(ysyx_26030103_FPU(config))) else None
  val FPURequest = Wire(Decoupled(new ysyx_26030103_FPURequest(config.FPULen)))
  val FPPending = RegInit(false.B)
  val FPInstReg = Reg(chiselTypeOf(io.in.bits))
  val FPResponseValid = FPUUnitOpt.map(_.io.response.valid).getOrElse(false.B)
  val FPResponseBits = FPUUnitOpt
    .map(_.io.response.bits)
    .getOrElse(0.U.asTypeOf(new ysyx_26030103_FPUResponse(config.FPULen)))
  val FPResponseFire = FPPending && FPResponseValid && io.out.ready && !io.MemTrapCommit
  // M 扩展关闭时这些信号折叠为常量，MDU 模块和顺序队列只在 elaboration 期的
  // UseM 分支中生成，避免 RISCV_M=n 时仍生成完整乘除法器。
  val PendingMDU = WireDefault(false.B)
  val PendingMDUInst = WireDefault(0.U.asTypeOf(chiselTypeOf(io.in.bits)))
  val MDURequestReady = WireDefault(false.B)
  val MDURequestFire = WireDefault(false.B)
  val MDUResponseFire = WireDefault(false.B)
  val MDUResponseValid = WireDefault(false.B)
  val MDUResponseResult = WireDefault(0.U(XLEN.W))
  val MDUPerfDivIterations = WireDefault(0.U(16.W))
  val MDUPerfDivEarlyOut = WireDefault(false.B)
  val MDUHiddenWriteMask = WireDefault(0.U(32.W))
  // RV64 W 型 M 扩展指令：MULW/DIVW/REMW 先符号扩展低 32 位操作数，
  // DIVUW/REMUW 先零扩展；结果再由 sext32 把低 32 位符号扩展回 XLEN。
  val IsWordMDU = ysyx_26030103_MDUOp.IsWordOp(inst.MDUOp)
  val MDUBaseOp = ysyx_26030103_MDUOp.BaseOp(inst.MDUOp)
  val WordMDUUnsigned = IsWordMDU && (MDUBaseOp === ysyx_26030103_MDUOp.DIVU ||
    MDUBaseOp === ysyx_26030103_MDUOp.REMU)
  val MDUWordLHS =
    if (XLEN == 32) inst.ALU_A
    else Mux(WordMDUUnsigned, zext32(inst.ALU_A(31, 0)),
      sext32(inst.ALU_A(31, 0)))
  val MDUWordRHS =
    if (XLEN == 32) inst.ALU_B
    else Mux(WordMDUUnsigned, zext32(inst.ALU_B(31, 0)),
      sext32(inst.ALU_B(31, 0)))
  val MDURequestLHS = if (XLEN == 32) inst.ALU_A
    else Mux(IsWordMDU, MDUWordLHS, inst.ALU_A)
  val MDURequestRHS = if (XLEN == 32) inst.ALU_B
    else Mux(IsWordMDU, MDUWordRHS, inst.ALU_B)
  val MDURequestOp = if (XLEN == 32) inst.MDUOp else Mux(IsWordMDU, MDUBaseOp, inst.MDUOp)
  // 上游随指令传来的异常标记(IFU取指错cause=1/IDU非法指令cause=2)
  val upstreamException = inst.ExceptionValid

  // IALIGN=32 时只有实际转移的 branch/JAL/JALR 检查目标低两位；启用 C 后
  // IALIGN=16，目标只需 bit0=0。JALR 在两种配置下都只清 bit0。
  ALUUnit.io.A := inst.ALU_A
  ALUUnit.io.B := inst.ALU_B
  ALUUnit.io.ALUCtrl := inst.ALUCtrl
  branchComparatorUnit.io.A := inst.BranchA
  branchComparatorUnit.io.B := inst.BranchB
  branchComparatorUnit.io.Funct3 := inst.BranchFunct3
  branchComparatorUnit.io.IsBranch := inst.IsBranch
  val branchTaken = inst.IsBranch && branchComparatorUnit.io.Taken
  val branchTarget = inst.PC + inst.Immediate
  val jalTarget = ALUUnit.io.result
  val jalrTarget = Cat(ALUUnit.io.result(XLEN - 1, 1), 0.U(1.W))
  val controlTransferTaken = branchTaken || inst.IsJal || inst.IsJalr
  val controlTransferTarget = Mux(
    inst.IsJal,
    jalTarget,
    Mux(inst.IsJalr, jalrTarget, branchTarget)
  )
  val instructionTargetAlignMask =
    if (config.UseC) 1.U(XLEN.W) else 3.U(XLEN.W)
  val instructionAddressMisaligned =
    !upstreamException && controlTransferTaken &&
      (controlTransferTarget & instructionTargetAlignMask) =/= 0.U
  val instructionTrapValid = upstreamException || instructionAddressMisaligned
  val isMemoryForCommit = inst.MemoryValid && !instructionTrapValid
  val IsMDUInstruction =
    (if (config.UseM) inst.IsMDU else false.B) && !instructionTrapValid &&
      !io.MemTrapCommit

  // 带异常标记或动态发现异常的指令只提交异常，不产生访存/CSR/GPR 写、重定向、
  // 预测器训练副作用；CSR/异常/IRQ/FPU 等年长 MEM 越过精确边界，branch/jal 只等年长访存
  // （否则故障前会留下不可回滚的 BTB/RAS 状态）。
  val requiresPipelineDrain =
    inst.IsCSRRW || inst.IsCSRRS || inst.IsCSRRC || inst.IsEcall || inst.IsEbreak ||
      inst.IsSimHalt || inst.IsMret ||
      (if (hasS) inst.IsSret || inst.IsSfenceVma else false.B) ||
      (if (useF) inst.IsFPU else false.B) ||
      instructionTrapValid
  val requiresMemoryDrain =
    inst.IsFenceI || inst.IsFence || inst.IsBranch || inst.IsJal || inst.IsJalr
  // MEMBusy 在顶层是 PipelineBusy 的子集；这里仍取或作防御，叶子 EXU 回归可只驱动 MEMBusy。
  val olderPipelineBusy = io.PipelineBusy || io.MEMBusy || FPPending
  // IRQ 是动态精确副作用：任意年长 MEM 指令未越过时，不能在当前边界提交 IRQ。
  val BlockIRQForPipeline = olderPipelineBusy && CSRUnit.io.IRQPending
  val blockForOlder =
    (olderPipelineBusy && requiresPipelineDrain) ||
      (io.MEMBusy && requiresMemoryDrain) || BlockIRQForPipeline

  // 过去 IRQ 提交只依赖 io.in.fire，取指不返回就没有机会；仅在 EXU 输入、MDU
  // 队列和更老流水级都为空时，才用顶层精确 PC 建边界（MemTrap 属于更老指令，优先）。
  val IdleIRQCommit =
    !io.in.valid && !PendingMDU && !FPPending && !olderPipelineBusy &&
      CSRUnit.io.IRQPending && !io.MemTrapCommit

  // IRQ 可在指令边界结束当前输入：消耗该 M 输入但不启动 MDU 请求；有更老 MDU 任务时保持输入到队列排空。
  val MDUInputBlockedByIRQ =
    IsMDUInstruction && CSRUnit.io.IRQPending && !io.MemTrapCommit
  // 未发射的 M 指令要在更老 MEM 全部排空后才能被消耗并在 IRQ 后重放，
  // 否则 MDU 专用 ready 路径会绕过 BlockIRQForPipeline，破坏精确陷阱顺序。
  val MDUInputKilledByIRQ =
    MDUInputBlockedByIRQ && !PendingMDU && !olderPipelineBusy

  mduQueueBody()

  // FPU 请求只在更老 MEM 排空后发射，避免 fflags 在更老访存故障之前提交；
  // FPURequest.ready 来自后端，支持 DIV/SQRT 多周期。
  val IsFPUInstruction =
    (if (useF) inst.IsFPU && !instructionTrapValid else false.B)
  val FPUFlush = WireDefault(false.B)
  val FPURequestFire = FPURequest.valid && FPURequest.ready
  fpuControlBody()

  io.in.ready := Mux(
    IsMDUInstruction,
    Mux(MDUInputKilledByIRQ, true.B, MDURequestReady) && !FPPending,
    Mux(
      IsFPUInstruction,
      !FPPending && !PendingMDU && FPURequest.ready && !blockForOlder &&
        !io.MemTrapCommit,
      // 待处理的MDU/FPU结果会占用本周期唯一的输出槽位。更年轻的普通指令
      // 必须留在 IDU->EXU 寄存器中，直到结果发出。
      !PendingMDU && !FPPending && io.out.ready && !blockForOlder
    )
  )
  io.out.valid := Mux(
    PendingMDU,
    MDUResponseValid && !io.MemTrapCommit && !BlockIRQForPipeline,
    Mux(FPPending, FPResponseValid && !io.MemTrapCommit,
      io.in.valid && !IsMDUInstruction && !IsFPUInstruction && !PendingMDU &&
        !FPPending && !blockForOlder)
  )

  val activeInst = Wire(chiselTypeOf(io.in.bits))
  activeInst := inst
  when(PendingMDU) {
    activeInst := PendingMDUInst
  }.elsewhen(FPPending) {
    activeInst := FPInstReg
  }
  val ActiveIsMDU = if (config.UseM) activeInst.IsMDU else false.B
  val ActiveMDUOp =
    if (config.UseM) activeInst.MDUOp else 0.U(ysyx_26030103_MDUOp.Width.W)
  val activeInstructionTrapValid = Mux(
    PendingMDU,
    activeInst.ExceptionValid,
    Mux(FPPending, FPInstReg.ExceptionValid, instructionTrapValid)
  )
  // InstructionAddressMisaligned 由当前输入 inst 计算；PendingMDU/FPPending 时
  // activeInst 是更老的排队指令，不能误用年轻 inst 的 misaligned 信号。
  val activeInstructionAddressMisaligned =
    Mux(PendingMDU || FPPending, false.B, instructionAddressMisaligned)
  // Decoupled.bits 在 valid=0 时没有语义；空流水 IRQ 会独立拉高 CSR Enable，
  // 所以“当前指令”控制必须由 valid 门控，不能让上一个 MRET/CSR 的 bits 与 IRQ 同时生效。
  val ValidInstructionForCSR =
    io.in.valid && !PendingMDU && !FPPending && !instructionTrapValid
  CSRUnit.io.IsCSRRW := ValidInstructionForCSR && inst.IsCSRRW
  CSRUnit.io.IsCSRRS := ValidInstructionForCSR && inst.IsCSRRS
  CSRUnit.io.IsCSRRC := ValidInstructionForCSR && inst.IsCSRRC
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
    Mux(IdleIRQCommit, io.IdleIRQPC, inst.PC)
  )
  CSRUnit.io.PC := TrapBoundaryPC
  // 非法指令的 mtval 写原始指令编码：压缩指令零扩展 16 位，32 位指令写 32 位原值。
  // 其余：load/store 用故障地址，misaligned 用目标地址，取指错/breakpoint 回退 PC，ecall/中断写 0。
  val activeIllegalCompressed =
    activeInst.ExceptionValid && activeInst.ExceptionCause === 2.U &&
      activeInst.IsCompressed
  val activeInstructionVisible = io.in.valid || PendingMDU
  // 上游异常优先于指令形状：取指故障的访存指令仍带 MemoryValid，但 mtval 必须是故障取指
  // 地址（C 模式 32 位跨字指令可能在第二字故障，PC/sepc 仍是首字），用 PredTarget 携带该 VAddr。
  val activeFetchFault =
    activeInst.ExceptionValid && activeInst.ExceptionCause =/= 2.U
  val activeTrapTval = Mux(
    activeInst.ExceptionValid,
    Mux(
      activeFetchFault,
      activeInst.PredTarget,
      Mux(activeInst.ExceptionCause === 2.U, activeInst.Instruction, activeInst.PC)
    ),
    Mux(
      activeInstructionAddressMisaligned,
      controlTransferTarget,
      Mux(activeInst.MemoryValid, ALUUnit.io.result, activeInst.PC)
    )
  )
  val activeTrapTvalValid = activeInstructionVisible &&
    (activeInst.ExceptionValid || activeIllegalCompressed ||
      activeInst.MemoryValid || activeInstructionAddressMisaligned)
  CSRUnit.io.TrapTval := Mux(io.MemTrapCommit, io.MemTrapTval, activeTrapTval)
  CSRUnit.io.TrapTvalValid := io.MemTrapCommit || activeTrapTvalValid
  // fence.i 也要重定向: 目标是自己的 snpc(fence.i+4)，借此冲掉流水线里比它年轻、可能过时的指令。
  // 实际下一 PC: jalr 低位清零由 jalrTarget 给出；分支 not-taken 与 fence.i 都回 snpc。
  val ActualNextPC = Mux(
    inst.IsJal,
    jalTarget,
    Mux(
      inst.IsJalr,
      jalrTarget,
      Mux(
        branchTaken,
        branchTarget,
        inst.snpc
      )
    )
  )
  val CommitNextPC =
    if (hasS) Mux(inst.IsMret || inst.IsSret,
      CSRUnit.io.ExceptionTarget, ActualNextPC)
    else Mux(inst.IsMret, CSRUnit.io.ExceptionTarget, ActualNextPC)
  // 预测下一PC: IFU用BTB(分支BTFN+独立jal BTB)给出,随指令传到此;未命中=顺序=snpc
  val PredNextPC = Mux(inst.PredTaken, inst.PredTarget, inst.snpc)
  // 预测错误检查: 实际与预测下一 PC 不一致则冲刷并重定向
  // (jal: jal BTB 命中免冲刷; ret: Ret 表项+RAS 栈顶; 其他 jalr: 无预测=>必然判错)。
  val mispredict = ActualNextPC =/= PredNextPC
  // MRET 下一 PC 是 mepc，不参与顺序 PC 比较（陈旧 BTB 表项会让它走 Mispredict 产生伪 Redirect）；
  // SFENCE.VMA/satp 写换地址空间后必须像 fence.i 一样冲刷并从 snpc 重取，丢弃旧上下文预取的年轻指令。
  val redirect =
    (mispredict || inst.IsFenceI ||
      (if (hasS) inst.IsSfenceVma || CSRUnit.io.FlushTLB else false.B)) &&
      !instructionTrapValid && !inst.IsMret &&
      (if (hasS) !inst.IsSret else true.B)
  // CSR 提交只认本拍 fire；MEM 故障走独立 MemTrap 后门，当拍被冲刷的 csrrw/mret 不产生
  // CSR 副作用、异常目标不被 mret 劫持。已使能 IRQ 可在 load/store 前边界提交并压掉该访存。
  val InstructionCSRCommit = io.in.fire && !PendingMDU &&
    (!isMemoryForCommit || CSRUnit.io.IRQPending) && !io.MemTrapCommit
  CSRUnit.io.Enable := InstructionCSRCommit || IdleIRQCommit
  CSRUnit.io.MemTrap := io.MemTrapCommit
  CSRUnit.io.TrapValid :=
    io.in.valid && !PendingMDU && !FPPending && instructionTrapValid
  CSRUnit.io.TrapCause := Mux(
    io.MemTrapCommit,
    io.MemTrapCause,
    Mux(
      instructionAddressMisaligned,
      0.U(32.W),
      Cat(0.U((XLEN - 4).W), inst.ExceptionCause)
    )
  )

  // 当前 EXU 指令只有真正被接受、且未被更老访存故障或中断抢占才算提交；
  // 预测器和当前指令的控制副作用统一使用它。
  val instructionCommit =
    io.in.fire && !PendingMDU && !instructionTrapValid && !io.MemTrapCommit &&
      !CSRUnit.io.IRQCommit

  statusBody()

  io.out.bits.Instruction := activeInst.Instruction
  // 同步异常指令不退休；IRQ抢占的指令会从mepc重做，也不退休。
  // load/store先携带退休意图进入MEM，若总线故障则由LSU清除。
  io.out.bits.Retire := !activeInstructionTrapValid && !activeInst.IsEcall &&
    !activeInst.IsEbreak && !activeInst.IsSimHalt && !CSRUnit.io.IRQCommit &&
    !io.MemTrapCommit
  // 只有真正提交的 CSR 写才打标记；该指令随后仍可正常退休并计入 PMU。
  io.out.bits.WritesMinstret :=
    instructionCommit && CSRUnit.io.MinstretWriteCommit
  io.out.bits.PC := activeInst.PC
  io.out.bits.snpc := activeInst.snpc
  // Pending MDU 不可能是控制转移；其它指令用本拍实际后继 PC，MRET 用 mepc。
  io.out.bits.NextPC :=
    Mux(PendingMDU || FPPending, activeInst.snpc, CommitNextPC)
  io.out.bits.Rd := activeInst.Rd
  // 带异常标记的指令不得写回GPR;被中断压掉的指令(IrqCommit)也不得写回
  io.out.bits.RegisterWrite :=
    activeInst.RegisterWrite && !activeInstructionTrapValid &&
      !CSRUnit.io.IRQCommit && !io.MemTrapCommit
  io.out.bits.WBSelect := activeInst.WBSelect
  // RV64 W 型 M 扩展结果只保留低 32 位并按 bit31 符号扩展。
  val MDUResultFinal =
    if (XLEN == 64)
      Mux(ysyx_26030103_MDUOp.IsWordOp(activeInst.MDUOp),
        sext32(MDUResponseResult(31, 0)), MDUResponseResult)
    else MDUResponseResult
  io.out.bits.ALUResult := Mux(PendingMDU, MDUResultFinal,
    Mux(FPPending, FPResponseBits.result(XLEN - 1, 0), ALUUnit.io.result))
  io.out.bits.VAddress := ALUUnit.io.result
  io.out.bits.LoadData := 0.U(XLEN.W) // 由MEM在访存完成后填写
  io.out.bits.CSRReadData := CSRUnit.io.CSR_rdata
  io.out.bits.CSRStateMstatus := CSRUnit.io.StateMstatus
  io.out.bits.CSRStateMtvec := CSRUnit.io.StateMtvec
  io.out.bits.CSRStateMepc := CSRUnit.io.StateMepc
  io.out.bits.CSRStateMcause := CSRUnit.io.StateMcause
  io.out.bits.MemoryValid :=
    activeInst.MemoryValid && !activeInstructionTrapValid &&
      !CSRUnit.io.IRQCommit && !io.MemTrapCommit
  io.out.bits.MemoryWrite :=
    activeInst.MemoryWrite && !activeInstructionTrapValid &&
      !CSRUnit.io.IRQCommit && !io.MemTrapCommit
  io.out.bits.WidthSelect := activeInst.WidthSelect
  io.out.bits.LoadSigned := activeInst.LoadSigned
  io.out.bits.StoreData := activeInst.StoreData
  // F/D：FPU 结果直接从这里写入 f 寄存器；FLW/FLD 的 FPWriteData 由
  // LSU 在 load 完成时按 FP64 做 NaN-boxing。
  io.out.bits.FPRegisterWrite :=
    activeInst.FPRegisterWrite && !activeInstructionTrapValid &&
      !CSRUnit.io.IRQCommit && !io.MemTrapCommit
  io.out.bits.FPWriteData :=
    Mux(FPPending, FPResponseBits.result, activeInst.FPWriteData)
  io.out.bits.FP64 := activeInst.FP64
  // A 扩展：把译码得到的原子标记/操作号透传到 LSU。
  io.out.bits.IsAtomic := activeInst.IsAtomic
  io.out.bits.AtomicOp := activeInst.AtomicOp

  perfBody()

  // 这些是“当前EXU指令”的副作用。中断接受或更老访存故障提交时，
  // 当前指令会被重做/丢弃，不能训练预测器，也不能产生重定向或FenceI刷新。
  io.FenceIFlush := instructionCommit && inst.IsFenceI
  // CSR 指令提交视为上下文/地址空间可能切换，保守地清除 LR reservation。
  io.CSRCommit := instructionCommit &&
    (inst.IsCSRRW || inst.IsCSRRS || inst.IsCSRRC ||
      (if (hasS) inst.IsSfenceVma else false.B))
  val localFlush =
    (instructionCommit && redirect) || CSRUnit.io.ExceptionTaken
  io.FlushIF := localFlush
  // 更老指令的精确 trap/重定向必须取消在途 FPU 请求（尤其 DIV/SQRT）。
  FPUFlush := localFlush

  // FPU 运算在 EXU 提交点累积 fflags。此时所有更老 MEM 指令已排空
  // （RequiresPipelineDrain），因此不会出现“更老访存随后才 trap”的情况。
  val FPFlagsCommit =
    FPPending && FPResponseFire && !CSRUnit.io.IRQCommit
  CSRUnit.io.FPCommit := FPFlagsCommit
  CSRUnit.io.FPFlags := FPResponseBits.fflags
  CSRUnit.io.FPStateDirtyCommit := io.FPStateDirtyCommit
  io.CSRFrm := CSRUnit.io.Frm
  io.CSRFcsr := CSRUnit.io.Fcsr

  io.HazardValid := io.in.valid || PendingMDU || FPPending
  io.HazardRd := activeInst.Rd
  // 带异常标记的指令不会真正写回,不应让IDU白白等它;被中断压掉的指令同理
  io.HazardRegWrite :=
    activeInst.RegisterWrite && !activeInstructionTrapValid &&
      !CSRUnit.io.IRQCommit && !io.MemTrapCommit
  io.HazardMemOp := activeInst.MemoryValid
  io.HazardMDUHiddenWrites := MDUHiddenWriteMask
  io.PerfIdleNoInput := !io.in.valid && !PendingMDU
  io.PerfTrap := CSRUnit.io.TrapCommit
  // 转发给IDU的最终写回值(与WBU写GPR的值一致): ALU结果/snpc/CSR读出
  // (load数据在EXU阶段不可知,由MEM提供它那一级的转发)
  io.ForwardData := Mux(
    PendingMDU,
    MDUResultFinal,
    Mux(
      FPPending,
      FPResponseBits.result(XLEN - 1, 0),
      Mux(
        activeInst.WBSelect === 2.U,
        activeInst.snpc,
        Mux(activeInst.WBSelect === 3.U, CSRUnit.io.CSR_rdata, ALUUnit.io.result)
      )
    )
  )
  // 可转发条件: 会写 rd 且不是 load（load 要等 MEM）；FPU 运算必须等响应 valid，
  // 不能把未发射/未返回的占位 ALU 结果前递给紧随其后的分支或整数消费者。
  io.ForwardReady := io.HazardValid && io.HazardRegWrite && !activeInst.MemoryValid &&
    (!ActiveIsMDU || MDUResponseValid) &&
    (!activeInst.IsFPU || (FPPending && FPResponseValid))
  // 浮点写口转发: Fcvt/Fmv/比较写 GPR 时 HazardFPRegWrite=0；FLW/FLD 数据在 MEM 才可用；
  // FPU 运算未返回（甚至未发射）时不能声称可转发，否则 IDU 会把占位 FPWriteData(0) 当前递。
  io.HazardFPValid := io.in.valid || PendingMDU || FPPending
  io.HazardFPRd := activeInst.Rd
  io.HazardFPRegWrite :=
    activeInst.FPRegisterWrite && !activeInstructionTrapValid &&
      !CSRUnit.io.IRQCommit && !io.MemTrapCommit
  io.ForwardFPData := Mux(FPPending, FPResponseBits.result, activeInst.FPWriteData)
  io.ForwardFPReady := io.HazardFPValid && io.HazardFPRegWrite &&
    !activeInst.MemoryValid && FPPending && FPResponseValid

  predictorUpdateBody()
}
