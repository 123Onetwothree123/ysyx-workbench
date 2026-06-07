package RV32I
import chisel3._
import chisel3.util._
import _root_.RV32I.Message._
import _root_.RV32I.CSR._
import _root_.RV32I.ALU._
class EXU extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new IDUMessage))
    val out = Decoupled(new EXUMessage)
    val Redirect = Output(Bool())
    val RedirectTarget = Output(UInt(32.W))
    val ExceptionTaken = Output(Bool())
    val ExceptionTarget = Output(UInt(32.W))
    val Interrupt = Input(Bool())
    // 收LSU的
    val LSU_Complete = Input(Bool())
    val LSULoadDATA = Input(UInt(32.W))
    // 给LSU的
    val MemoryValid = Output(Bool())
    val MemoryWrite = Output(Bool())
    val WidthSelect = Output(UInt(2.W))
    val ALUResult_ToLSU = Output(UInt(32.W))
    val StoreDATA = Output(UInt(32.W))
    val LoadSigned = Output(Bool())
  })
  val ALUUnit = Module(new ALU)
  ALUUnit.io.A := io.in.bits.ALU_A
  ALUUnit.io.B := io.in.bits.ALU_B
  ALUUnit.io.ALUCtrl := io.in.bits.ALUCtrl
  val CSRUnit = Module(new CSR)
  CSRUnit.io.clk := clock
  CSRUnit.io.rst := reset.asBool
  CSRUnit.io.IsCsrrw := io.in.bits.IsCsrrw
  CSRUnit.io.IsCsrrs := io.in.bits.IsCsrrs
  CSRUnit.io.IsEcall := io.in.bits.IsEcall
  CSRUnit.io.IsEbreak := io.in.bits.IsEbreak
  CSRUnit.io.IsMret := io.in.bits.IsMret
  CSRUnit.io.CSRAddress := io.in.bits.CSRAddress
  CSRUnit.io.rs1 := io.in.bits.Rs1
  CSRUnit.io.Rs1Data := io.in.bits.Rs1Data
  CSRUnit.io.pc := io.in.bits.pc
  CSRUnit.io.Interrupt := io.Interrupt
  val BranchComparatorUnit = Module(new BranchComparator)
  BranchComparatorUnit.io.A := io.in.bits.BranchA
  BranchComparatorUnit.io.B := io.in.bits.BranchB
  BranchComparatorUnit.io.Funct3 := io.in.bits.BranchFunct3
  BranchComparatorUnit.io.IsBranch := io.in.bits.IsBranch
  // 也是状态机，多周期处理器都是做状态机的吗
  val states = Enum(3)
  val StatesIdle = states(0)
  val StatesWait = states(1)
  val StatesDone = states(2)
  val state = RegInit(StatesIdle)
  // 先给fire一个初始值，我是真的没理解为什么要给这个名字
  io.in.ready := false.B
  io.out.valid := false.B
  val FSM_Is_Idle = state === StatesIdle
  io.MemoryValid := io.in.fire && io.in.bits.MemoryValid && FSM_Is_Idle
  // 真的不知道这么做对不对，但是Immediate到底能不能负数，能不能往前跳啊，反正我之前还想做减法的，然后问AI设计思路的时候
  // deepseek说不需要，因为immgen是输出带符号的，符号应该没事吧，但是我immdiate又是UInt的，但是测试好像成功通过测试了，他
  // 妈的，好烦，我不知道
  val BranchTarget = io.in.bits.pc + io.in.bits.Immediate
  val JalTarget = ALUUnit.io.result
  // 和logisim一样，最低位换成常量0
  val JalrTarget = Cat(ALUUnit.io.result(31, 1), 0.U(1.W))
  val Redirect =
    io.in.bits.IsJal || io.in.bits.IsJalr || BranchComparatorUnit.io.Taken
  // val InstructionExecutionDone=FSM_Is_Idle&&io.in.fire
  // 让AI后期二次审核的时候，AI说必须这么写，说我写的是错的，问原因，就说是为了安全，防止误触，因为LSU要几个周期，而且访存指令
  // 本身不会产生跳转
  // 那不就是了，既然load不会跳，store不会跳，问题这么一来Redirect是没激活的啊，得jal，jalr才能激活啊，因为第三个条件，这些条
  // 件判断语句跳转吗？
  // 也不是
  // 为了严谨吗？防止valid选中内存的这些加载和存储指令选中吗？
  // 不知道，反正加入了后也能跑起来，也许算是提高了条件的门槛，提高安全性吧
  val InstructionExecutionDone =
    FSM_Is_Idle && io.in.fire && !io.in.bits.MemoryValid
  CSRUnit.io.Enable := FSM_Is_Idle && io.in.fire && !io.in.bits.MemoryValid
  io.Redirect := InstructionExecutionDone && Redirect
  when(io.in.bits.IsJalr) {
    io.RedirectTarget := JalrTarget
  }.elsewhen(io.in.bits.IsJal) {
    io.RedirectTarget := JalTarget
  }.otherwise {
    io.RedirectTarget := BranchTarget
  }
  io.ExceptionTaken := InstructionExecutionDone && CSRUnit.io.ExceptionTaken
  io.ExceptionTarget := CSRUnit.io.ExceptionTarget
  io.MemoryWrite := io.in.bits.MemoryWrite
  io.WidthSelect := io.in.bits.WidthSelect
  io.ALUResult_ToLSU := ALUUnit.io.result
  io.StoreDATA := io.in.bits.StoreData
  io.LoadSigned := io.in.bits.LoadSigned
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
  io.out.bits.Rd := io.in.bits.Rd
  io.out.bits.RegisterWrite := io.in.bits.RegisterWrite
  io.out.bits.WBSelect := io.in.bits.WBSelect
  io.out.bits.ALUResult := ALUUnit.io.result
  io.out.bits.LoadData := io.LSULoadDATA
  io.out.bits.snpc := io.in.bits.snpc
  io.out.bits.CSRReadData := CSRUnit.io.CSR_rdata
}