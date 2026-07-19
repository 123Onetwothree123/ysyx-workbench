package ysyx_26030103
import chisel3._
import chisel3.util._
class ysyx_26030103_PC(resetAddr: Long = 0x30000000L) extends Module {
  val io = IO(new Bundle {
    val ysyx_26030103_NextPC = Input(UInt(32.W))
    val PCEnable = Input(Bool())
    val ysyx_26030103_PC = Output(UInt(32.W))
  })
  val PCReg = RegInit(resetAddr.U(32.W))
  when(io.PCEnable) {
    PCReg := io.ysyx_26030103_NextPC
  }
  io.ysyx_26030103_PC := PCReg
}
