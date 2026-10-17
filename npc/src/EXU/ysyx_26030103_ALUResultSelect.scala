package ysyx_26030103.exu
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common.ysyx_26030103_ALUFunction._

// ALU 结果选择：各子块并行算完后的浅 mux。
// SLT/SLTU 由减法器给出的比较位在这里转换成 0/1 结果。
class ysyx_26030103_ALUResultSelect extends Module {
  val io = IO(new Bundle {
    val ALUCtrl = Input(UInt(CtrlWidth.W))
    val adderSum = Input(UInt(32.W))
    val subResult = Input(UInt(32.W))
    val slt = Input(Bool())
    val sltu = Input(Bool())
    val sll = Input(UInt(32.W))
    val srl = Input(UInt(32.W))
    val sra = Input(UInt(32.W))
    val xorResult = Input(UInt(32.W))
    val orResult = Input(UInt(32.W))
    val andResult = Input(UInt(32.W))
    val result = Output(UInt(32.W))
  })
  io.result := MuxLookup(
    io.ALUCtrl,
    0.U(32.W)
  )(
    Seq(
      ADD -> io.adderSum,
      SUB -> io.subResult,
      SLL -> io.sll,
      SLT -> Mux(io.slt, 1.U(32.W), 0.U(32.W)),
      SLTU -> Mux(io.sltu, 1.U(32.W), 0.U(32.W)),
      XOR -> io.xorResult,
      SRL -> io.srl,
      SRA -> io.sra,
      OR -> io.orResult,
      AND -> io.andResult
    )
  )
}
