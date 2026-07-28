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
    val FlushFetch        = Input(Bool())
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
  val cyc = RegInit(0.U(32.W))
  cyc := cyc + 1.U
  when(cyc < 10.U) {
    printf("[IFU c=%d] state=%d pc=%x redirect=%d fvalid=%d fready=%d rvalid=%d\n",
      cyc, state, PCModule.io.ysyx_26030103_PC, io.Redirect, io.FetchValid, io.FetchReady, io.RespValid)
  }

  switch(state) {
    is(StatesIdle) {
      io.RespReady := true.B
      when(io.FlushFetch || io.Redirect) {
        state := StatesIdle
      }.otherwise {
      AccessFaultReg     := false.B
      AccessFaultRespReg := 0.U
      io.FetchValid := true.B
      io.FetchAddr  := PCModule.io.ysyx_26030103_PC
      PCReg         := PCModule.io.ysyx_26030103_PC
      when(io.FetchReady) {
        state := StatesWaitICache
      }
      }
    }
    is(StatesWaitICache) {
      when(io.FlushFetch || io.Redirect) {
        state := StatesIdle
      }.otherwise {
      io.RespReady := true.B
      when(io.RespValid && io.RespReady) {
        InstructionReg := io.RespData
        state := StatesHold
      }
      }
    }
    is(StatesHold) {
      when(io.FlushFetch || io.Redirect) {
        state := StatesIdle
      }.otherwise {
      io.out.valid := true.B
      when(io.out.valid && io.out.ready) {
        state := StatesIdle
      }
      }
    }
  }
  NextPCModule.io.SNPC             := snpc
  NextPCModule.io.Redirect         := io.Redirect
  NextPCModule.io.RedirectTarget   := io.RedirectTarget
  NextPCModule.io.ExceptionTaken   := io.ExceptionTaken
  NextPCModule.io.ExceptionTarget  := io.ExceptionTarget
  PCModule.io.ysyx_26030103_NextPC := NextPCModule.io.ysyx_26030103_NextPC
  PCModule.io.PCEnable := (NextPCModule.io.PCEnable && io.out.fire) || io.Redirect || io.ExceptionTaken

  io.DebugPC           := PCModule.io.ysyx_26030103_PC
  io.DebugInstructions := InstructionReg
  io.StallPipeline     := state === StatesHold
  io.StallICache       := state === StatesWaitICache
  io.StallIdle         := state === StatesIdle
  when(state === StatesHold && io.out.fire) {
    printf("[IFU] out pc=%x inst=%x\n", PCReg, InstructionReg)
  }
  when(io.Redirect) {
    printf("[IFU] redirect target=%x\n", io.RedirectTarget)
  }
}
