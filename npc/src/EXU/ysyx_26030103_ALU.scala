package ysyx_26030103.exu
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common.ysyx_26030103_ALUFunction._

// ALU 顶层：数据通路按复用性拆成子块。
// Adder/Subtractor/Shifter/BitLogic 并行计算，ResultSelect 做最后的浅 mux；
// SLT/SLTU 不单独做比较器，直接由 Subtractor 的借位派生。
class ysyx_26030103_ALU(val XLEN: Int = 32) extends Module {
  require(XLEN == 32 || XLEN == 64, s"ALU XLEN 非法: $XLEN")
  private val ShamtWidth = if (XLEN == 64) 6 else 5
  val io = IO(new Bundle {
    val A = Input(UInt(XLEN.W))
    val B = Input(UInt(XLEN.W))
    val ALUCtrl = Input(UInt(CtrlWidth.W))
    val result = Output(UInt(XLEN.W))
  })
  val AdderUnit = Module(new ysyx_26030103_ALUAdder(XLEN))
  val SubtractorUnit = Module(new ysyx_26030103_ALUSubtractor(XLEN))
  val ShifterUnit = Module(new ysyx_26030103_ALUShifter(XLEN))
  val BitLogicUnit = Module(new ysyx_26030103_ALUBitLogic(XLEN))
  val ResultSelectUnit = Module(new ysyx_26030103_ALUResultSelect(XLEN))

  AdderUnit.io.A := io.A
  AdderUnit.io.B := io.B
  SubtractorUnit.io.A := io.A
  SubtractorUnit.io.B := io.B
  ShifterUnit.io.A := io.A
  // 普通移位取低 6 位（RV64）或低 5 位（RV32）；W 型在 Shifter 内只取低 5 位。
  ShifterUnit.io.shamt := io.B(ShamtWidth - 1, 0)
  BitLogicUnit.io.A := io.A
  BitLogicUnit.io.B := io.B

  ResultSelectUnit.io.ALUCtrl := io.ALUCtrl
  ResultSelectUnit.io.adderSum := AdderUnit.io.sum
  ResultSelectUnit.io.subResult := SubtractorUnit.io.sub
  ResultSelectUnit.io.slt := SubtractorUnit.io.slt
  ResultSelectUnit.io.sltu := SubtractorUnit.io.sltu
  ResultSelectUnit.io.sll := ShifterUnit.io.sll
  ResultSelectUnit.io.srl := ShifterUnit.io.srl
  ResultSelectUnit.io.sra := ShifterUnit.io.sra
  ResultSelectUnit.io.xorResult := BitLogicUnit.io.xorResult
  ResultSelectUnit.io.orResult := BitLogicUnit.io.orResult
  ResultSelectUnit.io.andResult := BitLogicUnit.io.andResult
  ResultSelectUnit.io.addw := AdderUnit.io.sumWord
  ResultSelectUnit.io.subw := SubtractorUnit.io.subWord
  ResultSelectUnit.io.sllw := ShifterUnit.io.sllw
  ResultSelectUnit.io.srlw := ShifterUnit.io.srlw
  ResultSelectUnit.io.sraw := ShifterUnit.io.sraw

  io.result := ResultSelectUnit.io.result
}
