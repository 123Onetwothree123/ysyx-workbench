package ysyx_26030103.ifu
import chisel3._
import chisel3.util._

// 取指流水化后的IFU: 退化为纯PC发生器
// 每拍向icache发一个取指请求,被接受就PC+4;重定向/异常时停发一拍并改写PC
// 指令的锁存、交付和冲刷全部由icache响应级+下游流水寄存器完成。
// FetchAddr 是架构 PC（XLEN 位）；顶层在无 MMU 时直接接到同宽
// 物理 ICache/AXI 地址，不再截断到 32 位。
class ysyx_26030103_IFU(
    resetAddr: Long = 0x30000000L,
    val XLEN: Int = 32
) extends Module {
  require(XLEN == 32 || XLEN == 64, s"IFU XLEN 非法: $XLEN")
  val io = IO(new Bundle {
    val FetchAddr = Output(UInt(XLEN.W))
    val FetchValid = Output(Bool())
    val FetchReady = Input(Bool())

    val RedirectTarget = Input(UInt(XLEN.W))
    val Redirect = Input(Bool())
    val ExceptionTaken = Input(Bool())
    val ExceptionTarget = Input(UInt(XLEN.W))

    // BTB查询结果(由顶层例化的BTB用当前FetchAddr查后回送)
    val PredHit = Input(Bool())
    val PredTarget = Input(UInt(XLEN.W))

    val StallIdle = Output(Bool())
  })
  val PCModule = Module(new ysyx_26030103_PC(resetAddr, XLEN))
  val NextPCModule = Module(new ysyx_26030103_NextPC(XLEN))
  val snpc = PCModule.io.ysyx_26030103_PC + 4.U(XLEN.W)

  val redirect = io.Redirect || io.ExceptionTaken
  io.FetchValid := !redirect
  io.FetchAddr := PCModule.io.ysyx_26030103_PC

  NextPCModule.io.SNPC := snpc
  NextPCModule.io.Redirect := io.Redirect
  NextPCModule.io.RedirectTarget := io.RedirectTarget
  NextPCModule.io.ExceptionTaken := io.ExceptionTaken
  NextPCModule.io.ExceptionTarget := io.ExceptionTarget
  NextPCModule.io.PredHit := io.PredHit && !redirect
  NextPCModule.io.PredTarget := io.PredTarget
  PCModule.io.ysyx_26030103_NextPC := NextPCModule.io.ysyx_26030103_NextPC
  PCModule.io.PCEnable := redirect || (io.FetchValid && io.FetchReady)

  io.StallIdle := !io.FetchValid
}
