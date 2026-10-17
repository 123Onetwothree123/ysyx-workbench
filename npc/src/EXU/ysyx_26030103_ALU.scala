package ysyx_26030103.exu
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common.ysyx_26030103_ALUFunction._

// ALU 顶层：数据通路按复用性拆成子块。
// Adder/Subtractor/Shifter/BitLogic 并行计算，ResultSelect 做最后的浅 mux；
// SLT/SLTU 不单独做比较器，直接由 Subtractor 的借位派生。
class ysyx_26030103_ALU extends Module {
  val io = IO(new Bundle {
    val A = Input(UInt(32.W))
    val B = Input(UInt(32.W))
    val ALUCtrl = Input(UInt(CtrlWidth.W))
    val result = Output(UInt(32.W))
  })
  val AdderUnit = Module(new ysyx_26030103_ALUAdder)
  val SubtractorUnit = Module(new ysyx_26030103_ALUSubtractor)
  val ShifterUnit = Module(new ysyx_26030103_ALUShifter)
  val BitLogicUnit = Module(new ysyx_26030103_ALUBitLogic)
  val ResultSelectUnit = Module(new ysyx_26030103_ALUResultSelect)

  AdderUnit.io.A := io.A
  AdderUnit.io.B := io.B
  SubtractorUnit.io.A := io.A
  SubtractorUnit.io.B := io.B
  ShifterUnit.io.A := io.A
  ShifterUnit.io.shamt := io.B(4, 0)
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

  io.result := ResultSelectUnit.io.result
}
