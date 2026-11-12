package ysyx_26030103.ifu
import _root_.ysyx_26030103.common.ysyx_26030103_KconfigDefaults
import chisel3._
import _root_.ysyx_26030103.common.ysyx_26030103_PMAOptions
class ysyx_26030103_PC(
    resetAddr: Long = ysyx_26030103_PMAOptions.Default.socResetAddr,
    val XLEN: Int = ysyx_26030103_KconfigDefaults.NpcXlen
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
