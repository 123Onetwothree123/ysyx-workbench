package ysyx_26030103.exu
import chisel3._

// ALU 位逻辑：XOR/OR/AND，三个都很浅，单独成块只为结构清晰。
class ysyx_26030103_ALUBitLogic extends Module {
  val io = IO(new Bundle {
    val A = Input(UInt(32.W))
    val B = Input(UInt(32.W))
    val xorResult = Output(UInt(32.W))
    val orResult = Output(UInt(32.W))
    val andResult = Output(UInt(32.W))
  })
  io.xorResult := io.A ^ io.B
  io.orResult := io.A | io.B
  io.andResult := io.A & io.B
}
