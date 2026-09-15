package ysyx_26030103.idu
import chisel3._
import chisel3.util._
import chisel3.util.experimental.decode._
import _root_.ysyx_26030103.common.ysyx_26030103_opcode._
class ysyx_26030103_ALUDecoder extends Module {
  import _root_.ysyx_26030103.common.ysyx_26030103_ALUFunction._
  val io = IO(new Bundle {
    val opcode = Input(UInt(7.W))
    val funct3 = Input(UInt(3.W))
    val funct7 = Input(UInt(7.W))
    val ALUCtrl = Output(UInt(CtrlWidth.W))
    val Illegal = Output(Bool())
  })
  private def encode(ctrl: UInt, illegal: Boolean = false): BitPat =
    BitPat(ctrl) ## BitPat(
      if (illegal) 1.U else 0.U
    ) // 输出就是ALUCtrl高位+Illegal的1bit在低位
  private def key(opcode: UInt, f3f7: String): BitPat =
    BitPat(opcode) ## BitPat(f3f7)
  private val table: TruthTable = TruthTable(
    Seq(
      key(OPCODE_Immediate_Lxxx, "b000_???????") -> encode(
        ADD
      ), // LB
      key(OPCODE_Immediate_Lxxx, "b001_???????") -> encode(ADD), // LH
      key(OPCODE_Immediate_Lxxx, "b010_???????") -> encode(ADD), // LW
      key(OPCODE_Immediate_Lxxx, "b100_???????") -> encode(ADD), // LBU
      key(OPCODE_Immediate_Lxxx, "b101_???????") -> encode(ADD), // LHU
      key(OPCODE_Store, "b000_???????") -> encode(ADD), // SB
      key(OPCODE_Store, "b001_???????") -> encode(ADD), // SH
      key(OPCODE_Store, "b010_???????") -> encode(ADD), // SW
      key(OPCODE_Immediate_Bxxx, "b000_???????") -> encode(ADD), // JALR
      key(OPCODE_UpperImmediate_auipc, "b???_???????") -> encode(
        ADD
      ), // AUIPC这边funct3和funct7属于立即数
      key(OPCODE_Jump, "b???_???????") -> encode(ADD), // JAL
      key(OPCODE_UpperImmediate_lui, "b???_???????") -> encode(ADD), // LUI
      key(OPCODE_Branch, "b000_???????") -> encode(SUB), // BEQ
      key(OPCODE_Branch, "b001_???????") -> encode(SUB), // BNE
      key(OPCODE_Branch, "b100_???????") -> encode(SUB), // BLT
      key(OPCODE_Branch, "b101_???????") -> encode(SUB), // BGE
      key(OPCODE_Branch, "b110_???????") -> encode(SUB), // BLTU
      key(OPCODE_Branch, "b111_???????") -> encode(SUB), // BGEU
      key(OPCODE_Immediate, "b000_???????") -> encode(
        ADD
      ), // ADDI这边funct7是立即数高位
      key(OPCODE_Immediate, "b001_0000000") -> encode(
        SLL
      ), // SLLI这里移位量立即数要求 funct7是全0
      key(OPCODE_Immediate, "b010_???????") -> encode(SLT), // SLTI
      key(OPCODE_Immediate, "b011_???????") -> encode(SLTU), // SLTIU
      key(OPCODE_Immediate, "b100_???????") -> encode(XOR), // XORI
      key(OPCODE_Immediate, "b101_0000000") -> encode(SRL), // SRLI
      key(OPCODE_Immediate, "b101_0100000") -> encode(SRA), // SRAI
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
      key(OPCODE_System, "b???_???????") -> encode(
        NOP
      ), // CSR和ecall和ebreak和mret有没有用就交给 IDU
      key(OPCODE_MiscMem, "b???_???????") -> encode(NOP) // fence和fence.i同上
    ),
    encode(
      NOP,
      illegal = true
    ) // 这是AI写的，AI给的建议，直接写就可以，不需要判断：所有行都不命中: ALUCtrl=NOP 且 Illegal=1
  )
  private val decoded =
    decoder(
      QMCMinimizer, // 卡诺图
      Cat(io.opcode, io.funct3, io.funct7),
      table
    )
  io.ALUCtrl := decoded(CtrlWidth, 1)
  io.Illegal := decoded(0)
}
