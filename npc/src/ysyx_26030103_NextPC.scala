package ysyx_26030103
import chisel3._
import chisel3.util._
class ysyx_26030103_NextPC extends Module {
  val io = IO(new Bundle {
    val SNPC = Input(UInt(32.W))
    val RedirectTarget = Input(UInt(32.W)) // jal和jalr和branch的目标地址
    val Redirect = Input(Bool())
    val ExceptionTaken = Input(Bool()) // ecall/ebreak异常进入，或mret返回
    val ExceptionTarget = Input(
      UInt(32.W)
    ) // ecall/ebreak跳ysyx_26030103_mtvec，mret跳ysyx_26030103_mepc
    val ysyx_26030103_NextPC = Output(UInt(32.W))
    val PCEnable = Output(Bool())
    // 分支预测: 无redirect/exception时,BTB命中则跳到预测目标,否则顺序取指
    val PredHit = Input(Bool())
    val PredTarget = Input(UInt(32.W))
  })
  io.ysyx_26030103_NextPC := Mux(
    io.ExceptionTaken,
    io.ExceptionTarget,
    Mux(io.Redirect, io.RedirectTarget, Mux(io.PredHit, io.PredTarget, io.SNPC))
  )
  io.PCEnable := true.B
}
