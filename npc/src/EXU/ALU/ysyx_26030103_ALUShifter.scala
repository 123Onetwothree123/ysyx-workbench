package ysyx_26030103.exu
import chisel3._

// ALU 移位器：SLL/SRL/SRA 共用一个桶形移位器。
// RV32 移位量取 B[4:0]，RV64 取 B[5:0]；W 型移位固定只取 B[4:0]。
class ysyx_26030103_ALUShifter(val XLEN: Int = 32) extends Module {
  require(XLEN == 32 || XLEN == 64, s"ALUShifter XLEN 非法: $XLEN")
  private val ShamtWidth = if (XLEN == 64) 6 else 5
  val io = IO(new Bundle {
    val A = Input(UInt(XLEN.W))
    val shamt = Input(UInt(ShamtWidth.W))
    val sll = Output(UInt(XLEN.W))
    val srl = Output(UInt(XLEN.W))
    val sra = Output(UInt(XLEN.W))
    val sllw = Output(UInt(32.W))
    val srlw = Output(UInt(32.W))
    val sraw = Output(UInt(32.W))
  })
  io.sll := (io.A << io.shamt)(XLEN - 1, 0)
  io.srl := io.A >> io.shamt
  io.sra := (io.A.asSInt >> io.shamt).asUInt
  private val WordShift = io.shamt(4, 0)
  io.sllw := (io.A(31, 0) << WordShift)(31, 0)
  io.srlw := io.A(31, 0) >> WordShift
  io.sraw := (io.A(31, 0).asSInt >> WordShift).asUInt
}
