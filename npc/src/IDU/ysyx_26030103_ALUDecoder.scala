package ysyx_26030103.idu
import chisel3._
import chisel3.util._
import chisel3.util.experimental.decode._
import _root_.ysyx_26030103.common.ysyx_26030103_opcode._
import _root_.ysyx_26030103.config.ysyx_26030103_NPCConfig
import _root_.ysyx_26030103.config.ysyx_26030103_IDUDecodeMinimizer
import _root_.ysyx_26030103.common.ysyx_26030103_MDUOp
class ysyx_26030103_ALUDecoder(
    val config: ysyx_26030103_NPCConfig = ysyx_26030103_NPCConfig()
) extends Module {
  import _root_.ysyx_26030103.common.ysyx_26030103_ALUFunction._
  import _root_.ysyx_26030103.common.ysyx_26030103_MDUOp.{
    MUL,
    MULH,
    MULHSU,
    MULHU,
    DIV,
    DIVU,
    REM,
    REMU,
    MULW,
    DIVW,
    DIVUW,
    REMW,
    REMUW
  }
  private val XLen64 = config.XLEN == 64
  val io = IO(new Bundle {
    val opcode = Input(UInt(7.W))
    val funct3 = Input(UInt(3.W))
    val funct7 = Input(UInt(7.W))
    val ALUCtrl = Output(UInt(CtrlWidth.W))
    val Illegal = Output(Bool())
    val IsMDU = Output(Bool())
    val MDUOp = Output(UInt(ysyx_26030103_MDUOp.Width.W))
  })
  private def encode(
      ctrl: UInt,
      illegal: Boolean = false,
      isMDU: Boolean = false,
      mduOp: UInt = 0.U(ysyx_26030103_MDUOp.Width.W)
  ): BitPat =
    BitPat(mduOp) ## BitPat(if (isMDU) 1.U else 0.U) ## BitPat(ctrl) ## BitPat(
      if (illegal) 1.U else 0.U
    )
  private def key(opcode: UInt, f3f7: String): BitPat =
    BitPat(opcode) ## BitPat(f3f7)

  private val SlliKey = if (XLen64) "b001_000000?" else "b001_0000000"
  private val SrliKey = if (XLen64) "b101_000000?" else "b101_0000000"
  private val SraiKey = if (XLen64) "b101_010000?" else "b101_0100000"

  private val BaseRows: Seq[(BitPat, BitPat)] = Seq(
    key(OPCODE_Immediate_Lxxx, "b000_???????") -> encode(ADD), // LB
    key(OPCODE_Immediate_Lxxx, "b001_???????") -> encode(ADD), // LH
    key(OPCODE_Immediate_Lxxx, "b010_???????") -> encode(ADD), // LW
    key(OPCODE_Immediate_Lxxx, "b100_???????") -> encode(ADD), // LBU
    key(OPCODE_Immediate_Lxxx, "b101_???????") -> encode(ADD), // LHU
    key(OPCODE_Store, "b000_???????") -> encode(ADD), // SB
    key(OPCODE_Store, "b001_???????") -> encode(ADD), // SH
    key(OPCODE_Store, "b010_???????") -> encode(ADD), // SW
    key(OPCODE_Immediate_Bxxx, "b000_???????") -> encode(ADD), // JALR
    key(OPCODE_UpperImmediate_auipc, "b???_???????") -> encode(ADD), // AUIPC
    key(OPCODE_Jump, "b???_???????") -> encode(ADD), // JAL
    key(OPCODE_UpperImmediate_lui, "b???_???????") -> encode(ADD), // LUI
    key(OPCODE_Branch, "b000_???????") -> encode(SUB), // BEQ
    key(OPCODE_Branch, "b001_???????") -> encode(SUB), // BNE
    key(OPCODE_Branch, "b100_???????") -> encode(SUB), // BLT
    key(OPCODE_Branch, "b101_???????") -> encode(SUB), // BGE
    key(OPCODE_Branch, "b110_???????") -> encode(SUB), // BLTU
    key(OPCODE_Branch, "b111_???????") -> encode(SUB), // BGEU
    key(OPCODE_Immediate, "b000_???????") -> encode(ADD), // ADDI
    key(OPCODE_Immediate, SlliKey) -> encode(SLL), // SLLI
    key(OPCODE_Immediate, "b010_???????") -> encode(SLT), // SLTI
    key(OPCODE_Immediate, "b011_???????") -> encode(SLTU), // SLTIU
    key(OPCODE_Immediate, "b100_???????") -> encode(XOR), // XORI
    key(OPCODE_Immediate, SrliKey) -> encode(SRL), // SRLI
    key(OPCODE_Immediate, SraiKey) -> encode(SRA), // SRAI
    key(OPCODE_Immediate, "b110_???????") -> encode(OR), // ORI
    key(OPCODE_Immediate, "b111_???????") -> encode(AND), // ANDI
    key(OPCODE_Register, "b000_0000000") -> encode(ADD), // ADD
    key(OPCODE_Register, "b000_0100000") -> encode(SUB), // SUB
    key(OPCODE_Register, "b001_0000000") -> encode(SLL), // SLL
    key(OPCODE_Register, "b010_0000000") -> encode(SLT), // SLT
    key(OPCODE_Register, "b011_0000000") -> encode(SLTU), // SLTU
    key(OPCODE_Register, "b100_0000000") -> encode(XOR), // XOR
    key(OPCODE_Register, "b101_0000000") -> encode(SRL), // SRL
    key(OPCODE_Register, "b101_0100000") -> encode(SRA), // SRA
    key(OPCODE_Register, "b110_0000000") -> encode(OR), // OR
    key(OPCODE_Register, "b111_0000000") -> encode(AND), // AND
    key(OPCODE_System, "b???_???????") -> encode(NOP), // SYSTEM/CSR
    key(OPCODE_MiscMem, "b???_???????") -> encode(NOP) // FENCE
  )

  private val RV64Rows: Seq[(BitPat, BitPat)] = if (XLen64) {
    Seq(
      key(OPCODE_Immediate_Lxxx, "b011_???????") -> encode(ADD), // LD
      key(OPCODE_Immediate_Lxxx, "b110_???????") -> encode(ADD), // LWU
      key(OPCODE_Store, "b011_???????") -> encode(ADD), // SD
      key(OPCODE_ImmediateWord, "b000_???????") -> encode(ADDW), // ADDIW
      key(OPCODE_ImmediateWord, "b001_0000000") -> encode(SLLW), // SLLIW
      key(OPCODE_ImmediateWord, "b101_0000000") -> encode(SRLW), // SRLIW
      key(OPCODE_ImmediateWord, "b101_0100000") -> encode(SRAW), // SRAIW
      key(OPCODE_RegisterWord, "b000_0000000") -> encode(ADDW), // ADDW
      key(OPCODE_RegisterWord, "b000_0100000") -> encode(SUBW), // SUBW
      key(OPCODE_RegisterWord, "b001_0000000") -> encode(SLLW), // SLLW
      key(OPCODE_RegisterWord, "b101_0000000") -> encode(SRLW), // SRLW
      key(OPCODE_RegisterWord, "b101_0100000") -> encode(SRAW) // SRAW
    )
  } else Nil

  private val MDURows: Seq[(BitPat, BitPat)] = if (config.UseM) {
    Seq(
      key(OPCODE_Register, "b000_0000001") -> encode(NOP, isMDU = true, mduOp = MUL), // MUL
      key(OPCODE_Register, "b001_0000001") -> encode(NOP, isMDU = true, mduOp = MULH), // MULH
      key(OPCODE_Register, "b010_0000001") -> encode(NOP, isMDU = true, mduOp = MULHSU), // MULHSU
      key(OPCODE_Register, "b011_0000001") -> encode(NOP, isMDU = true, mduOp = MULHU), // MULHU
      key(OPCODE_Register, "b100_0000001") -> encode(NOP, isMDU = true, mduOp = DIV), // DIV
      key(OPCODE_Register, "b101_0000001") -> encode(NOP, isMDU = true, mduOp = DIVU), // DIVU
      key(OPCODE_Register, "b110_0000001") -> encode(NOP, isMDU = true, mduOp = REM), // REM
      key(OPCODE_Register, "b111_0000001") -> encode(NOP, isMDU = true, mduOp = REMU) // REMU
    ) ++ (if (XLen64) Seq(
      key(OPCODE_RegisterWord, "b000_0000001") -> encode(NOP, isMDU = true, mduOp = MULW), // MULW
      key(OPCODE_RegisterWord, "b100_0000001") -> encode(NOP, isMDU = true, mduOp = DIVW), // DIVW
      key(OPCODE_RegisterWord, "b101_0000001") -> encode(NOP, isMDU = true, mduOp = DIVUW), // DIVUW
      key(OPCODE_RegisterWord, "b110_0000001") -> encode(NOP, isMDU = true, mduOp = REMW), // REMW
      key(OPCODE_RegisterWord, "b111_0000001") -> encode(NOP, isMDU = true, mduOp = REMUW) // REMUW
    ) else Nil)
  } else Nil

  private val AtomicRows: Seq[(BitPat, BitPat)] = if (config.UseA) {
    Seq(
      key(OPCODE_Amo, "b010_???????") -> encode(ADD) // AMO/LR/SC.W
    ) ++ (if (XLen64) Seq(
      key(OPCODE_Amo, "b011_???????") -> encode(ADD) // AMO/LR/SC.D
    ) else Nil)
  } else Nil

  private val FpRows: Seq[(BitPat, BitPat)] = if (config.UseF) {
    Seq(
      key(OPCODE_LoadFP, "b???_???????") -> encode(ADD), // FLW/FLD
      key(OPCODE_StoreFP, "b???_???????") -> encode(ADD), // FSW/FSD
      key(OPCODE_OpFP, "b???_???????") -> encode(NOP), // OP-FP
      key(OPCODE_Fmadd, "b???_???????") -> encode(NOP), // FMADD
      key(OPCODE_Fmsub, "b???_???????") -> encode(NOP), // FMSUB
      key(OPCODE_Fnmsub, "b???_???????") -> encode(NOP), // FNMSUB
      key(OPCODE_Fnmadd, "b???_???????") -> encode(NOP) // FNMADD
    )
  } else Nil

  private val table: TruthTable = TruthTable(
    BaseRows ++ RV64Rows ++ MDURows ++ AtomicRows ++ FpRows,
    encode(NOP, illegal = true)
  )
  private val decodeInput = Cat(io.opcode, io.funct3, io.funct7)
  private def decodeWith(minimizer: Minimizer): UInt =
    try {
      decoder(minimizer, decodeInput, table)
    } catch {
      case e: java.io.IOException =>
        throw new RuntimeException(
          s"IDU_DECODE_MINIMIZER=${config.IDUDecodeMinimizer.Name} " +
            s"执行失败：${e.getMessage}",
          e
        )
    }
  private val decoded = config.IDUDecodeMinimizer match {
    case ysyx_26030103_IDUDecodeMinimizer.QMC =>
      decoder(QMCMinimizer, decodeInput, table)
    case ysyx_26030103_IDUDecodeMinimizer.Espresso =>
      try {
        decoder(EspressoMinimizer, decodeInput, table)
      } catch {
        case EspressoNotFoundException =>
          throw new RuntimeException(
            "IDU_DECODE_MINIMIZER=espresso 但 PATH 中找不到 espresso；" +
              "请安装 espresso，或改用 auto/qmc"
          )
        case e: java.io.IOException =>
          throw new RuntimeException(
            s"IDU_DECODE_MINIMIZER=espresso 执行失败：${e.getMessage}",
            e
          )
      }
    case ysyx_26030103_IDUDecodeMinimizer.Auto =>
      decoder(decodeInput, table)
    case ysyx_26030103_IDUDecodeMinimizer.None =>
      decoder(ysyx_26030103_IdentityMinimizer, decodeInput, table)
    case ysyx_26030103_IDUDecodeMinimizer.Bdd =>
      decodeWith(ysyx_26030103_AbcBddMinimizer)
    case ysyx_26030103_IDUDecodeMinimizer.Sat =>
      decodeWith(ysyx_26030103_AbcSatMinimizer)
  }
  io.ALUCtrl := decoded(CtrlWidth, 1)
  io.Illegal := decoded(0)
  io.IsMDU := decoded(CtrlWidth + 1)
  io.MDUOp := decoded(CtrlWidth + 1 + ysyx_26030103_MDUOp.Width, CtrlWidth + 2)
}
