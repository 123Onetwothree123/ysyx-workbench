package ysyx_26030103.ifu
import chisel3._
import chisel3.util._
class ysyx_26030103_NextPC(val XLEN: Int = 32) extends Module {
  require(XLEN == 32 || XLEN == 64, s"NextPC XLEN 非法: $XLEN")
  val io = IO(new Bundle {
    val SNPC = Input(UInt(XLEN.W))
    val RedirectTarget = Input(UInt(XLEN.W)) // jal和jalr和branch的目标地址
    val Redirect = Input(Bool())
    val ExceptionTaken = Input(Bool()) // ecall/ebreak异常进入，或mret返回
    val ExceptionTarget = Input(
      UInt(XLEN.W)
    ) // ecall/ebreak跳ysyx_26030103_mtvec，mret跳ysyx_26030103_mepc
    val ysyx_26030103_NextPC = Output(UInt(XLEN.W))
    // 分支预测: 无redirect/exception时,BTB命中则跳到预测目标,否则顺序取指
    val PredHit = Input(Bool())
    val PredTarget = Input(UInt(XLEN.W))
  })
  io.ysyx_26030103_NextPC := Mux(
    io.ExceptionTaken,
    io.ExceptionTarget,
    Mux(io.Redirect, io.RedirectTarget, Mux(io.PredHit, io.PredTarget, io.SNPC))
  )
}
