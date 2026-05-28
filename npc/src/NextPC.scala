package RV32I
import chisel3._
import chisel3.util._
class NextPC extends Module {
  val io = IO(new Bundle {
    val SNPC = Input(UInt(32.W))
    val RedirectTarget = Input(UInt(32.W)) // jal和jalr和branch的目标地址
    val Redirect = Input(Bool())
    val ExceptionTaken = Input(Bool()) // ecall/ebreak异常进入，或mret返回
    val ExceptionTarget = Input(UInt(32.W)) // ecall/ebreak跳mtvec，mret跳mepc
    val NextPC = Output(UInt(32.W))
    val PCEnable = Output(Bool())
  })
  io.NextPC := Mux(
    io.ExceptionTaken,
    io.ExceptionTarget,
    Mux(io.Redirect, io.RedirectTarget, io.SNPC)
  )
  io.PCEnable := true.B
}
