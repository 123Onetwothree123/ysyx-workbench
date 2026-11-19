package ysyx_26030103.io.debug
import chisel3._
private[ysyx_26030103] trait ysyx_26030103_DebugRegisterPorts {
  this: Bundle =>
  val GPRRaddr = Input(UInt(5.W))
  val GPRRdata = Output(UInt(32.W))
  val GPRRdataHi = Output(UInt(32.W))
  val GPRWen = Output(Bool())
  val GPRWaddr = Output(UInt(5.W))
  val FPRRaddr = Input(UInt(5.W))
  val FPRRdata = Output(UInt(32.W))
  val FPRRdataHi = Output(UInt(32.W))
  val FPRWen = Output(Bool())
  val FPRWaddr = Output(UInt(5.W))
  val fcsr = Output(UInt(32.W))
  val FPValid = Output(Bool())
}
