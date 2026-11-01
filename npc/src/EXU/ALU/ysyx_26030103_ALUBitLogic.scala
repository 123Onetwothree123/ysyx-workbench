package ysyx_26030103.exu
import chisel3._
class ysyx_26030103_ALUBitLogic(val XLEN: Int = 32) extends Module {
  require(XLEN == 32 || XLEN == 64, s"ALUBitLogic XLEN 非法: $XLEN")
  val io = IO(new Bundle {
    val A = Input(UInt(XLEN.W))
    val B = Input(UInt(XLEN.W))
    val xorResult = Output(UInt(XLEN.W))
    val orResult = Output(UInt(XLEN.W))
    val andResult = Output(UInt(XLEN.W))
  })
  io.xorResult := io.A ^ io.B
  io.orResult := io.A | io.B
  io.andResult := io.A & io.B
}
