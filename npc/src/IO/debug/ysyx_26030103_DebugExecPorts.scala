package ysyx_26030103.io.debug
import chisel3._
private[ysyx_26030103] trait ysyx_26030103_DebugExecPorts {
  this: Bundle =>
  val pc = Output(UInt(32.W))
  val pc_hi = Output(UInt(32.W))
  val next_pc = Output(UInt(32.W))
  val next_pc_hi = Output(UInt(32.W))
  val arch_pc = Output(UInt(32.W))
  val arch_pc_hi = Output(UInt(32.W))
  val instructions = Output(UInt(32.W))
  val mtrace_valid = Output(Bool())
  val mtrace_pc = Output(UInt(32.W))
  val mtrace_pc_hi = Output(UInt(32.W))
  val mtrace_wen = Output(Bool())
  val mtrace_addr = Output(UInt(32.W))
  val mtrace_addr_hi = Output(UInt(32.W))
  val mtrace_wdata = Output(UInt(32.W))
  val mtrace_wdata_hi = Output(UInt(32.W))
  val mtrace_rdata = Output(UInt(32.W))
  val mtrace_rdata_hi = Output(UInt(32.W))
  val mtrace_width = Output(UInt(2.W))
  val access_fault = Output(Bool())
  val access_fault_pc = Output(UInt(32.W))
  val access_fault_pc_hi = Output(UInt(32.W))
  val access_fault_resp = Output(UInt(2.W))
  val commit = Output(Bool())
}
