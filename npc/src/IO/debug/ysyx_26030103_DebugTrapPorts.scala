package ysyx_26030103.io.debug
import chisel3._
private[ysyx_26030103] trait ysyx_26030103_DebugTrapPorts {
  this: Bundle =>
  val trap_valid = Output(Bool())
  val trap_pc = Output(UInt(32.W))
  val trap_pc_hi = Output(UInt(32.W))
  val trap_target = Output(UInt(32.W))
  val trap_target_hi = Output(UInt(32.W))
  val trap_cause = Output(UInt(32.W))
  val trap_tval = Output(UInt(32.W))
  val trap_tval_hi = Output(UInt(32.W))
  val csr_mstatus = Output(UInt(32.W))
  val csr_mstatus_hi = Output(UInt(32.W))
  val csr_mtvec = Output(UInt(32.W))
  val csr_mtvec_hi = Output(UInt(32.W))
  val csr_mepc = Output(UInt(32.W))
  val csr_mepc_hi = Output(UInt(32.W))
  val csr_mcause = Output(UInt(32.W))
  val csr_mcause_hi = Output(UInt(32.W))
  val trap_mstatus = Output(UInt(32.W))
  val trap_mstatus_hi = Output(UInt(32.W))
  val trap_mtvec = Output(UInt(32.W))
  val trap_mtvec_hi = Output(UInt(32.W))
  val trap_mepc = Output(UInt(32.W))
  val trap_mepc_hi = Output(UInt(32.W))
  val trap_mcause = Output(UInt(32.W))
  val trap_mcause_hi = Output(UInt(32.W))
}
