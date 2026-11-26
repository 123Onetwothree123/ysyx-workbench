package ysyx_26030103.ifu
import _root_.ysyx_26030103.common.ysyx_26030103_XLENRequire
import _root_.ysyx_26030103.common.ysyx_26030103_KconfigDefaults
import chisel3._
import _root_.ysyx_26030103.common.ysyx_26030103_PMAOptions
class ysyx_26030103_PC(
    resetAddress: Long = ysyx_26030103_PMAOptions.Default.SOCResetAddress,
    val XLEN: Int = ysyx_26030103_KconfigDefaults.NpcXlen
) extends Module {
  ysyx_26030103_XLENRequire(XLEN, "PC")
  val io = IO(new Bundle {
    val ysyx_26030103_NextPC = Input(UInt(XLEN.W))
    val PCEnable = Input(Bool())
    val ysyx_26030103_PC = Output(UInt(XLEN.W))
  })
  val PCReg = RegInit(resetAddress.U(XLEN.W))
  when(io.PCEnable) {
    PCReg := io.ysyx_26030103_NextPC
  }
  io.ysyx_26030103_PC := PCReg
}
