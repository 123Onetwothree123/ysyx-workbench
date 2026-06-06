package RV32I.ALU
import chisel3._
import chisel3.util._
class ALU extends Module {
  val io = IO(new Bundle {
    val A = Input(UInt(32.W))
    val B = Input(UInt(32.W))
    val ALUCtrl = Input(UInt(4.W))
    val result = Output(UInt(32.W))
  })
  val ALUCTRL_ADD = 0.U(4.W)
  val ALUCTRL_SUB = 1.U(4.W)
  val ALUCTRL_SLL = 2.U(4.W)
  val ALUCTRL_SLT = 3.U(4.W)
  val ALUCTRL_SLTU = 4.U(4.W)
  val ALUCTRL_XOR = 5.U(4.W)
  val ALUCTRL_SRL = 6.U(4.W)
  val ALUCTRL_SRA = 7.U(4.W)
  val ALUCTRL_OR = 8.U(4.W)
  val ALUCTRL_AND = 9.U(4.W)
  val ALUCTRL_NOP = 15.U(4.W)
  io.result := MuxLookup(
    io.ALUCtrl,
    0.U(32.W)
  )(
    Seq(
      ALUCTRL_ADD -> (io.A + io.B),
      ALUCTRL_SUB -> (io.A - io.B),
      ALUCTRL_SLL -> (io.A << io.B(4, 0)),
      ALUCTRL_SLT -> Mux(io.A.asSInt < io.B.asSInt, 1.U(32.W), 0.U(32.W)),
      ALUCTRL_SLTU -> Mux(io.A < io.B, 1.U(32.W), 0.U(32.W)),
      ALUCTRL_XOR -> (io.A ^ io.B),
      ALUCTRL_SRL -> (io.A >> io.B(4, 0)),
      ALUCTRL_SRA -> (io.A.asSInt >> io.B(4, 0)).asUInt,
      ALUCTRL_OR -> (io.A | io.B),
      ALUCTRL_AND -> (io.A & io.B)
    )
  )
}
