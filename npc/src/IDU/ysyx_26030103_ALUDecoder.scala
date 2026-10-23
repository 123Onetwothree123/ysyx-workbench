package ysyx_26030103.idu
import chisel3._
import chisel3.util._
import chisel3.util.experimental.decode._
import _root_.ysyx_26030103.common.ysyx_26030103_opcode._
import _root_.ysyx_26030103.config.ysyx_26030103_NPCConfig
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
    // M扩展
    val IsMDU = Output(Bool())
    val MDUOp = Output(UInt(ysyx_26030103_MDUOp.Width.W))
  })
  // 输出布局(低位到高位): Illegal(0) | ALUCtrl(4:1) | IsMDU(5) | MDUOp(8:6)
  // MDUOp 扩展为 4 位后整体宽度为 10 位。
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

  // RV64 的移位立即数允许 shamt[5] 位于 funct7 的 bit0。RV32 仍要求
  // 该位为 0，保持原有非法编码判定不变。
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
    key(OPCODE_UpperImmediate_auipc, "b???_???????") -> encode(ADD),
    key(OPCODE_Jump, "b???_???????") -> encode(ADD),
    key(OPCODE_UpperImmediate_lui, "b???_???????") -> encode(ADD),
    key(OPCODE_Branch, "b000_???????") -> encode(SUB), // BEQ
    key(OPCODE_Branch, "b001_???????") -> encode(SUB), // BNE
    key(OPCODE_Branch, "b100_???????") -> encode(SUB), // BLT
    key(OPCODE_Branch, "b101_???????") -> encode(SUB), // BGE
    key(OPCODE_Branch, "b110_???????") -> encode(SUB), // BLTU
    key(OPCODE_Branch, "b111_???????") -> encode(SUB), // BGEU
    key(OPCODE_Immediate, "b000_???????") -> encode(ADD), // ADDI
    key(OPCODE_Immediate, SlliKey) -> encode(SLL),
    key(OPCODE_Immediate, "b010_???????") -> encode(SLT), // SLTI
    key(OPCODE_Immediate, "b011_???????") -> encode(SLTU), // SLTIU
    key(OPCODE_Immediate, "b100_???????") -> encode(XOR), // XORI
    key(OPCODE_Immediate, SrliKey) -> encode(SRL),
    key(OPCODE_Immediate, SraiKey) -> encode(SRA),
    key(OPCODE_Immediate, "b110_???????") -> encode(OR), // ORI
    key(OPCODE_Immediate, "b111_???????") -> encode(AND), // ANDI
    key(OPCODE_Register, "b000_0000000") -> encode(ADD),
    key(OPCODE_Register, "b000_0100000") -> encode(SUB),
    key(OPCODE_Register, "b001_0000000") -> encode(SLL),
    key(OPCODE_Register, "b010_0000000") -> encode(SLT),
    key(OPCODE_Register, "b011_0000000") -> encode(SLTU),
    key(OPCODE_Register, "b100_0000000") -> encode(XOR),
    key(OPCODE_Register, "b101_0000000") -> encode(SRL),
    key(OPCODE_Register, "b101_0100000") -> encode(SRA),
    key(OPCODE_Register, "b110_0000000") -> encode(OR),
    key(OPCODE_Register, "b111_0000000") -> encode(AND),
    key(OPCODE_System, "b???_???????") -> encode(NOP),
    key(OPCODE_MiscMem, "b???_???????") -> encode(NOP)
  )

  // RV64I 专属编码：LD/LWU/SD 以及 OP-IMM-32/OP-32。
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

  // M扩展：RV32 只有 OP；RV64 额外支持 OP-32 的 MULW/DIVW/...
  private val MDURows: Seq[(BitPat, BitPat)] = if (config.UseM) {
    Seq(
      key(OPCODE_Register, "b000_0000001") -> encode(NOP, isMDU = true, mduOp = MUL),
      key(OPCODE_Register, "b001_0000001") -> encode(NOP, isMDU = true, mduOp = MULH),
      key(OPCODE_Register, "b010_0000001") -> encode(NOP, isMDU = true, mduOp = MULHSU),
      key(OPCODE_Register, "b011_0000001") -> encode(NOP, isMDU = true, mduOp = MULHU),
      key(OPCODE_Register, "b100_0000001") -> encode(NOP, isMDU = true, mduOp = DIV),
      key(OPCODE_Register, "b101_0000001") -> encode(NOP, isMDU = true, mduOp = DIVU),
      key(OPCODE_Register, "b110_0000001") -> encode(NOP, isMDU = true, mduOp = REM),
      key(OPCODE_Register, "b111_0000001") -> encode(NOP, isMDU = true, mduOp = REMU)
    ) ++ (if (XLen64) Seq(
      key(OPCODE_RegisterWord, "b000_0000001") -> encode(NOP, isMDU = true, mduOp = MULW),
      key(OPCODE_RegisterWord, "b100_0000001") -> encode(NOP, isMDU = true, mduOp = DIVW),
      key(OPCODE_RegisterWord, "b101_0000001") -> encode(NOP, isMDU = true, mduOp = DIVUW),
      key(OPCODE_RegisterWord, "b110_0000001") -> encode(NOP, isMDU = true, mduOp = REMW),
      key(OPCODE_RegisterWord, "b111_0000001") -> encode(NOP, isMDU = true, mduOp = REMUW)
    ) else Nil)
  } else Nil

  // A 扩展：AMO/LR/SC 的地址计算仍使用 ADD；操作号由 IDU 直接取 funct5。
  // funct3 的 .W/.D 合法性也在 IDU 中检查，这里只负责把操作码从
  // “未知指令”集合中放行。
  private val AtomicRows: Seq[(BitPat, BitPat)] = if (config.UseA) {
    Seq(
      key(OPCODE_Amo, "b010_???????") -> encode(ADD)
    ) ++ (if (XLen64) Seq(
      key(OPCODE_Amo, "b011_???????") -> encode(ADD)
    ) else Nil)
  } else Nil

  private val table: TruthTable = TruthTable(
    BaseRows ++ RV64Rows ++ MDURows ++ AtomicRows,
    encode(NOP, illegal = true)
  )
  private val decoded =
    decoder(
      QMCMinimizer,
      Cat(io.opcode, io.funct3, io.funct7),
      table
    )
  io.ALUCtrl := decoded(CtrlWidth, 1)
  io.Illegal := decoded(0)
  io.IsMDU := decoded(CtrlWidth + 1)
  io.MDUOp := decoded(CtrlWidth + 1 + ysyx_26030103_MDUOp.Width, CtrlWidth + 2)
}
