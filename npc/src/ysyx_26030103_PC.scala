package ysyx_26030103
import chisel3._
import chisel3.util._
class ysyx_26030103_PC extends Module {
  val io = IO(new Bundle {
    val ysyx_26030103_NextPC = Input(UInt(32.W)) // 下一条ysyx_26030103_PC值
    val PCEnable = Input(Bool()) // ysyx_26030103_PC写使能
    val ysyx_26030103_PC = Output(UInt(32.W)) // 当前ysyx_26030103_PC值
  })
  val RESET_ADDR = 0x20000000L
  val PCReg = RegInit(RESET_ADDR.U(32.W))
  when(io.PCEnable) {
    PCReg := io.ysyx_26030103_NextPC
  }
  io.ysyx_26030103_PC := PCReg
}
