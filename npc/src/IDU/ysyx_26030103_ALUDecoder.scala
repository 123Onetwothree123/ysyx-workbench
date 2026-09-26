package ysyx_26030103.idu
import chisel3._
import chisel3.util._
import chisel3.util.experimental.decode._
import _root_.ysyx_26030103.common.ysyx_26030103_opcode._
import _root_.ysyx_26030103.common.ysyx_26030103_NPCConfig
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
    REMU
  }
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
  // 这是AI写的了：输出布局(低位到高位): Illegal(0) | ALUCtrl(4:1) | IsMDU(5) | MDUOp(8:6)
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
  private val BaseRows: Seq[(BitPat, BitPat)] = Seq(
    key(OPCODE_Immediate_Lxxx, "b000_???????") -> encode(
      ADD
    ), // 字节加载（LB）
    key(OPCODE_Immediate_Lxxx, "b001_???????") -> encode(ADD), // 半字加载（LH）
    key(OPCODE_Immediate_Lxxx, "b010_???????") -> encode(ADD), // 字加载（LW）
    key(OPCODE_Immediate_Lxxx, "b100_???????") -> encode(ADD), // 无符号字节加载（LBU）
    key(OPCODE_Immediate_Lxxx, "b101_???????") -> encode(ADD), // 无符号半字加载（LHU）
    key(OPCODE_Store, "b000_???????") -> encode(ADD), // 字节存储（SB）
    key(OPCODE_Store, "b001_???????") -> encode(ADD), // 半字存储（SH）
    key(OPCODE_Store, "b010_???????") -> encode(ADD), // 字存储（SW）
    key(OPCODE_Immediate_Bxxx, "b000_???????") -> encode(ADD), // 间接跳转并链接（JALR）
    key(OPCODE_UpperImmediate_auipc, "b???_???????") -> encode(
      ADD
    ), // AUIPC这边funct3和funct7属于立即数
    key(OPCODE_Jump, "b???_???????") -> encode(ADD), // 跳转并链接（JAL）
    key(OPCODE_UpperImmediate_lui, "b???_???????") -> encode(ADD), // 高位立即数加载（LUI）
    key(OPCODE_Branch, "b000_???????") -> encode(SUB), // 相等分支（BEQ）
    key(OPCODE_Branch, "b001_???????") -> encode(SUB), // 不等分支（BNE）
    key(OPCODE_Branch, "b100_???????") -> encode(SUB), // 有符号小于分支（BLT）
    key(OPCODE_Branch, "b101_???????") -> encode(SUB), // 有符号大于等于分支（BGE）
    key(OPCODE_Branch, "b110_???????") -> encode(SUB), // 无符号小于分支（BLTU）
    key(OPCODE_Branch, "b111_???????") -> encode(SUB), // 无符号大于等于分支（BGEU）
    key(OPCODE_Immediate, "b000_???????") -> encode(
      ADD
    ), // ADDI这边funct7是立即数高位
    key(OPCODE_Immediate, "b001_0000000") -> encode(
      SLL
    ), // SLLI这里移位量立即数要求 funct7是全0
    key(OPCODE_Immediate, "b010_???????") -> encode(SLT), // 有符号小于立即数置位（SLTI）
    key(OPCODE_Immediate, "b011_???????") -> encode(SLTU), // 无符号小于立即数置位（SLTIU）
    key(OPCODE_Immediate, "b100_???????") -> encode(XOR), // 立即数异或（XORI）
    key(OPCODE_Immediate, "b101_0000000") -> encode(SRL), // 逻辑右移立即数（SRLI）
    key(OPCODE_Immediate, "b101_0100000") -> encode(SRA), // 算术右移立即数（SRAI）
    key(OPCODE_Immediate, "b110_???????") -> encode(OR), // 立即数或（ORI）
    key(OPCODE_Immediate, "b111_???????") -> encode(AND), // 立即数与（ANDI）
    key(OPCODE_Register, "b000_0000000") -> encode(ADD), // 加法（ADD）
    key(OPCODE_Register, "b000_0100000") -> encode(SUB), // 减法（SUB）
    key(OPCODE_Register, "b001_0000000") -> encode(SLL), // 逻辑左移（SLL）
    key(OPCODE_Register, "b010_0000000") -> encode(SLT), // 有符号小于置位（SLT）
    key(OPCODE_Register, "b011_0000000") -> encode(SLTU), // 无符号小于置位（SLTU）
    key(OPCODE_Register, "b100_0000000") -> encode(XOR), // 异或（XOR）
    key(OPCODE_Register, "b101_0000000") -> encode(SRL), // 逻辑右移（SRL）
    key(OPCODE_Register, "b101_0100000") -> encode(SRA), // 算术右移（SRA）
    key(OPCODE_Register, "b110_0000000") -> encode(OR), // 或（OR）
    key(OPCODE_Register, "b111_0000000") -> encode(AND), // 与（AND）
    key(OPCODE_System, "b???_???????") -> encode(
      NOP
    ), // CSR和ecall和ebreak和mret有没有用就交给 IDU
    key(OPCODE_MiscMem, "b???_???????") -> encode(NOP) // fence和fence.i同上
  )
  // M扩展
  // 这个思路是AI给的：ALUCtrl填NOP: 这些指令由EXU按IsMDU路由到MDU, 不使用ALU结果
  private val MDURows: Seq[(BitPat, BitPat)] =
    if (config.UseM) {
      Seq(
        key(OPCODE_Register, "b000_0000001") -> encode(
          NOP,
          isMDU = true,
          mduOp = MUL
        ),
        key(OPCODE_Register, "b001_0000001") -> encode(
          NOP,
          isMDU = true,
          mduOp = MULH
        ),
        key(OPCODE_Register, "b010_0000001") -> encode(
          NOP,
          isMDU = true,
          mduOp = MULHSU
        ),
        key(OPCODE_Register, "b011_0000001") -> encode(
          NOP,
          isMDU = true,
          mduOp = MULHU
        ),
        key(OPCODE_Register, "b100_0000001") -> encode(
          NOP,
          isMDU = true,
          mduOp = DIV
        ),
        key(OPCODE_Register, "b101_0000001") -> encode(
          NOP,
          isMDU = true,
          mduOp = DIVU
        ),
        key(OPCODE_Register, "b110_0000001") -> encode(
          NOP,
          isMDU = true,
          mduOp = REM
        ),
        key(OPCODE_Register, "b111_0000001") -> encode(
          NOP,
          isMDU = true,
          mduOp = REMU
        )
      )
    } else {
      Nil
    }
  private val table: TruthTable = TruthTable(
    BaseRows ++ MDURows,
    encode(
      NOP,
      illegal = true
    ) // 所有行都不命中: ALUCtrl=NOP, Illegal=1, IsMDU=0
  )
  private val decoded =
    decoder(
      QMCMinimizer, // 卡诺图
      Cat(io.opcode, io.funct3, io.funct7),
      table
    )
  io.ALUCtrl := decoded(CtrlWidth, 1)
  io.Illegal := decoded(0)
  io.IsMDU := decoded(CtrlWidth + 1)
  io.MDUOp := decoded(CtrlWidth + 1 + ysyx_26030103_MDUOp.Width, CtrlWidth + 2)
}
