package ysyx_26030103.exu
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._
import _root_.ysyx_26030103.config._
import _root_.ysyx_26030103.fpu._
class ysyx_26030103_EXU(
    val config: ysyx_26030103_NPCConfig = ysyx_26030103_NPCConfig()
) extends Module {
  private val hasS = config.MMU.hasS
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
    // 当EXU没有有效输入、且所有更老指令都已排空时，中断仍必须在当前
    // 架构指令边界提交。该PC由顶层的提交态PC提供，不能使用可能已因
    // 预取而前移的任意流水级bits。
    val IdleIRQPC = Input(UInt(XLEN.W))
    // WBU 退休事件：minstret 在退休级精确计数，EXU 的提交拍不能再作为
    // 计数依据（普通 load/store 不在 EXU 访问 CSR，且 MEM 故障会取消退休）。
    val RetireCommit = Input(Bool())
    // 过滤掉“本指令自身写 minstret”的退休事件；只驱动 minstret 自增，
    // RetireCommit 仍作为完整的退休/PMU Instructions 事件。
    val MinstretRetireCommit = Input(Bool())
    // 架构 PMU 事件总线；顶层把缓存/TLB/AXI/中断等事件汇总后送入 CSR。
    val PMUEvents = Input(UInt(_root_.ysyx_26030103.common.ysyx_26030103_PMUEventID.BusWidth.W))
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
    // 这是一个时钟沿前事件；现有的perf_*类型信号仍作为电平信号，
    // 用于统计活跃周期。
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
    val ForwardData = Output(UInt(XLEN.W))
    val ForwardReady = Output(Bool())
    // 浮点寄存器写口/冒险/转发（与 GPR 端口独立）。
    val HazardFPValid = Output(Bool())
    val HazardFPRd = Output(UInt(5.W))
    val HazardFPRegWrite = Output(Bool())
    val ForwardFPData = Output(UInt(64.W))
    val ForwardFPReady = Output(Bool())
    // 当前 frm 给 IDU 解析动态舍入；Fcsr 是 fflags/frm 的实时 8 位视图，
    // 顶层在 WBU 退休沿锁存后供 DiffTest 比较。
    val CSRFrm = Output(UInt(3.W))
    val CSRFcsr = Output(UInt(8.W))
    // 浮点运算提交时累积 fflags（EXU 提交点；FPU 运算不会在 MEM 故障）。
    // WBU 退休任意 FP 指令时置 mstatus.FS=Dirty（含 FLW/FSW/FLD/FSD）。
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
  val aluUnit = Module(ysyx_26030103_ALU(config))
  val csrUnit = Module(new ysyx_26030103_CSR(
    config.MMU, XLEN, config.UseA, config.UseC, config.UseF, config.UseD,
    config.UseM, config.RiscvArchPMU, config.RiscvArchPMUZihpm,
    config.RiscvArchPMUSscofpmf))
  csrUnit.io.clk := clock
  csrUnit.io.rst := reset.asBool
  csrUnit.io.MinstretRetireCommit := io.MinstretRetireCommit
  csrUnit.io.PMUEvents := io.PMUEvents
  csrUnit.io.Interrupt := io.Interrupt
  csrUnit.io.TimerInterrupt := io.TimerInterrupt
  val branchComparatorUnit = Module(new ysyx_26030103_BranchComparator(XLEN))
  val inst = io.in.bits
  // FPU 是 EXU 内的独立执行单元：FPU 指令在 EXU 原地等待响应，期间
  // 不消耗后续指令。FpPending/FpInstReg 保存唯一在途 FP 指令。
  val fpuUnitOpt =
    if (useF) Some(Module(ysyx_26030103_FPU(config))) else None
  val fpuRequest = Wire(Decoupled(new ysyx_26030103_FPURequest(config.FPULen)))
  val fpPending = RegInit(false.B)
  val fpInstReg = Reg(chiselTypeOf(io.in.bits))
  val fpResponseValid = fpuUnitOpt.map(_.io.resp.valid).getOrElse(false.B)
  val fpResponseBits = fpuUnitOpt
    .map(_.io.resp.bits)
    .getOrElse(0.U.asTypeOf(new ysyx_26030103_FPUResponse(config.FPULen)))
  val fpResponseFire = fpPending && fpResponseValid && io.out.ready && !io.MemTrapCommit
  // M扩展关闭时，这些信号全部折叠为常量；MDU模块和顺序
  // 队列只在elaboration期的UseM分支中生成。这避免了RISCV_M=n时
  // 仅靠译码器“不使用”但仍然生成完整乘除法器的问题。
  val pendingMDU = WireDefault(false.B)
  val pendingMDUInst = WireDefault(0.U.asTypeOf(chiselTypeOf(io.in.bits)))
  val mduRequestReady = WireDefault(false.B)
  val mduRequestFire = WireDefault(false.B)
  val mduResponseFire = WireDefault(false.B)
  val mduResponseValid = WireDefault(false.B)
  val mduResponseResult = WireDefault(0.U(XLEN.W))
  val mduPerfDivIterations = WireDefault(0.U(16.W))
  val mduPerfDivEarlyOut = WireDefault(false.B)
  val mduHiddenWriteMask = WireDefault(0.U(32.W))
  // RV64 W 型 M 扩展指令映射到对应的 XLEN 基础运算：
  //   MULW/DIVW/REMW 先符号扩展低 32 位操作数；
  //   DIVUW/REMUW 先零扩展低 32 位操作数；
  // 结果再由 Sext32 把低 32 位符号扩展回 XLEN。
  val isWordMDU = ysyx_26030103_MDUOp.IsWordOp(inst.MDUOp)
  val wordMDUUnsigned = inst.MDUOp === ysyx_26030103_MDUOp.DIVUW ||
    inst.MDUOp === ysyx_26030103_MDUOp.REMUW
  val mduBaseOp = MuxLookup(inst.MDUOp, inst.MDUOp)(
    Seq(
      ysyx_26030103_MDUOp.MULW -> ysyx_26030103_MDUOp.MUL,
      ysyx_26030103_MDUOp.DIVW -> ysyx_26030103_MDUOp.DIV,
      ysyx_26030103_MDUOp.DIVUW -> ysyx_26030103_MDUOp.DIVU,
      ysyx_26030103_MDUOp.REMW -> ysyx_26030103_MDUOp.REM,
      ysyx_26030103_MDUOp.REMUW -> ysyx_26030103_MDUOp.REMU
    )
  )
  val mduWordLHS =
    if (XLEN == 32) inst.ALU_A
    else Mux(wordMDUUnsigned, zext32(inst.ALU_A(31, 0)),
      sext32(inst.ALU_A(31, 0)))
  val mduWordRHS =
    if (XLEN == 32) inst.ALU_B
    else Mux(wordMDUUnsigned, zext32(inst.ALU_B(31, 0)),
      sext32(inst.ALU_B(31, 0)))
  val mduRequestLHS = if (XLEN == 32) inst.ALU_A
    else Mux(isWordMDU, mduWordLHS, inst.ALU_A)
  val mduRequestRHS = if (XLEN == 32) inst.ALU_B
    else Mux(isWordMDU, mduWordRHS, inst.ALU_B)
  val mduRequestOp = if (XLEN == 32) inst.MDUOp else Mux(isWordMDU, mduBaseOp, inst.MDUOp)
  // 上游随指令传来的异常标记(IFU取指错cause=1/IDU非法指令cause=2)
  val upstreamException = inst.ExceptionValid

  // IALIGN=32 时只有实际发生转移的 branch/JAL/JALR 才检查目标低两位；
  // 启用 C 扩展后 IALIGN=16，目标只需 2 字节对齐（bit0=0），bit1 可以
  // 为 1。JALR 在两种配置下都只清 bit0。
  aluUnit.io.A := inst.ALU_A
  aluUnit.io.B := inst.ALU_B
  aluUnit.io.ALUCtrl := inst.ALUCtrl
  branchComparatorUnit.io.A := inst.BranchA
  branchComparatorUnit.io.B := inst.BranchB
  branchComparatorUnit.io.Funct3 := inst.BranchFunct3
  branchComparatorUnit.io.IsBranch := inst.IsBranch
  val branchTaken = inst.IsBranch && branchComparatorUnit.io.Taken
  val branchTarget = inst.pc + inst.Immediate
  val jalTarget = aluUnit.io.result
  val jalrTarget = Cat(aluUnit.io.result(XLEN - 1, 1), 0.U(1.W))
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
  val isMDUInstruction =
    (if (config.UseM) inst.IsMDU else false.B) && !instructionTrapValid &&
      !io.MemTrapCommit

  // 带标记或EXU动态发现异常的指令只做异常提交，不得产生
  // 访存/CSR写/GPR写/重定向/预测器训练等副作用。
  // CSR/异常/IRQ会立即改变架构状态，必须等任意年长MEM级指令
  // 越过该精确边界。branch/JAL/JALR只需等待年长访存：普通ALU
  // 不会故障，不应该让控制转移多停一拍；但年长load/store仍可能
  // 提交精确故障，在它完成前不能留下无法回滚的BTB/RAS状态。
  // FPU 运算在 EXU 提交点累积 fflags，必须在所有可能故障的更老 MEM
  // 指令排空后才能提交；因此 FPU 指令也要求 pipeline drain。
  val requiresPipelineDrain =
    inst.IsCSRrw || inst.IsCSRrs || inst.IsCSRrc || inst.IsEcall || inst.IsEbreak ||
      inst.IsSimHalt || inst.IsMret ||
      (if (hasS) inst.IsSret || inst.IsSfenceVma else false.B) ||
      (if (useF) inst.IsFPU else false.B) ||
      instructionTrapValid
  val requiresMemoryDrain =
    inst.IsFenceI || inst.IsFence || inst.IsBranch || inst.IsJal || inst.IsJalr
  // MEMBusy在顶层是PipelineBusy的子集。这里仍取或作为防御，
  // 也让叶子EXU回归可以只驱动MEMBusy来表示年长访存。
  val olderPipelineBusy = io.PipelineBusy || io.MEMBusy || fpPending
  // IRQ是动态精确副作用：只要任意年长MEM级指令未越过，
  // 就不能在当前指令边界提交IRQ。
  val blockIRQForPipeline = olderPipelineBusy && csrUnit.io.IRQPending
  val blockForOlder =
    (olderPipelineBusy && requiresPipelineDrain) ||
      (io.MEMBusy && requiresMemoryDrain) || blockIRQForPipeline

  // 过去IRQ提交完全依赖io.in.fire，取指若长期不返回便没有提交机会。
  // 仅在EXU输入、MDU队列及所有更老流水级都为空时，才可使用顶层给出的
  // 精确架构PC建立一个空流水指令边界。MemTrap属于更老指令，优先级更高。
  val idleIRQCommit =
    !io.in.valid && !pendingMDU && !fpPending && !olderPipelineBusy &&
      csrUnit.io.IRQPending && !io.MemTrapCommit

  val blockPendingMDUForMEM =
    olderPipelineBusy && csrUnit.io.IRQPending
  // IRQ可能在指令边界结束当前输入。应消耗该M扩展输入以便提交中断，但不启动
  // MDU请求；一旦存在更老的MDU任务，就保持该输入，直到队列排空。
  val mduInputBlockedByIRQ =
    isMDUInstruction && csrUnit.io.IRQPending && !io.MemTrapCommit
  // 尚未发射的M扩展指令只有在所有更老MEM指令都排空后，才能被消耗并在IRQ后重放。
  // 否则，MDU专用ready路径会绕过BlockIrqForPipeline，破坏精确陷阱顺序。
  val mduInputKilledByIRQ =
    mduInputBlockedByIRQ && !pendingMDU && !olderPipelineBusy

  if (config.UseM) {
    val mduUnit = Module(ysyx_26030103_MDU(config))
    // MDU请求保存在顺序指令队列中。这样，即使最老结果仍在
    // 等待LSU/WBU级，流水乘法器也能接收连续的M扩展指令。
    val mduQueueDepth = config.MDUMaxInflight
    val mduQueuePointerWidth = log2Ceil(mduQueueDepth).max(1)
    val mduQueueCountWidth = log2Ceil(mduQueueDepth + 1)
    val mduInstQueue = RegInit(
      VecInit(Seq.fill(mduQueueDepth)(0.U.asTypeOf(chiselTypeOf(io.in.bits))))
    )
    val mduQueueHead = RegInit(0.U(mduQueuePointerWidth.W))
    val mduQueueTail = RegInit(0.U(mduQueuePointerWidth.W))
    val mduQueueCount = RegInit(0.U(mduQueueCountWidth.W))
    pendingMDU := mduQueueCount =/= 0.U
    pendingMDUInst := mduInstQueue(mduQueueHead)

    // MDU的Flush必须同时屏蔽旧Resp；访存故障提交时，所有已经
    // 接收的年轻M指令都属于待取消事务。
    mduUnit.io.Flush := io.MemTrapCommit
    mduUnit.io.Request.bits.LHS := mduRequestLHS
    mduUnit.io.Request.bits.RHS := mduRequestRHS
    mduUnit.io.Request.bits.MDUOp := mduRequestOp

    val mduResponseReady = pendingMDU && io.out.ready &&
      !io.MemTrapCommit && !blockPendingMDUForMEM
    mduUnit.io.Response.ready := mduResponseReady
    mduResponseValid := mduUnit.io.Response.valid
    mduResponseResult := mduUnit.io.Response.bits.Result
    mduPerfDivIterations := mduUnit.io.PerfDivIterations
    mduPerfDivEarlyOut := mduUnit.io.PerfDivEarlyOut
    mduResponseFire := mduUnit.io.Response.valid && mduResponseReady
    val mduQueueHasSpace = mduQueueCount < mduQueueDepth.U || mduResponseFire
    mduRequestReady := isMDUInstruction && !mduInputBlockedByIRQ &&
      mduQueueHasSpace && mduUnit.io.Request.ready
    mduUnit.io.Request.valid := io.in.valid && isMDUInstruction &&
      !mduInputBlockedByIRQ && mduQueueHasSpace && !fpPending
    mduRequestFire := mduUnit.io.Request.valid && mduUnit.io.Request.ready
    assert(!mduRequestFire || mduQueueHasSpace, "EXU MDU instruction queue overflow")
    assert(
      !mduResponseFire || pendingMDU,
      "EXU received an MDU response without an instruction"
    )
    assert(mduQueueCount <= mduQueueDepth.U, "EXU MDU instruction queue overflowed")

    val nextMDUQueueTail = Mux(
      mduQueueTail === (mduQueueDepth - 1).U,
      0.U,
      mduQueueTail + 1.U
    )
    val nextMDUQueueHead = Mux(
      mduQueueHead === (mduQueueDepth - 1).U,
      0.U,
      mduQueueHead + 1.U
    )
    when(io.MemTrapCommit) {
      mduQueueCount := 0.U
      mduQueueHead := 0.U
      mduQueueTail := 0.U
    }.elsewhen(mduRequestFire || mduResponseFire) {
      when(mduRequestFire) {
        mduInstQueue(mduQueueTail) := inst
        mduQueueTail := nextMDUQueueTail
      }
      when(mduResponseFire) {
        mduQueueHead := nextMDUQueueHead
      }
      when(mduRequestFire && !mduResponseFire) {
        mduQueueCount := mduQueueCount + 1.U
      }.elsewhen(!mduRequestFire && mduResponseFire) {
        mduQueueCount := mduQueueCount - 1.U
      }
    }

    // 现有的单一冒险/转发端口描述MDU队首。为每个更年轻的已排队写入者，
    // 以及当前正在等待入队的MDU输入构建掩码。
    var hiddenMDUWriteMask: UInt = 0.U(32.W)
    for (offset <- 1 until mduQueueDepth) {
      val extendedHead = Cat(0.U(1.W), mduQueueHead)
      val rawIndex = extendedHead + offset.U((mduQueuePointerWidth + 1).W)
      val wrappedIndex = Mux(
        rawIndex >= mduQueueDepth.U,
        rawIndex - mduQueueDepth.U,
        rawIndex
      )
      val entry = mduInstQueue(wrappedIndex(mduQueuePointerWidth - 1, 0))
      val entryWrites = offset.U < mduQueueCount && entry.RegisterWrite &&
        entry.Rd =/= 0.U
      hiddenMDUWriteMask = hiddenMDUWriteMask |
        Mux(entryWrites, UIntToOH(entry.Rd, 32), 0.U(32.W))
    }
    val currentMDUWrite = pendingMDU && io.in.valid && isMDUInstruction &&
      inst.RegisterWrite && inst.Rd =/= 0.U
    val currentMDUWriteMask =
      Mux(currentMDUWrite, UIntToOH(inst.Rd, 32), 0.U(32.W))
    mduHiddenWriteMask := hiddenMDUWriteMask | currentMDUWriteMask
  }

  // FPU 请求：只在所有更老 MEM 指令排空后发射，避免 fflags 在更老
  // 访存故障之前被提交。FpuReq.ready 来自后端，支持 DIV/SQRT 多周期。
  val isFPUInstruction =
    (if (useF) inst.IsFPU && !instructionTrapValid else false.B)
  val fpuFlush = WireDefault(false.B)
  val fpuRequestFire = fpuRequest.valid && fpuRequest.ready
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

  io.in.ready := Mux(
    isMDUInstruction,
    Mux(mduInputKilledByIRQ, true.B, mduRequestReady) && !fpPending,
    Mux(
      isFPUInstruction,
      !fpPending && !pendingMDU && fpuRequest.ready && !blockForOlder &&
        !io.MemTrapCommit,
      // 待处理的MDU/FPU结果会占用本周期唯一的输出槽位。更年轻的普通指令
      // 必须留在 IDU->EXU 寄存器中，直到结果发出。
      !pendingMDU && !fpPending && io.out.ready && !blockForOlder
    )
  )
  io.out.valid := Mux(
    pendingMDU,
    mduResponseValid && !io.MemTrapCommit && !blockPendingMDUForMEM,
    Mux(fpPending, fpResponseValid && !io.MemTrapCommit,
      io.in.valid && !isMDUInstruction && !isFPUInstruction && !pendingMDU &&
        !fpPending && !blockForOlder)
  )

  val activeInst = Wire(chiselTypeOf(io.in.bits))
  activeInst := inst
  when(pendingMDU) {
    activeInst := pendingMDUInst
  }.elsewhen(fpPending) {
    activeInst := fpInstReg
  }
  val activeIsMDU = if (config.UseM) activeInst.IsMDU else false.B
  val activeMDUOp =
    if (config.UseM) activeInst.MDUOp else 0.U(ysyx_26030103_MDUOp.Width.W)
  val activeInstructionTrapValid = Mux(
    pendingMDU,
    activeInst.ExceptionValid,
    Mux(fpPending, fpInstReg.ExceptionValid, instructionTrapValid)
  )
  // InstructionAddressMisaligned 由当前输入 inst 计算；PendingMDU/FpPending
  // 时 ActiveInst 是更老的排队指令，不能误用年轻 inst 的 misaligned 信号。
  val activeInstructionAddressMisaligned =
    Mux(pendingMDU || fpPending, false.B, instructionAddressMisaligned)
  // Decoupled.bits在valid=0时没有语义。空流水IRQ会独立拉高CSR Enable，
  // 因此所有“当前指令”控制必须显式由valid门控，不能让上一个MRET/CSR
  // 留下的bits与IRQ同时产生副作用。
  val validInstructionForCSR =
    io.in.valid && !pendingMDU && !fpPending && !instructionTrapValid
  csrUnit.io.IsCSRrw := validInstructionForCSR && inst.IsCSRrw
  csrUnit.io.IsCSRrs := validInstructionForCSR && inst.IsCSRrs
  csrUnit.io.IsCSRrc := validInstructionForCSR && inst.IsCSRrc
  csrUnit.io.IsEcall := validInstructionForCSR && inst.IsEcall
  csrUnit.io.IsEbreak := validInstructionForCSR && inst.IsEbreak
  csrUnit.io.IsMret := validInstructionForCSR && inst.IsMret
  csrUnit.io.IsSret :=
    validInstructionForCSR && (if (hasS) inst.IsSret else false.B)
  csrUnit.io.IsSfenceVma :=
    validInstructionForCSR && (if (hasS) inst.IsSfenceVma else false.B)
  csrUnit.io.CSRAddress := inst.CSRAddress
  csrUnit.io.rs1 := inst.Rs1
  csrUnit.io.Rs1Data := inst.Rs1Data
  val trapBoundaryPC = Mux(
    io.MemTrapCommit,
    io.MemTrapPC,
    Mux(idleIRQCommit, io.IdleIRQPC, inst.pc)
  )
  csrUnit.io.pc := trapBoundaryPC
  // 非法压缩指令的 mtval 写入原始 16 位编码（零扩展）。32 位非法指令
  // 保持既有 ABI（DiffTest/回归测试以 PC 作为 tval）；load/store 使用
  // 故障地址，misaligned 使用目标地址，取指访问错误/breakpoint 仍回退
  // 到 PC；ecall/中断由 CSR 侧固定写 0。
  val activeIllegalCompressed =
    activeInst.ExceptionValid && activeInst.ExceptionCause === 2.U &&
      activeInst.IsCompressed
  val activeInstructionVisible = io.in.valid || pendingMDU
  // 上游已标记的异常（取指 access fault、非法指令）优先于指令形状：
  // 取指故障的访存指令仍带 MemoryValid，但 mtval 必须是发生故障的
  // 取指地址，不能取 ALU 计算出的访存地址。C 模式下 32 位指令跨字时
  // 故障可能发生在第二个字（...1000），而指令 pc/sepc 仍是 ...ffe；
  // IFU/顶层用 pred_target 携带这个 fault VAddr（异常指令不使用预测目标）。
  // 压缩非法指令仍是原始 16 位编码，32 位非法指令沿用既有 ABI 以 PC
  // 作为 tval。misaligned 的 mtval 是错误的目标地址；其余无专门 tval
  // 的异常回退到 ActiveInst.pc。
  val activeFetchFault =
    activeInst.ExceptionValid && activeInst.ExceptionCause =/= 2.U
  val activeTrapTval = Mux(
    activeInst.ExceptionValid,
    Mux(
      activeFetchFault,
      activeInst.pred_target,
      Mux(activeIllegalCompressed, activeInst.Instruction, activeInst.pc)
    ),
    Mux(
      activeInstructionAddressMisaligned,
      controlTransferTarget,
      Mux(activeInst.MemoryValid, aluUnit.io.result, activeInst.pc)
    )
  )
  val activeTrapTvalValid = activeInstructionVisible &&
    (activeInst.ExceptionValid || activeIllegalCompressed ||
      activeInst.MemoryValid || activeInstructionAddressMisaligned)
  csrUnit.io.TrapTval := Mux(io.MemTrapCommit, io.MemTrapTval, activeTrapTval)
  csrUnit.io.TrapTvalValid := io.MemTrapCommit || activeTrapTvalValid
  // fence.i也要产生"重定向": 目标是自己的snpc(即fence.i+4),
  // 借此把流水线里比fence.i年轻的、可能过时的指令全部冲刷并重新取指
  // 实际下一PC: jalr最低位清零由JalrTarget给出;分支not-taken与fence.i都回snpc
  val actualNextPC = Mux(
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
  val commitNextPC =
    if (hasS) Mux(inst.IsMret || inst.IsSret,
      csrUnit.io.ExceptionTarget, actualNextPC)
    else Mux(inst.IsMret, csrUnit.io.ExceptionTarget, actualNextPC)
  // 预测下一PC: IFU用BTB(分支BTFN+独立jal BTB)给出,随指令传到此;未命中=顺序=snpc
  val predNextPC = Mux(inst.pred_taken, inst.pred_target, inst.snpc)
  // 预测错误检查: 比较实际与预测的下一PC,不一致则冲刷并重定向到实际目标
  // (jal: jal BTB命中免冲刷; ret: Ret表项+RAS栈顶预测; 其他jalr: 无预测=>必然判错重定向)
  val mispredict = actualNextPC =/= predNextPC
  // MRET 的实际下一PC是mepc，不参与这里的顺序PC比较：它的重定向完全由
  // ExceptionTaken 承担。此前陈旧BTB表项会让 MRET 走 Mispredict 产生一次
  // 伪 Redirect（目标虽已修正为mepc，但事件语义/计数仍会误导）。
  // SFENCE.VMA 改变地址空间后，年轻指令可能已按旧翻译取指/译码；
  // 提交时必须像 fence.i 一样冲刷年轻取指，并从 snpc 重新取指。
  // satp 写入同样会切换地址空间：CSRFlushTLB 在合法 satp 写提交拍为高。
  // 即使软件随后才执行 sfence.vma，也要先丢弃已经带着旧上下文 BTB 快照
  // 预取的年轻指令，避免它们随后用伪预测冲刷自己的访存/浮点副作用。
  val redirect =
    (mispredict || inst.IsFenceI ||
      (if (hasS) inst.IsSfenceVma || csrUnit.io.FlushTLB else false.B)) &&
      !instructionTrapValid && !inst.IsMret &&
      (if (hasS) !inst.IsSret else true.B)
  // CSR提交(csr写/ecall/ebreak/mret/异常/中断): 普通访存不访问CSR单元；
  // 但已使能IRQ可以在load/store执行前的指令边界提交，并压掉该访存。
  // 带异常标记的访存指令(取指错/非法编码)必须在EXU提交异常, 提交视角下不算访存指令,
  // 否则会落入"等MEM级提交"分支而把异常吞掉
  // 指令级提交只认本拍fire; MEM级故障走独立的MemTrap后门, 这样MemTrap当拍
  // EXU里被冲刷的指令(csrrw/mret)不会产生CSR副作用, 异常目标也不会被mret劫持
  // MEM故障是更老指令的精确异常；当它提交时，当前EXU指令即使因
  // FlushEXMEM而被“放行”也不能再提交自己的CSR/MRET副作用。
  val instructionCSRCommit = io.in.fire && !pendingMDU &&
    (!isMemoryForCommit || csrUnit.io.IRQPending) && !io.MemTrapCommit
  csrUnit.io.Enable := instructionCSRCommit || idleIRQCommit
  csrUnit.io.MemTrap := io.MemTrapCommit
  csrUnit.io.TrapValid :=
    io.in.valid && !pendingMDU && !fpPending && instructionTrapValid
  csrUnit.io.TrapCause := Mux(
    io.MemTrapCommit,
    io.MemTrapCause,
    Mux(
      instructionAddressMisaligned,
      0.U(32.W),
      Cat(0.U((XLEN - 4).W), inst.ExceptionCause)
    )
  )

  // 当前EXU指令只有在真正被接受、且没有被更老的访存故障或中断
  // 抢占时才算提交。预测器和当前指令产生的控制副作用统一使用它。
  val instructionCommit =
    io.in.fire && !pendingMDU && !instructionTrapValid && !io.MemTrapCommit &&
      !csrUnit.io.IRQCommit
  // 性能事件描述的是当前正发往下一级的指令，而不是排出待处理MDU结果时
  // 恰好呈现的任何更年轻输入。
  val activeCommit = Mux(
    pendingMDU,
    mduResponseFire && !io.MemTrapCommit && !csrUnit.io.IRQCommit,
    io.out.fire && !activeInstructionTrapValid && !io.MemTrapCommit &&
      !csrUnit.io.IRQCommit
  )

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
  // 重定向目标必须与实际提交的下一PC一致：MRET 的实际目标是 mepc
  // （CommitNextPC 的 MRET 分支），若用 ActualNextPC 会给出顺序 snpc。
  // 功能上 MRET 由 ExceptionTaken 优先分支覆盖，此处只修正
  // RedirectTarget 的语义（消费方/波形/perf 归因不再看到错值）。
  io.RedirectTarget := commitNextPC
  // ecall/mret/上游异常提交,或MEM经CSR后门提交的访存故障
  io.ExceptionTaken := csrUnit.io.ExceptionTaken
  io.ExceptionTarget := csrUnit.io.ExceptionTarget

  io.out.bits.Instruction := activeInst.Instruction
  // 同步异常指令不退休；IRQ抢占的指令会从mepc重做，也不退休。
  // load/store先携带退休意图进入MEM，若总线故障则由LSU清除。
  io.out.bits.Retire := !activeInstructionTrapValid && !activeInst.IsEcall &&
    !activeInst.IsEbreak && !activeInst.IsSimHalt && !csrUnit.io.IRQCommit &&
    !io.MemTrapCommit
  // 只有真正提交的 CSR 写才打标记；该指令随后仍可正常退休并计入 PMU。
  io.out.bits.WritesMinstret :=
    instructionCommit && csrUnit.io.MinstretWriteCommit
  io.out.bits.pc := activeInst.pc
  io.out.bits.snpc := activeInst.snpc
  // Pending MDU 不可能是控制转移；其它指令使用本拍已
  // 计算的实际后继 PC，MRET 则使用 mepc。
  io.out.bits.NextPC :=
    Mux(pendingMDU || fpPending, activeInst.snpc, commitNextPC)
  io.out.bits.Rd := activeInst.Rd
  // 带异常标记的指令不得写回GPR;被中断压掉的指令(IrqCommit)也不得写回
  io.out.bits.RegisterWrite :=
    activeInst.RegisterWrite && !activeInstructionTrapValid &&
      !csrUnit.io.IRQCommit && !io.MemTrapCommit
  io.out.bits.WBSelect := activeInst.WBSelect
  // RV64 W 型 M 扩展结果只保留低 32 位并按 bit31 符号扩展。
  val mduResultFinal =
    if (XLEN == 64)
      Mux(ysyx_26030103_MDUOp.IsWordOp(activeInst.MDUOp),
        sext32(mduResponseResult(31, 0)), mduResponseResult)
    else mduResponseResult
  io.out.bits.ALUResult := Mux(pendingMDU, mduResultFinal,
    Mux(fpPending, fpResponseBits.result(XLEN - 1, 0), aluUnit.io.result))
  io.out.bits.VAddress := aluUnit.io.result
  io.out.bits.LoadData := 0.U(XLEN.W) // 由MEM在访存完成后填写
  io.out.bits.CSRReadData := csrUnit.io.CSR_rdata
  io.out.bits.CSRStateMstatus := csrUnit.io.StateMstatus
  io.out.bits.CSRStateMtvec := csrUnit.io.StateMtvec
  io.out.bits.CSRStateMepc := csrUnit.io.StateMepc
  io.out.bits.CSRStateMcause := csrUnit.io.StateMcause
  io.out.bits.MemoryValid :=
    activeInst.MemoryValid && !activeInstructionTrapValid &&
      !csrUnit.io.IRQCommit && !io.MemTrapCommit
  io.out.bits.MemoryWrite :=
    activeInst.MemoryWrite && !activeInstructionTrapValid &&
      !csrUnit.io.IRQCommit && !io.MemTrapCommit
  io.out.bits.WidthSelect := activeInst.WidthSelect
  io.out.bits.LoadSigned := activeInst.LoadSigned
  io.out.bits.StoreData := activeInst.StoreData
  // F/D：FPU 结果直接从这里写入 f 寄存器；FLW/FLD 的 FPWriteData 由
  // LSU 在 load 完成时按 FP64 做 NaN-boxing。
  io.out.bits.FPRegisterWrite :=
    activeInst.FPRegisterWrite && !activeInstructionTrapValid &&
      !csrUnit.io.IRQCommit && !io.MemTrapCommit
  io.out.bits.FPWriteData :=
    Mux(fpPending, fpResponseBits.result, activeInst.FPWriteData)
  io.out.bits.FP64 := activeInst.FP64
  // A 扩展：把译码得到的原子标记/操作号透传到 LSU。
  io.out.bits.IsAtomic := activeInst.IsAtomic
  io.out.bits.AtomicOp := activeInst.AtomicOp

  val perfALUEligible = activeCommit &&
    !activeInst.MemoryValid && !activeIsMDU && !activeInst.IsCSRrw &&
    !activeInst.IsCSRrs && !activeInst.IsCSRrc && !activeInst.IsBranch && !activeInst.IsJal &&
    !activeInst.IsJalr && !activeInst.IsEcall && !activeInst.IsEbreak &&
    !activeInst.IsMret && !activeInst.IsSimHalt && !activeInst.IsFence &&
    !activeInst.IsFenceI && !activeInst.ExceptionValid
  val perfEventKind = Mux(
    !activeCommit,
    0.U,
    Mux(
      activeIsMDU,
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
  // 类型电平用于活跃周期统计。PendingMDU时io.in.bits可能已经是
  // 被反压的年轻指令，因此必须与输出数据一样使用ActiveInst，
  // 否则会把MDU等待周期错记到年轻ALU/MEM/CSR/branch上。
  val activeALUClass =
    !activeInst.MemoryValid && !activeIsMDU && !activeInst.IsCSRrw &&
      !activeInst.IsCSRrs && !activeInst.IsCSRrc && !activeInst.IsBranch && !activeInst.IsJal &&
      !activeInst.IsJalr && !activeInst.IsEcall && !activeInst.IsEbreak &&
      !activeInst.IsMret && !activeInst.IsSimHalt && !activeInst.IsFence &&
      !activeInst.IsFenceI && !activeInstructionTrapValid
  io.PerfALUOp := activeALUClass
  io.PerfMemOp := activeInst.MemoryValid && !activeInstructionTrapValid
  io.PerfCSROp := activeInst.IsCSRrw || activeInst.IsCSRrs || activeInst.IsCSRrc
  io.PerfBranchOp := activeInst.IsBranch || activeInst.IsJal || activeInst.IsJalr
  io.PerfJalOp := activeInst.IsJal
  io.PerfJalrOp := activeInst.IsJalr
  io.PerfBranchMispredict := instructionCommit && mispredict
  io.PerfBranchTaken := instructionCommit && branchTaken
  io.PerfFPURequest := fpuRequestFire
  io.PerfFPUDone := fpResponseFire
  io.PerfLoadOp := activeInst.MemoryValid && !activeInst.MemoryWrite &&
    !activeInst.IsAtomic && !activeInstructionTrapValid
  io.PerfStoreOp := activeInst.MemoryValid && activeInst.MemoryWrite &&
    !activeInst.IsAtomic && !activeInstructionTrapValid
  io.PerfAmoOp := activeInst.IsAtomic && !activeInstructionTrapValid
  io.PerfBranchNotTaken := instructionCommit && inst.IsBranch && !branchTaken
  io.PerfFPUActive := fpPending
  io.PerfFenceOp := activeInst.IsFence || activeInst.IsFenceI
  io.PerfRvcInst := activeInst.IsCompressed
  io.PerfSystemOp := activeInst.IsEcall || activeInst.IsEbreak ||
    activeInst.IsMret || activeInst.IsSret || activeInst.IsSfenceVma
  io.PerfInterruptTaken := csrUnit.io.IRQCommit
  io.PerfCSRRead := activeInst.IsCSRrw || activeInst.IsCSRrs ||
    activeInst.IsCSRrc
  io.PerfCSRWrite := activeInst.IsCSRrw ||
    ((activeInst.IsCSRrs || activeInst.IsCSRrc) && activeInst.Rs1 =/= 0.U)
  io.PerfRedirectBranch := instructionCommit && mispredict
  io.PerfRedirectFenceI := instructionCommit &&
    (inst.IsFenceI || (if (hasS) inst.IsSfenceVma else false.B))
  io.PerfRedirectTrap := csrUnit.io.ExceptionTaken
  io.PerfRedirectMret := instructionCommit &&
    (inst.IsMret || (if (hasS) inst.IsSret else false.B))
  io.PerfFPUStall := fpPending && !fpResponseValid
  io.PerfIllegalInst :=
    csrUnit.io.TrapCommit && csrUnit.io.CommittedCause === 2.U
  io.PerfFPUDivSqrt := fpPending &&
    (fpInstReg.FPOp === ysyx_26030103_FPUOp.FDiv ||
      fpInstReg.FPOp === ysyx_26030103_FPUOp.FSqrt)
  io.PerfFPUFma := fpPending &&
    (fpInstReg.FPOp === ysyx_26030103_FPUOp.FFma ||
      fpInstReg.FPOp === ysyx_26030103_FPUOp.FFms ||
      fpInstReg.FPOp === ysyx_26030103_FPUOp.FFnma ||
      fpInstReg.FPOp === ysyx_26030103_FPUOp.FFnms)
  io.PerfFPUException := fpResponseFire && fpResponseBits.fflags =/= 0.U
  io.PerfCSRIllegal :=
    csrUnit.io.TrapCommit && csrUnit.io.CommittedCause === 2.U &&
      (activeInst.IsCSRrw || activeInst.IsCSRrs || activeInst.IsCSRrc)
  io.PerfIRQPending := csrUnit.io.IRQPending
  io.PerfDivIterations := mduPerfDivIterations
  io.PerfDivEarlyOut := mduPerfDivEarlyOut
  io.PerfEventKind := perfEventKind
  io.PerfMDURequest := mduRequestFire
  io.PerfMDUDone := activeCommit && activeIsMDU && !activeInst.ExceptionValid
  io.PerfMDUOp := activeMDUOp
  // MDUActive包含首次发射拍。MDUWait只统计等待MDU本身的周期；
  // 若结果已valid却因EX/MEM/LSU反压未接收，应归到LSU/下游stall，
  // 不再误算成MDU计算延迟。
  val waitingForFirstMDUResult =
    !pendingMDU && io.in.valid && isMDUInstruction && !mduInputBlockedByIRQ
  val waitingForPendingMDUResult = pendingMDU && !mduResponseValid
  val mduActiveLevel = pendingMDU || mduRequestFire
  val mduIsMul = activeMDUOp < 4.U || activeMDUOp === 8.U
  io.PerfDivDone := activeCommit && activeIsMDU && !mduIsMul
  io.PerfMDUActive := mduActiveLevel
  io.PerfMulActive := mduActiveLevel && mduIsMul
  io.PerfDivActive := mduActiveLevel && !mduIsMul
  io.PerfMDUWait := !io.out.fire &&
    (waitingForFirstMDUResult || waitingForPendingMDUResult)
  io.PerfExecutionActive := io.in.valid || pendingMDU

  // 这些是“当前EXU指令”的副作用。中断接受或更老访存故障提交时，
  // 当前指令会被重做/丢弃，不能训练预测器，也不能产生重定向或FenceI刷新。
  io.FenceIFlush := instructionCommit && inst.IsFenceI
  // CSR 指令提交视为上下文/地址空间可能切换，保守地清除 LR reservation。
  io.CSRCommit := instructionCommit &&
    (inst.IsCSRrw || inst.IsCSRrs || inst.IsCSRrc ||
      (if (hasS) inst.IsSfenceVma else false.B))
  val localFlush =
    (instructionCommit && redirect) || csrUnit.io.ExceptionTaken
  io.FlushIF := localFlush
  // 更老指令的精确 trap/重定向必须取消在途 FPU 请求（尤其 DIV/SQRT）。
  fpuFlush := localFlush

  // FPU 运算在 EXU 提交点累积 fflags。此时所有更老 MEM 指令已排空
  // （RequiresPipelineDrain），因此不会出现“更老访存随后才 trap”的情况。
  val fpFlagsCommit =
    fpPending && fpResponseFire && !csrUnit.io.IRQCommit
  csrUnit.io.FPCommit := fpFlagsCommit
  csrUnit.io.FPFlags := fpResponseBits.fflags
  csrUnit.io.FPStateDirtyCommit := io.FPStateDirtyCommit
  io.CSRFrm := csrUnit.io.Frm
  io.CSRFcsr := csrUnit.io.Fcsr

  io.HazardValid := io.in.valid || pendingMDU || fpPending
  io.HazardRd := activeInst.Rd
  // 带异常标记的指令不会真正写回,不应让IDU白白等它;被中断压掉的指令同理
  io.HazardRegWrite :=
    activeInst.RegisterWrite && !activeInstructionTrapValid &&
      !csrUnit.io.IRQCommit && !io.MemTrapCommit
  io.HazardMemOp := activeInst.MemoryValid
  io.HazardMDUHiddenWrites := mduHiddenWriteMask
  io.PerfIdleNoInput := !io.in.valid && !pendingMDU
  io.PerfTrap := csrUnit.io.TrapCommit
  // 转发给IDU的最终写回值(与WBU写GPR的值一致): ALU结果/snpc/CSR读出
  // (load数据在EXU阶段不可知,由MEM提供它那一级的转发)
  io.ForwardData := Mux(
    pendingMDU,
    mduResultFinal,
    Mux(
      fpPending,
      fpResponseBits.result(XLEN - 1, 0),
      Mux(
        activeInst.WBSelect === 2.U,
        activeInst.snpc,
        Mux(activeInst.WBSelect === 3.U, csrUnit.io.CSR_rdata, aluUnit.io.result)
      )
    )
  )
  // 可转发条件: 会写rd,且不是load(load要等MEM完成)；FPU 运算必须
  // 等响应 valid，不能把尚未发射/尚未返回的占位 ALU 结果前递给
  // 紧随其后的分支或整数消费者（FCVT/FCMP/FCLASS 写 GPR）。
  io.ForwardReady := io.HazardValid && io.HazardRegWrite && !activeInst.MemoryValid &&
    (!activeIsMDU || mduResponseValid) &&
    (!activeInst.IsFPU || (fpPending && fpResponseValid))
  // 浮点写口转发。Fcvt/Fmv/比较写 GPR 时 HazardFPRegWrite=0；FLW/FLD
  // 的数据在 MEM 级才可用，因此 EXU 不声明 FP 转发 ready。FPU 运算
  // 在请求尚未返回（甚至尚未发射）时也不能声称可转发，否则 IDU 会把
  // IDU 侧的占位 FPWriteData(0) 当结果前递。
  io.HazardFPValid := io.in.valid || pendingMDU || fpPending
  io.HazardFPRd := activeInst.Rd
  io.HazardFPRegWrite :=
    activeInst.FPRegisterWrite && !activeInstructionTrapValid &&
      !csrUnit.io.IRQCommit && !io.MemTrapCommit
  io.ForwardFPData := Mux(fpPending, fpResponseBits.result, activeInst.FPWriteData)
  io.ForwardFPReady := io.HazardFPValid && io.HazardFPRegWrite &&
    !activeInst.MemoryValid && fpPending && fpResponseValid

  // 分支BTB只保存BTFN有可能预测taken的后向条件分支。前向表项即使命中，
  // PredictorSelect仍会选择顺序PC；将它写入只会占用容量并驱逐有用的
  // 后向分支。InstructionCommit继续保证异常/IRQ/更老MEM fault不会训练表项。
  io.BTBUpdateValid :=
    instructionCommit && inst.IsBranch && branchTarget < inst.pc
  io.BTBUpdatePC := inst.pc
  io.BTBUpdateTarget := branchTarget
  // RISC-V RAS hint同时使用x1(ra)与x5(alternate link register)。JAL在rd为
  // link register时push；JALR按rd/rs1 hint表产生push、pop，异名link register
  // (x1<-x5或x5<-x1)是协程切换，需要同拍pop后push。
  val rdIsLink = inst.Rd === 1.U || inst.Rd === 5.U
  val rs1IsLink = inst.Rs1 === 1.U || inst.Rs1 === 5.U
  val rasPush = (inst.IsJal && rdIsLink) || (inst.IsJalr && rdIsLink)
  val rasPop =
    inst.IsJalr && rs1IsLink && (!rdIsLink || inst.Rd =/= inst.Rs1)
  // jal BTB更新: jal提交时写回静态目标；普通间接call(rd=x1/x5且不pop)
  // 写回本次解析出的实际JALR目标，后续稳定函数指针调用即可命中；ret及
  // 协程切换仍保存静态immediate，由预测器与动态RAS栈顶相加。
  // 不带link提示的普通间接jalr目标可能频繁变化，仍不入表。
  val indirectCall = inst.IsJalr && rdIsLink && !rasPop
  io.JalBTBUpdateValid :=
    instructionCommit && (inst.IsJal || rasPop || indirectCall)
  io.JalBTBUpdatePC := inst.pc
  io.JalBTBUpdateTarget := Mux(
    inst.IsJal,
    jalTarget,
    Mux(rasPop, inst.Immediate, jalrTarget)
  )
  io.JalBTBUpdateKind := Mux(
    rasPop,
    ysyx_26030103_BTBKind.Ret,
    Mux(rdIsLink, ysyx_26030103_BTBKind.Call, ysyx_26030103_BTBKind.Jal)
  )
  // RAS更新：协程JALR会同时拉高push/pop，RAS内部按pop-then-push处理。
  io.RASPushValid := instructionCommit && rasPush
  io.RASPushAddress := inst.snpc
  io.RASPopValid := instructionCommit && rasPop
}
