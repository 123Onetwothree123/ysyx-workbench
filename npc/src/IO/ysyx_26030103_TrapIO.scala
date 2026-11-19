package ysyx_26030103
import chisel3._

class ysyx_26030103_TrapIO extends Bundle {
  val valid = Output(Bool())
  val PC = Output(UInt(32.W))
  val PCHi = Output(UInt(32.W))
}
