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
    // 收ysyx_26030103_LSU的
    val LSU_Complete = Input(Bool())
    val LSULoadDATA = Input(UInt(32.W))
    // LSU完成访存时给出的总线错误标志(RRESP/BRESP非0),只在LSU_Complete那一拍有效,需要锁存
    val LSUAccessFault = Input(Bool())
    // 给ysyx_26030103_LSU的
    val MemoryValid = Output(Bool())
    val MemoryWrite = Output(Bool())
    val WidthSelect = Output(UInt(2.W))
    val ALUResult_ToLSU = Output(UInt(32.W))
    val StoreDATA = Output(UInt(32.W))
    val LoadSigned = Output(Bool())
    val TrapValid = Output(Bool())
    val TrapPC = Output(UInt(32.W))
    val PerfALUOp = Output(Bool())
    val PerfMemOp = Output(Bool())
    val PerfCSROp = Output(Bool())
    val PerfBranchOp = Output(Bool())
    val PerfExecutionActive = Output(Bool())
    val StallWaitLSU = Output(Bool())

    val FenceIFlush = Output(Bool())

    // 统一冲刷输出
    // FlushIF: 分支/跳转/fence.i重定向或异常(ecall/mret/中断/各类异常)提交时,
    //          冲刷IFU内部状态和IFU→IDU流水寄存器里的年轻指令
    // FlushIDEX: 仅访存异常提交时使用,此时异常指令本身在MemoryInstructionReg里,
    //          卡在IDU→EXU流水寄存器里的是年轻指令,必须杀掉;
    //          空闲路径的异常/重定向指令本身就在IDU→EXU寄存器里,靠fire消费,
    //          若flush会形成组合环路并杀掉提交者自己,故两路必须分开
    val FlushIF = Output(Bool())
    val FlushIDEX = Output(Bool())

    // 给IDU做数据冒险检测用：访存指令被锁存进MemoryInstructionReg后，
    // io.in.valid会撤销，流水线寄存器里看不到它了，但指令还在EX阶段，
    // 必须让IDU在整个LSU等待期间都能看到这条指令，否则后面的依赖指令会拿旧值
    val HazardValid = Output(Bool())
    val HazardRd = Output(UInt(5.W))
    val HazardRegWrite = Output(Bool())
    val HazardMemOp = Output(Bool())
    val PerfIdleNoInput = Output(Bool())
    val PerfTrap = Output(Bool())
    // 转发给IDU: EX阶段生产者的最终写回值,以及该值当前是否可用于转发
    val FwdData = Output(UInt(32.W))
    val FwdReady = Output(Bool())
  })
  val ALUUnit = Module(new ysyx_26030103_ALU)
  val CSRUnit = Module(new ysyx_26030103_CSR)
  CSRUnit.io.clk := clock
  CSRUnit.io.rst := reset.asBool
  CSRUnit.io.Interrupt := io.Interrupt
  val BranchComparatorUnit = Module(new ysyx_26030103_BranchComparator)
  // 也是状态机，多周期处理器都是做状态机的吗
  val states = Enum(3)
  val StatesIdle = states(0)
  val StatesWait = states(1)
  val StatesDone = states(2)
  val state = RegInit(StatesIdle)
  val MemoryInstructionReg = Reg(chiselTypeOf(io.in.bits))
  val FSM_Is_Idle = state === StatesIdle
  // 带异常标记的"访存指令"不允许进LSU(取指错的垃圾数据可能碰巧译码成load/store),
  // 按非访存指令处理,直接在空闲路径一拍流向提交点
  val MemOp = io.in.bits.MemoryValid && !io.in.bits.ExceptionValid
  when(FSM_Is_Idle && io.in.fire && MemOp) {
    MemoryInstructionReg := io.in.bits
  }
  val ActiveInstruction =
    Mux(FSM_Is_Idle, io.in.bits, MemoryInstructionReg)
  // 上游随指令传来的异常标记(IFU取指错cause=1/IDU非法指令cause=2)
  // 带标记的指令流到EXU提交点后只做异常提交,不得产生任何副作用(访存/CSR写/GPR写/重定向)
  val UpEx = ActiveInstruction.ExceptionValid
  ALUUnit.io.A := ActiveInstruction.ALU_A
  ALUUnit.io.B := ActiveInstruction.ALU_B
  ALUUnit.io.ALUCtrl := ActiveInstruction.ALUCtrl
  CSRUnit.io.IsCsrrw := ActiveInstruction.IsCsrrw && !UpEx
  CSRUnit.io.IsCsrrs := ActiveInstruction.IsCsrrs && !UpEx
  CSRUnit.io.IsEcall := ActiveInstruction.IsEcall && !UpEx
  CSRUnit.io.IsEbreak := ActiveInstruction.IsEbreak && !UpEx
  CSRUnit.io.IsMret := ActiveInstruction.IsMret && !UpEx
  CSRUnit.io.CSRAddress := ActiveInstruction.CSRAddress
  CSRUnit.io.rs1 := ActiveInstruction.Rs1
  CSRUnit.io.Rs1Data := ActiveInstruction.Rs1Data
  CSRUnit.io.pc := ActiveInstruction.pc
  BranchComparatorUnit.io.A := ActiveInstruction.BranchA
  BranchComparatorUnit.io.B := ActiveInstruction.BranchB
  BranchComparatorUnit.io.Funct3 := ActiveInstruction.BranchFunct3
  BranchComparatorUnit.io.IsBranch := ActiveInstruction.IsBranch
  BranchComparatorUnit.io.IsBranch := ActiveInstruction.IsBranch
  // 先给fire一个初始值，我是真的没理解为什么要给这个名字
  io.in.ready := false.B
  io.out.valid := false.B
  io.MemoryValid := io.in.fire && MemOp && FSM_Is_Idle
  // 真的不知道这么做对不对，但是Immediate到底能不能负数，能不能往前跳啊，反正我之前还想做减法的，然后问AI设计思路的时候
  // deepseek说不需要，因为immgen是输出带符号的，符号应该没事吧，但是我immdiate又是UInt的，但是测试好像成功通过测试了，他
  // 妈的，好烦，我不知道
  val BranchTarget = ActiveInstruction.pc + ActiveInstruction.Immediate
  val JalTarget = ALUUnit.io.result
  // 和logisim一样，最低位换成常量0
  val JalrTarget = Cat(ALUUnit.io.result(31, 1), 0.U(1.W))
  // fence.i也要产生"重定向": 目标是自己的snpc(即fence.i+4),
  // 借此把流水线里比fence.i年轻的、可能过时的指令全部冲刷并重新取指
  val Redirect =
    (ActiveInstruction.IsJal || ActiveInstruction.IsJalr || BranchComparatorUnit.io.Taken ||
    ActiveInstruction.IsFenceI) && !UpEx
  // val InstructionExecutionDone=FSM_Is_Idle&&io.in.fire
  // 让AI后期二次审核的时候，AI说必须这么写，说我写的是错的，问原因，就说是为了安全，防止误触，因为ysyx_26030103_LSU要几个周期，而且访存指令
  // 本身不会产生跳转
  // 那不就是了，既然load不会跳，store不会跳，问题这么一来Redirect是没激活的啊，得jal，jalr才能激活啊，因为第三个条件，这些条
  // 件判断语句跳转吗？
  // 也不是
  // 为了严谨吗？防止valid选中内存的这些加载和存储指令选中吗？
  // 不知道，反正加入了后也能跑起来，也许算是提高了条件的门槛，提高安全性吧
  val InstructionExecutionDone =
    FSM_Is_Idle && io.in.fire && !MemOp
  // 访存错误锁存:LSU进入Done那一拍AccessFault还有效,下一拍LSU回Idle就清零了,必须锁存住
  val MemFaultReg = RegInit(false.B)
  when(state === StatesWait && io.LSU_Complete) {
    MemFaultReg := io.LSUAccessFault
  }
  // 注意:StatesDone里out.valid恒为true,这里用out.ready而不是out.fire,
  // 否则out.valid在StatesIdle分支组合依赖于in.valid,会经FlushIDEX→StageConnect→in.valid形成组合环
  when(state === StatesDone && io.out.ready) {
    MemFaultReg := false.B
  }
  // 访存异常提交点:出错指令在MemoryInstructionReg里,随out.fire退休,
  // 同拍写mepc/mcause(load错cause=5,store错cause=7)、跳mtvec并冲刷年轻指令
  val MemTrapCommit = state === StatesDone && MemFaultReg && io.out.ready
  CSRUnit.io.Enable := (FSM_Is_Idle && io.in.fire && !MemOp) || MemTrapCommit
  CSRUnit.io.TrapValid := (FSM_Is_Idle && UpEx) || MemTrapCommit
  CSRUnit.io.TrapCause := Mux(
    state === StatesDone,
    Mux(MemoryInstructionReg.MemoryWrite, 7.U(32.W), 5.U(32.W)),
    Cat(0.U(28.W), ActiveInstruction.ExceptionCause)
  )

  io.TrapValid := InstructionExecutionDone && CSRUnit.io.IsEbreak
  io.TrapPC := io.in.bits.pc
  io.Redirect := InstructionExecutionDone && Redirect
  when(ActiveInstruction.IsJalr) {
    io.RedirectTarget := JalrTarget
  }.elsewhen(ActiveInstruction.IsJal) {
    io.RedirectTarget := JalTarget
  }.elsewhen(ActiveInstruction.IsFenceI) {
    io.RedirectTarget := ActiveInstruction.snpc // fence.i: 从下一指令重新取指
  }.otherwise {
    io.RedirectTarget := BranchTarget
  }
  // CSRUnit.io.Enable已经精确覆盖两条提交路径(空闲路径fire拍/访存异常提交拍),直接透传即可
  io.ExceptionTaken := CSRUnit.io.ExceptionTaken
  io.ExceptionTarget := CSRUnit.io.ExceptionTarget
  io.MemoryWrite := ActiveInstruction.MemoryWrite
  io.WidthSelect := ActiveInstruction.WidthSelect
  io.ALUResult_ToLSU := ALUUnit.io.result
  io.StoreDATA := ActiveInstruction.StoreData
  io.LoadSigned := ActiveInstruction.LoadSigned
  switch(state) {
    is(StatesIdle) {
      when(io.in.valid && MemOp) { // 内存被选中了（valid 必须为真）,带异常标记的访存指令走下面的非访存路径
        io.in.ready := true.B
        when(io.in.fire) {
          state := StatesWait
        }
      }.otherwise {
        io.in.ready := io.out.ready
        io.out.valid := io.in.valid
      }
    }
    is(StatesWait) {
      when(io.LSU_Complete) {
        state := StatesDone
      }
    }
    is(StatesDone) {
      io.out.valid := true.B
      when(io.out.fire) {
        state := StatesIdle
      }
    }
  }
  io.out.bits.Rd := ActiveInstruction.Rd
  // 带异常标记的指令(以及访存出错的load)不得写回GPR;
  // 被中断压掉的指令(IrqCommit)也不得写回——mepc记的是它自己,mret后会重跑
  io.out.bits.RegisterWrite := ActiveInstruction.RegisterWrite && !UpEx && !MemFaultReg && !CSRUnit.io.IrqCommit
  io.out.bits.WBSelect := ActiveInstruction.WBSelect
  io.out.bits.ALUResult := ALUUnit.io.result
  io.out.bits.LoadData := io.LSULoadDATA
  io.out.bits.snpc := ActiveInstruction.snpc
  io.out.bits.CSRReadData := CSRUnit.io.CSR_rdata
  io.PerfALUOp := !ActiveInstruction.MemoryValid && !ActiveInstruction.IsCsrrw && !ActiveInstruction.IsCsrrs &&
    !ActiveInstruction.IsBranch && !ActiveInstruction.IsJal && !ActiveInstruction.IsJalr
  io.PerfMemOp := ActiveInstruction.MemoryValid
  io.PerfCSROp := ActiveInstruction.IsCsrrw || ActiveInstruction.IsCsrrs
  io.PerfBranchOp := ActiveInstruction.IsBranch || ActiveInstruction.IsJal || ActiveInstruction.IsJalr
  io.PerfExecutionActive := state =/= StatesIdle
  io.StallWaitLSU := state === StatesWait

  io.FenceIFlush := InstructionExecutionDone && ActiveInstruction.IsFenceI && !UpEx

  // 统一冲刷:重定向或异常提交时,冲刷IFU内部状态和IFU→IDU流水寄存器里的年轻指令
  io.FlushIF := io.Redirect || io.ExceptionTaken
  // 仅访存异常提交时冲刷IDU→EXU寄存器(此时里面卡着的是年轻指令;
  // 空闲路径的提交者本身就在该寄存器里,靠fire消费,flush会杀掉提交者并形成组合环路)
  io.FlushIDEX := MemTrapCommit

  // StatesIdle时ActiveInstruction就是io.in.bits，非Idle时是MemoryInstructionReg，
  // 因此load在LSU等待期间对IDU始终可见，IDU会一直stall到它进入WB阶段
  io.HazardValid := io.in.valid || (state =/= StatesIdle)
  io.HazardRd := ActiveInstruction.Rd
  // 带异常标记的指令和访存出错的load不会真正写回,不应让IDU白白等它;被中断压掉的指令同理
  io.HazardRegWrite := ActiveInstruction.RegisterWrite && !UpEx && !MemFaultReg && !CSRUnit.io.IrqCommit
  io.HazardMemOp := ActiveInstruction.MemoryValid
  io.PerfIdleNoInput := FSM_Is_Idle && !io.in.valid
  io.PerfTrap := io.ExceptionTaken
  // 转发给IDU的最终写回值(与WBU写GPR的值一致): ALU结果/snpc/CSR读出/load数据
  io.FwdData := Mux(ActiveInstruction.WBSelect === 2.U, ActiveInstruction.snpc,
    Mux(ActiveInstruction.WBSelect === 3.U, CSRUnit.io.CSR_rdata,
      Mux(ActiveInstruction.WBSelect === 1.U, io.LSULoadDATA, ALUUnit.io.result)))
  // 可转发条件: 会写rd,且非访存指令(当拍就绪)或load已完成(StatesDone时LoadDATA有效)
  io.FwdReady := io.HazardValid && io.HazardRegWrite &&
    (!ActiveInstruction.MemoryValid || state === StatesDone)
}
