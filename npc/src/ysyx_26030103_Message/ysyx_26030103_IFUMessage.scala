package ysyx_26030103.ysyx_26030103_Message
import chisel3._
class ysyx_26030103_IFUMessage extends Bundle {
  val Instruction = UInt(32.W)
  val pc = UInt(32.W)//和ysyx_26030103_IFU的ysyx_26030103_PC做区别，这个是取的那一刻的ysyx_26030103_PC
}
