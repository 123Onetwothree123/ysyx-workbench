package ysyx_26030103
import chisel3._
import chisel3.util._

// 取指流水化后的IFU: 退化为纯PC发生器
// 每拍向icache发一个取指请求,被接受就PC+4;重定向/异常时停发一拍并改写PC
// 指令的锁存、交付和冲刷全部由icache响应级+下游流水寄存器完成
class ysyx_26030103_IFU(resetAddr: Long = 0x30000000L) extends Module {
  val io = IO(new Bundle {
    val FetchAddr  = Output(UInt(32.W))
    val FetchValid = Output(Bool())
    val FetchReady = Input(Bool())

    val RedirectTarget  = Input(UInt(32.W))
    val Redirect        = Input(Bool())
    val ExceptionTaken  = Input(Bool())
    val ExceptionTarget = Input(UInt(32.W))

    val DebugPC       = Output(UInt(32.W))
    val StallPipeline = Output(Bool())
    val StallICache   = Output(Bool())
    val StallIdle     = Output(Bool())
  })
  val PCModule     = Module(new ysyx_26030103_PC(resetAddr))
  val NextPCModule = Module(new ysyx_26030103_NextPC)
  val snpc = PCModule.io.ysyx_26030103_PC + 4.U(32.W)

  val redirect = io.Redirect || io.ExceptionTaken
  io.FetchValid := !redirect
  io.FetchAddr  := PCModule.io.ysyx_26030103_PC

  NextPCModule.io.SNPC             := snpc
  NextPCModule.io.Redirect         := io.Redirect
  NextPCModule.io.RedirectTarget   := io.RedirectTarget
  NextPCModule.io.ExceptionTaken   := io.ExceptionTaken
  NextPCModule.io.ExceptionTarget  := io.ExceptionTarget
  PCModule.io.ysyx_26030103_NextPC := NextPCModule.io.ysyx_26030103_NextPC
  PCModule.io.PCEnable := redirect || (io.FetchValid && io.FetchReady)

  io.DebugPC       := PCModule.io.ysyx_26030103_PC
  io.StallICache   := io.FetchValid && !io.FetchReady
  io.StallIdle     := !io.FetchValid
  io.StallPipeline := false.B // 下游反压的测点上移到icache响应侧,由顶层统计
}
