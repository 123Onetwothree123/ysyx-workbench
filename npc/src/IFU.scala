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

  io.out.valid := true.B
  io.out.bits.Instruction := io.InstructionReadDATA
  io.out.bits.pc := PCModule.io.PC
}
