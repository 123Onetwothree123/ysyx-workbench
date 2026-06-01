package RV32I
import chisel3._
import chisel3.util._
class IFU extends Module {
  val io = IO(new Bundle {
    val InstructionReadDATA = Input(UInt(32.W))
    val InstructionRespValid = Input(Bool())
    val RedirectTarget = Input(UInt(32.W))
    val Redirect = Input(Bool())
    val ExceptionTaken = Input(Bool())
    val ExceptionTarget = Input(UInt(32.W))
    val out = Decoupled(new IFUMessage)
    val InstructionReqValid = Output(Bool())
    val InstructionReqReady = Input(Bool())
    val InstructionRespReady = Output(Bool())
    val PC = Output(UInt(32.W))
    val InstructionAddress = Output(UInt(32.W))
    val InstructionOutput = Output(UInt(32.W))
    val SNPC = Output(UInt(32.W))
  })

  val PCModule = Module(new PC)
  val NextPCModule = Module(new NextPC)
  val snpc = PCModule.io.PC + 4.U(32.W)
  // 状态机，我真的是服了，谁能想到wait是scala和Java的object的内置方法，居然还不能用这个做变量名
  val states = Enum(4)
  val StatesIdle = states(0)
  val StatesWaitReq = states(1)
  val StatesWaitResp = states(2)
  val StatesHold = states(3)
  val state = RegInit(StatesIdle)
  val instructionReg = RegInit(0.U(32.W))
  val pcReg = RegInit(0.U(32.W))
  val InstructionRespFire = io.InstructionRespValid && io.InstructionRespReady
  // 他妈的居然防止报警转错，还要手动去给个初始值
  io.InstructionReqValid := false.B
  io.InstructionRespReady := false.B
  io.out.valid := false.B
  io.out.bits.Instruction := instructionReg
  io.out.bits.pc := pcReg
  switch(state) {
    is(StatesIdle) {
      io.InstructionReqValid := true.B
      pcReg := PCModule.io.PC
      state := Mux(io.InstructionReqReady, StatesWaitResp, StatesWaitReq)
    }
    is(StatesWaitReq) {
      io.InstructionReqValid := true.B
      when(io.InstructionReqReady) {
        state := StatesWaitResp
      }
    }
    is(StatesWaitResp) {
      io.InstructionRespReady := true.B
      when(InstructionRespFire) {
        instructionReg := io.InstructionReadDATA
        state := StatesHold
      }
    }
    is(StatesHold) {
      io.out.valid := true.B
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
  io.InstructionOutput := instructionReg
  io.SNPC := snpc
}
