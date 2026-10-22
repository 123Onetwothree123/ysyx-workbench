package ysyx_26030103.exu
import chisel3._

// ALU 加法器：ADD 使用；地址计算、jal/jalr/auipc 目标加法也可以复用同一个实现。
class ysyx_26030103_ALUAdder(val XLEN: Int = 32) extends Module {
  require(XLEN == 32 || XLEN == 64, s"ALUAdder XLEN 非法: $XLEN")
  val io = IO(new Bundle {
    val A = Input(UInt(XLEN.W))
    val B = Input(UInt(XLEN.W))
    val sum = Output(UInt(XLEN.W))
    // RV64 ADDW：只保留低 32 位，最终由顶层做符号扩展。
    val sumWord = Output(UInt(32.W))
  })
  io.sum := io.A + io.B
  io.sumWord := (io.A(31, 0) + io.B(31, 0))(31, 0)
}
