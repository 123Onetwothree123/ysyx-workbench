package ysyx_26030103.exu
import chisel3._

// ALU 加法器：ADD 使用；地址计算、jal/jalr/auipc 目标加法也可以复用同一个实现。
class ysyx_26030103_ALUAdder extends Module {
  val io = IO(new Bundle {
    val A = Input(UInt(32.W))
    val B = Input(UInt(32.W))
    val sum = Output(UInt(32.W))
  })
  io.sum := io.A + io.B
}
