package ysyx_26030103
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.ysyx_26030103_Message.ysyx_26030103_IFUMessage

class ysyx_26030103_IFU(resetAddr: Long = 0x30000000L) extends Module {
  val io = IO(new Bundle {
    val FetchAddr  = Output(UInt(32.W))
    val FetchValid = Output(Bool())
    val FetchReady = Input(Bool())

    val RespData  = Input(UInt(32.W))
    val RespValid = Input(Bool())
    val RespReady = Output(Bool())

    val RedirectTarget  = Input(UInt(32.W))
    val Redirect        = Input(Bool())
    val ExceptionTaken  = Input(Bool())
    val ExceptionTarget = Input(UInt(32.W))

    val out = Decoupled(new ysyx_26030103_IFUMessage)

    val DebugPC           = Output(UInt(32.W))
    val DebugInstructions = Output(UInt(32.W))
    val AccessFault       = Output(Bool())
    val AccessFaultResp   = Output(UInt(2.W))
    val StallPipeline     = Output(Bool())
    val StallICache       = Output(Bool())
    val StallIdle         = Output(Bool())
  })
  val PCModule     = Module(new ysyx_26030103_PC(resetAddr))
  val NextPCModule = Module(new ysyx_26030103_NextPC)
  val snpc = PCModule.io.ysyx_26030103_PC + 4.U(32.W)

  val states = Enum(3)
  val StatesIdle       = states(0)
  val StatesWaitICache = states(1)
  val StatesHold       = states(2)
  val state = RegInit(StatesIdle)

  val InstructionReg     = RegInit(0.U(32.W))
  val PCReg              = RegInit(0.U(32.W))
  val AccessFaultReg     = RegInit(false.B)
  val AccessFaultRespReg = RegInit(0.U(2.W))

  io.AccessFault     := AccessFaultReg
  io.AccessFaultResp := AccessFaultRespReg

  io.FetchValid := false.B
  io.FetchAddr  := 0.U
  io.RespReady  := false.B
  io.out.valid  := false.B
  io.out.bits.Instruction := InstructionReg
  io.out.bits.pc          := PCReg

  switch(state) {
    is(StatesIdle) {
      AccessFaultReg     := false.B
      AccessFaultRespReg := 0.U
      io.FetchValid := true.B
      io.FetchAddr  := PCModule.io.ysyx_26030103_PC
      PCReg         := PCModule.io.ysyx_26030103_PC
      when(io.FetchReady) {
        state := StatesWaitICache
      }
    }
    is(StatesWaitICache) {
      io.RespReady := true.B
      when(io.RespValid && io.RespReady) {
        InstructionReg := io.RespData
        when(io.RespData === "h00100073".U) {
          printf(cf"IFU: fetched ebreak at pc=${PCModule.io.ysyx_26030103_PC}\n")
        }
        state := StatesHold
      }
    }
    is(StatesHold) {
      io.out.valid := true.B
      when(io.out.valid && io.out.ready) {
        state := StatesIdle
      }
    }
  }
  NextPCModule.io.SNPC             := snpc
  NextPCModule.io.Redirect         := io.Redirect
  NextPCModule.io.RedirectTarget   := io.RedirectTarget
  NextPCModule.io.ExceptionTaken   := io.ExceptionTaken
  NextPCModule.io.ExceptionTarget  := io.ExceptionTarget
  PCModule.io.ysyx_26030103_NextPC := NextPCModule.io.ysyx_26030103_NextPC
  PCModule.io.PCEnable := NextPCModule.io.PCEnable && io.out.fire

  io.DebugPC           := PCModule.io.ysyx_26030103_PC
  io.DebugInstructions := InstructionReg
  io.StallPipeline     := state === StatesHold
  io.StallICache       := state === StatesWaitICache
  io.StallIdle         := state === StatesIdle
}
