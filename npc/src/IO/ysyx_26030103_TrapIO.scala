package ysyx_26030103
import chisel3._

class ysyx_26030103_TrapIO extends Bundle {
  val valid = Output(Bool())
  val pc = Output(UInt(32.W))
  val pc_hi = Output(UInt(32.W))
}
