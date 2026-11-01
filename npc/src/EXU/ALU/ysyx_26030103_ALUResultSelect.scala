package ysyx_26030103.exu
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common.ysyx_26030103_ALUFunction._
//这不是运算单元，单纯是运算单元计算之后的结果选择器
//SLT和SLTU是减法器给出比较位，并且在这里转换为0或者1
//主要是因为SLT和SLTU的比较结果已经是1bit的bool，这里就变成XLEN位的0或者1
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
    if (XLEN == 32) {
      Value
    } else {
      Cat(Fill(XLEN - 32, Value(31)), Value)
    }
  // deepseek真是个蠢材啊，复查代码，居然还是用muxlookup
  // 我现在复查加重写，先把控制码译成之前Verilog时候的one-hot的结构，
  // 再用Mux1H做成AND和OR选择，避免MuxLookup的优先级版本的一大堆mux，
  // 同时让未映射的编码自然落到0
  private val Decoded = UIntToOH(io.ALUCtrl, 1 << CtrlWidth)
  private def Select(code: UInt): Bool = Decoded(code.litValue.toInt)
  // 连隔离也没做，现在也隔离起来，到时候RV32的时候剩面积
  private val WordMappings =
    if (XLEN == 64) {
      Seq(
        Select(ADDW) -> SextWord(io.addw),
        Select(SUBW) -> SextWord(io.subw),
        Select(SLLW) -> SextWord(io.sllw),
        Select(SRLW) -> SextWord(io.srlw),
        Select(SRAW) -> SextWord(io.sraw)
      )
    } else {
      Nil
    }
  io.result := Mux1H(
    Seq(
      Select(ADD) -> io.adderSum,
      Select(SUB) -> io.subResult,
      Select(SLL) -> io.sll,
      Select(SLT) -> Mux(io.slt, 1.U(XLEN.W), 0.U(XLEN.W)),
      Select(SLTU) -> Mux(io.sltu, 1.U(XLEN.W), 0.U(XLEN.W)),
      Select(XOR) -> io.xorResult,
      Select(SRL) -> io.srl,
      Select(SRA) -> io.sra,
      Select(OR) -> io.orResult,
      Select(AND) -> io.andResult,
      Select(NOP) -> 0.U(XLEN.W)
    ) ++ WordMappings
  )
}
