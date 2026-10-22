package ysyx_26030103.exu
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common.ysyx_26030103_ALUFunction._

// ALU 结果选择：各子块并行算完后的浅 mux。
// SLT/SLTU 由减法器给出的比较位在这里转换成 0/1 结果。
class ysyx_26030103_ALUResultSelect(val XLEN: Int = 32) extends Module {
  require(XLEN == 32 || XLEN == 64, s"ALUResultSelect XLEN 非法: $XLEN")
  val io = IO(new Bundle {
    val ALUCtrl = Input(UInt(CtrlWidth.W))
    val adderSum = Input(UInt(XLEN.W))
    val subResult = Input(UInt(XLEN.W))
    val slt = Input(Bool())
    val sltu = Input(Bool())
    val sll = Input(UInt(XLEN.W))
    val srl = Input(UInt(XLEN.W))
    val sra = Input(UInt(XLEN.W))
    val xorResult = Input(UInt(XLEN.W))
    val orResult = Input(UInt(XLEN.W))
    val andResult = Input(UInt(XLEN.W))
    // W 型运算的 32 位原始结果，选择后统一做符号扩展。
    val addw = Input(UInt(32.W))
    val subw = Input(UInt(32.W))
    val sllw = Input(UInt(32.W))
    val srlw = Input(UInt(32.W))
    val sraw = Input(UInt(32.W))
    val result = Output(UInt(XLEN.W))
  })
  private def SextWord(Value: UInt): UInt =
    if (XLEN == 32) Value
    else Cat(Fill(XLEN - 32, Value(31)), Value)
  io.result := MuxLookup(
    io.ALUCtrl,
    0.U(XLEN.W)
  )(
    Seq(
      ADD -> io.adderSum,
      SUB -> io.subResult,
      SLL -> io.sll,
      SLT -> Mux(io.slt, 1.U(XLEN.W), 0.U(XLEN.W)),
      SLTU -> Mux(io.sltu, 1.U(XLEN.W), 0.U(XLEN.W)),
      XOR -> io.xorResult,
      SRL -> io.srl,
      SRA -> io.sra,
      OR -> io.orResult,
      AND -> io.andResult,
      ADDW -> SextWord(io.addw),
      SUBW -> SextWord(io.subw),
      SLLW -> SextWord(io.sllw),
      SRLW -> SextWord(io.srlw),
      SRAW -> SextWord(io.sraw)
    )
  )
}
