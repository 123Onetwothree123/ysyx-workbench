package ysyx_26030103.io.debug
import chisel3._
private[ysyx_26030103] trait ysyx_26030103_DebugTrapPorts {
  this: Bundle =>
  val TrapValid = Output(Bool())
  val TrapPC = Output(UInt(32.W))
  val TrapPCHi = Output(UInt(32.W))
  val TrapTarget = Output(UInt(32.W))
  val TrapTargetHi = Output(UInt(32.W))
  val TrapCause = Output(UInt(32.W))
  val TrapTval = Output(UInt(32.W))
  val TrapTvalHi = Output(UInt(32.W))
  val CSRMstatus = Output(UInt(32.W))
  val CSRMstatusHi = Output(UInt(32.W))
  val CSRMtvec = Output(UInt(32.W))
  val CSRMtvecHi = Output(UInt(32.W))
  val CSRMepc = Output(UInt(32.W))
  val CSRMepcHi = Output(UInt(32.W))
  val CSRMcause = Output(UInt(32.W))
  val CSRMcauseHi = Output(UInt(32.W))
  val TrapMstatus = Output(UInt(32.W))
  val TrapMstatusHi = Output(UInt(32.W))
  val TrapMtvec = Output(UInt(32.W))
  val TrapMtvecHi = Output(UInt(32.W))
  val TrapMepc = Output(UInt(32.W))
  val TrapMepcHi = Output(UInt(32.W))
  val TrapMcause = Output(UInt(32.W))
  val TrapMcauseHi = Output(UInt(32.W))
}
