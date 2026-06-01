package RV32I
import chisel3._
import chisel3.util._
class IFU extends Module {
  val io = IO(new Bundle {
    val InstructionReadDATA = Input(UInt(32.W))
    val RedirectTarget = Input(UInt(32.W))
    val Redirect = Input(Bool())
    val ExceptionTaken = Input(Bool())
    val ExceptionTarget = Input(UInt(32.W))
    val out = Decoupled(new IFUMessage)
    val PC = Output(UInt(32.W))
    val InstructionAddress = Output(UInt(32.W))
    val InstructionOutput = Output(UInt(32.W))
    val SNPC = Output(UInt(32.W))
  })

  val PCModule = Module(new PC)
  val NextPCModule = Module(new NextPC)
  val snpc = PCModule.io.PC + 4.U(32.W)
  // 状态机，我真的是服了，谁能想到wait是scala和Java的object的内置方法，居然还不能用这个做变量名
  val states = Enum(2)
  val StatesIdle = states(0)
  val StatesWait = states(1)
  val state = RegInit(StatesIdle)
  // 他妈的居然防止报警转错，还要手动去给个初始值
  io.out.valid := false.B
  io.out.bits.Instruction := io.InstructionReadDATA
  io.out.bits.pc := PCModule.io.PC
  switch(state) {
    is(StatesIdle) {
      io.out.valid := false.B
      state := StatesWait
    }
    is(StatesWait) {
      io.out.valid := true.B
      io.out.bits.Instruction := io.InstructionReadDATA
      io.out.bits.pc := PCModule.io.PC
      when(io.out.valid && io.out.ready) { // 只有IDU真的接走了这条指令，IFU这里才会去进入下一次的取指
        state := StatesIdle
      }
    }
  }
  NextPCModule.io.SNPC := snpc
  NextPCModule.io.Redirect := io.Redirect
  NextPCModule.io.RedirectTarget := io.RedirectTarget
  NextPCModule.io.ExceptionTaken := io.ExceptionTaken
  NextPCModule.io.ExceptionTarget := io.ExceptionTarget

  PCModule.io.NextPC := NextPCModule.io.NextPC
  PCModule.io.PCEnable := NextPCModule.io.PCEnable && io.out.fire

  io.PC := PCModule.io.PC
  io.InstructionAddress := PCModule.io.PC
  io.InstructionOutput := io.InstructionReadDATA
  io.SNPC := snpc
}
