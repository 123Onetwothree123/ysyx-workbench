package ysyx_26030103.io.debug
import chisel3._
private[ysyx_26030103] trait ysyx_26030103_DebugRegisterPorts {
  this: Bundle =>
  val gpr_raddr = Input(UInt(5.W))
  val gpr_rdata = Output(UInt(32.W))
  val gpr_rdata_hi = Output(UInt(32.W))
  val gpr_wen = Output(Bool())
  val gpr_waddr = Output(UInt(5.W))
  val fpr_raddr = Input(UInt(5.W))
  val fpr_rdata = Output(UInt(32.W))
  val fpr_rdata_hi = Output(UInt(32.W))
  val fpr_wen = Output(Bool())
  val fpr_waddr = Output(UInt(5.W))
  val fcsr = Output(UInt(32.W))
  val fp_valid = Output(Bool())
}
