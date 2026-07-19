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
  when(FSM_Is_Idle && io.in.fire && io.in.bits.MemoryValid) {
    MemoryInstructionReg := io.in.bits
  }
  val ActiveInstruction =
    Mux(FSM_Is_Idle, io.in.bits, MemoryInstructionReg)
  ALUUnit.io.A := ActiveInstruction.ALU_A
  ALUUnit.io.B := ActiveInstruction.ALU_B
  ALUUnit.io.ALUCtrl := ActiveInstruction.ALUCtrl
  CSRUnit.io.IsCsrrw := ActiveInstruction.IsCsrrw
  CSRUnit.io.IsCsrrs := ActiveInstruction.IsCsrrs
  CSRUnit.io.IsEcall := ActiveInstruction.IsEcall
  CSRUnit.io.IsEbreak := ActiveInstruction.IsEbreak
  CSRUnit.io.IsMret := ActiveInstruction.IsMret
  CSRUnit.io.CSRAddress := ActiveInstruction.CSRAddress
  CSRUnit.io.rs1 := ActiveInstruction.Rs1
  CSRUnit.io.Rs1Data := ActiveInstruction.Rs1Data
  CSRUnit.io.pc := ActiveInstruction.pc
  BranchComparatorUnit.io.A := ActiveInstruction.BranchA
  BranchComparatorUnit.io.B := ActiveInstruction.BranchB
  BranchComparatorUnit.io.Funct3 := ActiveInstruction.BranchFunct3
  BranchComparatorUnit.io.IsBranch := ActiveInstruction.IsBranch
  // 先给fire一个初始值，我是真的没理解为什么要给这个名字
  io.in.ready := false.B
  io.out.valid := false.B
  io.MemoryValid := io.in.fire && io.in.bits.MemoryValid && FSM_Is_Idle
  when (io.MemoryValid && io.MemoryWrite) {
    printf(p"[EXU STORE pc=0x${Hexadecimal(ActiveInstruction.pc)} addr=0x${Hexadecimal(ALUUnit.io.result)}]\n")
  }
  when (FSM_Is_Idle && io.in.valid && io.in.bits.MemoryValid) {
    printf(p"[EXU GOT MEM OP]\n")
  }
  // 真的不知道这么做对不对，但是Immediate到底能不能负数，能不能往前跳啊，反正我之前还想做减法的，然后问AI设计思路的时候
  // deepseek说不需要，因为immgen是输出带符号的，符号应该没事吧，但是我immdiate又是UInt的，但是测试好像成功通过测试了，他
  // 妈的，好烦，我不知道
  val BranchTarget = ActiveInstruction.pc + ActiveInstruction.Immediate
  val JalTarget = ALUUnit.io.result
  // 和logisim一样，最低位换成常量0
  val JalrTarget = Cat(ALUUnit.io.result(31, 1), 0.U(1.W))
  val Redirect =
    ActiveInstruction.IsJal || ActiveInstruction.IsJalr || BranchComparatorUnit.io.Taken
  // val InstructionExecutionDone=FSM_Is_Idle&&io.in.fire
  // 让AI后期二次审核的时候，AI说必须这么写，说我写的是错的，问原因，就说是为了安全，防止误触，因为ysyx_26030103_LSU要几个周期，而且访存指令
  // 本身不会产生跳转
  // 那不就是了，既然load不会跳，store不会跳，问题这么一来Redirect是没激活的啊，得jal，jalr才能激活啊，因为第三个条件，这些条
  // 件判断语句跳转吗？
  // 也不是
  // 为了严谨吗？防止valid选中内存的这些加载和存储指令选中吗？
  // 不知道，反正加入了后也能跑起来，也许算是提高了条件的门槛，提高安全性吧
  val InstructionExecutionDone =
    FSM_Is_Idle && io.in.fire && !io.in.bits.MemoryValid
  CSRUnit.io.Enable := FSM_Is_Idle && io.in.fire && !io.in.bits.MemoryValid
  io.TrapValid := InstructionExecutionDone && ActiveInstruction.IsEbreak
  io.TrapPC := ActiveInstruction.pc
  io.Redirect := InstructionExecutionDone && Redirect
  when(ActiveInstruction.IsJalr) {
    io.RedirectTarget := JalrTarget
  }.elsewhen(ActiveInstruction.IsJal) {
    io.RedirectTarget := JalTarget
  }.otherwise {
    io.RedirectTarget := BranchTarget
  }
  io.ExceptionTaken := InstructionExecutionDone && CSRUnit.io.ExceptionTaken
  io.ExceptionTarget := CSRUnit.io.ExceptionTarget
  io.MemoryWrite := ActiveInstruction.MemoryWrite
  io.WidthSelect := ActiveInstruction.WidthSelect
  io.ALUResult_ToLSU := ALUUnit.io.result
  io.StoreDATA := ActiveInstruction.StoreData
  io.LoadSigned := ActiveInstruction.LoadSigned
  switch(state) {
    is(StatesIdle) {
      when(io.in.bits.MemoryValid) { // 内存被选中了
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
  io.out.bits.RegisterWrite := ActiveInstruction.RegisterWrite
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
}
