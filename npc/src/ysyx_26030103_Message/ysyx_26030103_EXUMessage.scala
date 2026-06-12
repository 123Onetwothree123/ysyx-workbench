package ysyx_26030103.ysyx_26030103_Message
import chisel3._
class ysyx_26030103_EXUMessage extends Bundle {
  val Rd = UInt(5.W)
  val RegisterWrite = Bool()
  val WBSelect = UInt(2.W)
  val ALUResult = UInt(32.W)
  val LoadData = UInt(32.W)
  val snpc = UInt(32.W)
  val CSRReadData = UInt(32.W)
}
