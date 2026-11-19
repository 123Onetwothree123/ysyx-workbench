package ysyx_26030103.io.debug
import chisel3._
private[ysyx_26030103] trait ysyx_26030103_DebugExecPorts {
  this: Bundle =>
  val PC = Output(UInt(32.W))
  val PCHi = Output(UInt(32.W))
  val NextPC = Output(UInt(32.W))
  val NextPCHi = Output(UInt(32.W))
  val ArchPC = Output(UInt(32.W))
  val ArchPCHi = Output(UInt(32.W))
  val instructions = Output(UInt(32.W))
  val MTraceValid = Output(Bool())
  val MTracePC = Output(UInt(32.W))
  val MTracePCHi = Output(UInt(32.W))
  val MTraceWen = Output(Bool())
  val MTraceAddress = Output(UInt(32.W))
  val MTraceAddressHi = Output(UInt(32.W))
  val MTraceWdata = Output(UInt(32.W))
  val MTraceWdataHi = Output(UInt(32.W))
  val MTraceRdata = Output(UInt(32.W))
  val MTraceRdataHi = Output(UInt(32.W))
  val MTraceWidth = Output(UInt(2.W))
  val AccessFault = Output(Bool())
  val AccessFaultPC = Output(UInt(32.W))
  val AccessFaultPCHi = Output(UInt(32.W))
  val AccessFaultResponse = Output(UInt(2.W))
  val commit = Output(Bool())
}
