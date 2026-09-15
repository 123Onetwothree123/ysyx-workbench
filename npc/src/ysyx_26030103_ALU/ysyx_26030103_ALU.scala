package ysyx_26030103.ysyx_26030103_ALU
import chisel3._
import chisel3.util._
import ysyx_26030103_ALUFunction._
class ysyx_26030103_ALU extends Module {
  val io = IO(new Bundle {
    val A = Input(UInt(32.W))
    val B = Input(UInt(32.W))
    val ALUCtrl = Input(UInt(CtrlWidth.W))
    val result = Output(UInt(32.W))
  })
  io.result := MuxLookup(
    io.ALUCtrl,
    0.U(32.W)
  )(
    Seq(
      ADD -> (io.A + io.B),
      SUB -> (io.A - io.B),
      SLL -> (io.A << io.B(4, 0))(31, 0),
      SLT -> Mux(io.A.asSInt < io.B.asSInt, 1.U(32.W), 0.U(32.W)),
      SLTU -> Mux(io.A < io.B, 1.U(32.W), 0.U(32.W)),
      XOR -> (io.A ^ io.B),
      SRL -> (io.A >> io.B(4, 0)),
      SRA -> (io.A.asSInt >> io.B(4, 0)).asUInt,
      OR -> (io.A | io.B),
      AND -> (io.A & io.B)
    )
  )
}
