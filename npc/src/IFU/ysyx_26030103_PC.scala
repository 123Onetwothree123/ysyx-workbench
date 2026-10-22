package ysyx_26030103.ifu
import chisel3._
import chisel3.util._
class ysyx_26030103_PC(
    resetAddr: Long = 0x30000000L,
    val XLEN: Int = 32
) extends Module {
  require(XLEN == 32 || XLEN == 64, s"PC XLEN 非法: $XLEN")
  val io = IO(new Bundle {
    val ysyx_26030103_NextPC = Input(UInt(XLEN.W))
    val PCEnable = Input(Bool())
    val ysyx_26030103_PC = Output(UInt(XLEN.W))
  })
  val PCReg = RegInit(resetAddr.U(XLEN.W))
  when(io.PCEnable) {
    PCReg := io.ysyx_26030103_NextPC
  }
  io.ysyx_26030103_PC := PCReg
}
