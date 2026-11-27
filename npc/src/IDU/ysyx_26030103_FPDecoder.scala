package ysyx_26030103.idu
import chisel3._
import chisel3.util._
import chisel3.util.experimental.decode._
import _root_.ysyx_26030103.config.ysyx_26030103_NPCConfig
import _root_.ysyx_26030103.fpu.{
  ysyx_26030103_FPUOp,
  ysyx_26030103_FPUFmt
}
class ysyx_26030103_FPDecoder(
    val config: ysyx_26030103_NPCConfig = ysyx_26030103_NPCConfig()
) extends Module {
  import _root_.ysyx_26030103.fpu.ysyx_26030103_FPUOp._
  private val xlen64 = config.XLEN == 64
  val io = IO(new Bundle {
    val op5 = Input(UInt(5.W))
    val funct3 = Input(UInt(3.W))
    val rs2 = Input(UInt(5.W))
    val fmt = Input(UInt(ysyx_26030103_FPUFmt.Width.W))
    val legal = Output(Bool())
    val rm = Output(Bool())
    val uses1 = Output(Bool())
    val uses2 = Output(Bool())
    val uses3 = Output(Bool())
    val writesFPR = Output(Bool())
    val writesGPR = Output(Bool())
    val funct3Ok = Output(Bool())
    val rs2Ok = Output(Bool())
    val gprSource1 = Output(Bool())
    val mvOp = Output(Bool())
    val cvtFpFmt = Output(Bool())
    val op = Output(UInt(ysyx_26030103_FPUOp.Width.W))
  })

  private val F3Any = 0
  private val F3Le2 = 1
  private val F3Le1 = 2
  private val F3Zero = 3
  private val Rs2Any = 0
  private val Rs2Zero = 1
  private val Rs2CvtFmt = 2
  private val Rs2IntWidth = 3
  private val OpFixed = 0
  private val OpAddF3 = 1
  private val OpSubF3 = 2
  private val OpClass = 3
  private val OpSign = 4

  private def flag(value: Boolean): BitPat = BitPat(if (value) "b1" else "b0")
  private def bits(value: Int, width: Int): BitPat =
    BitPat("b" + (width - 1 to 0 by -1)
      .map(i => if (((value >> i) & 1) == 0) '0' else '1').mkString)

  private def encode(
      opBase: UInt,
      legal: Boolean = true,
      rm: Boolean = false,
      uses1: Boolean = true,
      uses2: Boolean = false,
      uses3: Boolean = false,
      writesFPR: Boolean = false,
      writesGPR: Boolean = false,
      funct3Rule: Int = F3Any,
      rs2Rule: Int = Rs2Any,
      gprSource1: Boolean = false,
      mvOp: Boolean = false,
      cvtFpFmt: Boolean = false,
      opMode: Int = OpFixed
  ): BitPat =
    flag(legal) ## flag(rm) ## flag(uses1) ## flag(uses2) ## flag(uses3) ##
      flag(writesFPR) ## flag(writesGPR) ## bits(funct3Rule, 2) ##
      bits(rs2Rule, 2) ## flag(cvtFpFmt) ## flag(mvOp) ## flag(gprSource1) ##
      bits(opMode, 3) ## BitPat(opBase)

  private def key(op5: Int): BitPat = BitPat(op5.U(5.W))
  private val rows: Seq[(BitPat, BitPat)] = Seq(
    key(0) -> encode(FAdd, rm = true, uses2 = true, writesFPR = true),
    key(1) -> encode(FSub, rm = true, uses2 = true, writesFPR = true),
    key(2) -> encode(FMul, rm = true, uses2 = true, writesFPR = true),
    key(3) -> encode(FDiv, rm = true, uses2 = true, writesFPR = true),
    key(4) -> encode(FSgnj, uses2 = true, writesFPR = true,
      funct3Rule = F3Le2, opMode = OpAddF3),
    key(5) -> encode(FMin, uses2 = true, writesFPR = true,
      funct3Rule = F3Le1, opMode = OpAddF3),
    key(8) -> encode(FCvtFPToFP, rm = true, writesFPR = true,
      rs2Rule = Rs2CvtFmt, cvtFpFmt = true),
    key(11) -> encode(FSqrt, rm = true, writesFPR = true, rs2Rule = Rs2Zero),
    key(20) -> encode(FLe, uses2 = true, writesGPR = true,
      funct3Rule = F3Le2, opMode = OpSubF3),
    key(24) -> encode(FCvtFPToInt, rm = true, writesGPR = true,
      rs2Rule = Rs2IntWidth, opMode = OpSign),
    key(26) -> encode(FCvtIntToFP, rm = true, uses1 = false, writesFPR = true,
      rs2Rule = Rs2IntWidth, gprSource1 = true, opMode = OpSign),
    key(28) -> encode(FMvFPToInt, writesGPR = true, funct3Rule = F3Le1,
      rs2Rule = Rs2Zero, mvOp = true, opMode = OpClass),
    key(30) -> encode(FMvIntToFP, uses1 = false, writesFPR = true,
      funct3Rule = F3Zero, rs2Rule = Rs2Zero, gprSource1 = true, mvOp = true)
  )
  private val table: TruthTable =
    TruthTable(rows, encode(0.U(ysyx_26030103_FPUOp.Width.W), legal = false))

  private val decoded = decoder.qmc(io.op5, table)
  private val funct3Rule = decoded(15, 14)
  private val rs2Rule = decoded(13, 12)
  private val opMode = decoded(8, 6)
  private val opBase = decoded(5, 0)

  io.legal := decoded(22)
  io.rm := decoded(21)
  io.uses1 := decoded(20)
  io.uses2 := decoded(19)
  io.uses3 := decoded(18)
  io.writesFPR := decoded(17)
  io.writesGPR := decoded(16)
  io.cvtFpFmt := decoded(11)
  io.mvOp := decoded(10)
  io.gprSource1 := decoded(9)
  io.funct3Ok := MuxLookup(funct3Rule, true.B)(Seq(
    F3Le2.U(2.W) -> (io.funct3 <= 2.U),
    F3Le1.U(2.W) -> (io.funct3 <= 1.U),
    F3Zero.U(2.W) -> (io.funct3 === 0.U)
  ))
  io.rs2Ok := MuxLookup(rs2Rule, true.B)(Seq(
    Rs2Zero.U(2.W) -> (io.rs2 === 0.U),
    Rs2CvtFmt.U(2.W) -> (io.rs2 === Mux(io.fmt === ysyx_26030103_FPUFmt.S,
      1.U, 0.U)),
    Rs2IntWidth.U(2.W) -> (io.rs2 <= Mux(xlen64.B, 3.U, 1.U))
  ))
  io.op := MuxLookup(opMode, opBase)(Seq(
    OpAddF3.U(3.W) -> (opBase + io.funct3),
    OpSubF3.U(3.W) -> (opBase - io.funct3),
    OpClass.U(3.W) -> Mux(io.funct3 === 1.U, FClass, FMvFPToInt),
    OpSign.U(3.W) -> (opBase | io.rs2(0))
  ))
}
