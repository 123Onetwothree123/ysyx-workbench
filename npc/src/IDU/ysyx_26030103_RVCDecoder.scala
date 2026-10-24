package ysyx_26030103.idu

import chisel3._
import chisel3.util._

/** RISC-V C 扩展（RV32C/RV64C）展开器。
  *
  * 输入是取指阶段交付的原始 32 位槽（压缩指令只有低 16 位有效），
  * 输出等价的 32 位基础指令。保留 RVC 的差异语义：
  *   - RV32 的 C.JAL 与 RV64 的 C.ADDIW 共用 funct3=001；
  *   - C.LD/C.SD/C.LDSP/C.SDSP/C.SUBW/C.ADDW 只在 RV64 合法；
  *   - RV32 的 C.FLW/C.FSW/C.FLWSP/C.FSWSP 使用 C.LW/C.SW 的立即数
  *     布局，与 RV64 同编码位的 C.LD/C.SD 互斥；
  *   - C.FLD/C.FSD/C.FLDSP/C.FSDSP 由 D 扩展开关使能，RV32/RV64 共用；
  *   - RV32 的 SLLI/SRLI/SRAI 要求 shamt[5]=0；
  *   - C.ADDI4SPN/C.ADDI16SP/C.LUI 的零立即数、C.ADDIW/C.LWSP/C.LDSP/
  *     C.FLDSP/C.FLWSP 的 rd=0、C.JR 的 rs1=0 都是保留编码，必须显式报非法。
  *
  * F/D 关闭时 C.FLD/C.FSD/C.FLW/C.FSW 及其 SP 形式保持非法，绝不把浮点
  * 压缩编码误当成整数指令执行。
  * 规范允许的 HINT（例如 rd=0 的 C.ADDI/C.LI/C.MV/C.ADD、shamt=0 的
  * 移位）展开为写 x0 的等价指令，正常退休但不产生架构副作用。
  */
class ysyx_26030103_RVCDecoder(
    val XLEN: Int = 32,
    val UseF: Boolean = false,
    val UseD: Boolean = false
) extends Module {
  require(XLEN == 32 || XLEN == 64, s"RVCDecoder XLEN 非法: $XLEN")
  require(!UseD || UseF, "RVCDecoder 的 D 扩展依赖 F 扩展")

  val io = IO(new Bundle {
    // 原始取指槽：只有 IsCompressed=1 时才把低 16 位当作压缩指令。
    val in = Input(UInt(32.W))
    val out = Output(UInt(32.W))
    val illegal = Output(Bool())
    val isCompressed = Output(Bool())
  })

  private val x = io.in(15, 0)
  io.isCompressed := x(1, 0) =/= "b11".U(2.W)

  // ---- 通用寄存器编号 ----
  private val x0 = 0.U(5.W)
  private val ra = 1.U(5.W)
  private val sp = 2.U(5.W)
  private val rd = x(11, 7)
  // 三位压缩寄存器编号扩展到 x8..x15。
  private val rs1p = Cat("b01".U(2.W), x(9, 7))
  private val rs2p = Cat("b01".U(2.W), x(4, 2))
  private val rs2 = x(6, 2)

  // ---- 立即数位拼接（与 RVC 规范的字段重排一一对应）----
  private val immAddi4spn =
    Cat(x(10, 7), x(12, 11), x(5), x(6), "b00".U(2.W)) // 10 位
  private val immLw = Cat(x(5), x(12, 10), x(6), "b00".U(2.W)) // 7 位
  private val immLd =
    Cat(x(6, 5), x(12, 10), "b000".U(3.W)) // 8 位
  // C.FLD/C.FSD 的 8 字节对齐偏移：uimm[7:6|5|4:3] = x[6:5|12|11:10]。
  private val immFld =
    Cat(x(6, 5), x(12), x(11, 10), "b000".U(3.W)) // 8 位
  // C.FLW/C.FSW 共用 C.LW/C.SW 的偏移布局：uimm[5:3|2|6]。
  // （C.FLW/C.FSW 只在 RV32 存在；RV64 同编码位是 C.LD/C.SD。）
  private val immFlw =
    Cat(x(5), x(12, 10), x(6), "b00".U(2.W)) // 7 位
  private val immLwsp =
    Cat(x(3, 2), x(12), x(6, 4), "b00".U(2.W)) // 8 位
  private val immLdsp =
    Cat(x(4, 2), x(12), x(6, 5), "b000".U(3.W)) // 9 位
  // C.FLDSP/C.FSDSP 分别复用 C.LDSP/C.SDSP 的偏移布局。
  private val immFldsp = immLdsp
  private val immSwsp =
    Cat(x(8, 7), x(12, 9), "b00".U(2.W)) // 8 位
  private val immSdsp =
    Cat(x(9, 7), x(12, 10), "b000".U(3.W)) // 9 位
  private val immFsdsp = immSdsp
  // C.ADDI/C.LI/C.ANDI 的 6 位有符号立即数，符号扩展到 12 位。
  private val immAddi = Cat(Fill(7, x(12)), x(6, 2))
  // C.ADDI16SP 的 10 位有符号立即数，再扩展到 12 位。
  private val immAddi16sp10 =
    Cat(Fill(3, x(12)), x(4, 3), x(5), x(2), x(6), "b0000".U(4.W))
  private val immAddi16sp =
    Cat(Fill(2, immAddi16sp10(9)), immAddi16sp10)
  // C.LUI 的 6 位有符号立即数放到 imm[17:12]。
  private val immLui =
    Cat(Fill(15, x(12)), x(6, 2), "b000000000000".U(12.W))
  // C.J/C.JAL 的 21 位偏移。
  private val immJ = Cat(
    Fill(10, x(12)),
    x(8),
    x(10, 9),
    x(6),
    x(7),
    x(2),
    x(11),
    x(5, 3),
    "b0".U(1.W)
  )
  // C.BEQZ/C.BNEZ 的 13 位偏移。
  private val immB = Cat(
    Fill(5, x(12)),
    x(6, 5),
    x(2),
    x(11, 10),
    x(4, 3),
    "b0".U(1.W)
  )
  // RV64 的 6 位移位量；RV32 已通过 illegal 条件拒绝 shamt[5]。
  private val shamt = Cat(x(12), x(6, 2))

  // ---- 32 位指令编码辅助函数 ----
  private def encR(
      opcode: Int,
      rd: UInt,
      funct3: Int,
      rs1: UInt,
      rs2: UInt,
      funct7: Int
  ): UInt =
    Cat(funct7.U(7.W), rs2, rs1, funct3.U(3.W), rd, opcode.U(7.W))

  private def encI(
      opcode: Int,
      rd: UInt,
      funct3: Int,
      rs1: UInt,
      imm: UInt
  ): UInt =
    Cat(imm(11, 0), rs1, funct3.U(3.W), rd, opcode.U(7.W))

  private def encS(
      opcode: Int,
      funct3: Int,
      rs1: UInt,
      rs2: UInt,
      imm: UInt
  ): UInt =
    Cat(imm(11, 5), rs2, rs1, funct3.U(3.W), imm(4, 0), opcode.U(7.W))

  private def encB(
      opcode: Int,
      funct3: Int,
      rs1: UInt,
      rs2: UInt,
      imm: UInt
  ): UInt =
    Cat(
      imm(12),
      imm(10, 5),
      rs2,
      rs1,
      funct3.U(3.W),
      imm(4, 1),
      imm(11),
      opcode.U(7.W)
    )

  private def encU(opcode: Int, rd: UInt, imm: UInt): UInt =
    Cat(imm(31, 12), rd, opcode.U(7.W))

  private def encJ(opcode: Int, rd: UInt, imm: UInt): UInt =
    Cat(
      imm(20),
      imm(10, 1),
      imm(11),
      imm(19, 12),
      rd,
      opcode.U(7.W)
    )

  // ---- 各压缩指令展开候选 ----
  // 象限 0：栈指针相对寻址与压缩寄存器访存。
  private val addi4spn =
    encI(0x13, rs2p, 0, sp, Cat("b00".U(2.W), immAddi4spn))
  private val lw =
    encI(0x03, rs2p, 2, rs1p, Cat("b00000".U(5.W), immLw))
  private val ld =
    encI(0x03, rs2p, 3, rs1p, Cat("b0000".U(4.W), immLd))
  // C.FLD/C.FSD：rd'/rs2' 是 f8..f15，rs1' 仍是 x8..x15。
  private val fld =
    encI(0x07, rs2p, 3, rs1p, Cat("b0000".U(4.W), immFld))
  // C.FLW/C.FSW：RV32 专用，偏移布局与 C.LW/C.SW 完全一致。
  private val flw =
    encI(0x07, rs2p, 2, rs1p, Cat("b00000".U(5.W), immFlw))
  private val sw =
    encS(0x23, 2, rs1p, rs2p, Cat("b00000".U(5.W), immLw))
  private val sd =
    encS(0x23, 3, rs1p, rs2p, Cat("b0000".U(4.W), immLd))
  private val fsd =
    encS(0x27, 3, rs1p, rs2p, Cat("b0000".U(4.W), immFld))
  private val fsw =
    encS(0x27, 2, rs1p, rs2p, Cat("b00000".U(5.W), immFlw))

  // 象限 1：算术、跳转、条件分支与 MISC-ALU。
  private val addi = encI(0x13, rd, 0, rd, immAddi)
  private val addiw = encI(0x1B, rd, 0, rd, immAddi)
  private val jal = encJ(0x6F, ra, immJ) // RV32 C.JAL
  private val li = encI(0x13, rd, 0, x0, immAddi)
  private val addi16sp = encI(0x13, sp, 0, sp, immAddi16sp)
  private val lui = encU(0x37, rd, immLui)
  private val j = encJ(0x6F, x0, immJ)
  private val beqz = encB(0x63, 0, rs1p, x0, immB)
  private val bnez = encB(0x63, 1, rs1p, x0, immB)
  private val srli =
    encI(0x13, rs1p, 5, rs1p, Cat("b000000".U(6.W), shamt))
  private val srai =
    encI(0x13, rs1p, 5, rs1p, Cat("b010000".U(6.W), shamt))
  private val andi = encI(0x13, rs1p, 7, rs1p, immAddi)
  // 压缩寄存器-寄存器运算：bit12=0 是基础字操作；bit12=1 在 RV64
  // 只允许 SUBW(bit6:5=00) 与 ADDW(bit6:5=01)。
  private val sub = encR(0x33, rs1p, 0, rs1p, rs2p, 0x20)
  private val xor = encR(0x33, rs1p, 4, rs1p, rs2p, 0x00)
  private val or = encR(0x33, rs1p, 6, rs1p, rs2p, 0x00)
  private val and = encR(0x33, rs1p, 7, rs1p, rs2p, 0x00)
  private val subw = encR(0x3B, rs1p, 0, rs1p, rs2p, 0x20)
  private val addw = encR(0x3B, rs1p, 0, rs1p, rs2p, 0x00)

  // 象限 2：栈指针相对访存、移位与跳转/寄存器搬移。
  private val slli =
    encI(0x13, rd, 1, rd, Cat("b000000".U(6.W), shamt))
  private val lwsp =
    encI(0x03, rd, 2, sp, Cat("b0000".U(4.W), immLwsp))
  private val ldsp =
    encI(0x03, rd, 3, sp, Cat("b000".U(3.W), immLdsp))
  private val fldsp =
    encI(0x07, rd, 3, sp, Cat("b000".U(3.W), immFldsp))
  private val flwsp =
    encI(0x07, rd, 2, sp, Cat("b0000".U(4.W), immLwsp))
  private val jr = encI(0x67, x0, 0, rd, 0.U(12.W))
  private val mv = encR(0x33, rd, 0, x0, rs2, 0x00)
  private val ebreak = "h00100073".U(32.W)
  private val jalr = encI(0x67, ra, 0, rd, 0.U(12.W))
  private val add = encR(0x33, rd, 0, rd, rs2, 0x00)
  private val swsp =
    encS(0x23, 2, sp, rs2, Cat("b0000".U(4.W), immSwsp))
  private val sdsp =
    encS(0x23, 3, sp, rs2, Cat("b000".U(3.W), immSdsp))
  private val fsdsp =
    encS(0x27, 3, sp, rs2, Cat("b000".U(3.W), immFsdsp))
  private val fswsp =
    encS(0x27, 2, sp, rs2, Cat("b0000".U(4.W), immSwsp))

  private val out = WireDefault(0.U(32.W))
  private val illegal = WireDefault(false.B)

  // 象限 3（低两位=11）不是压缩指令；正常路径不会进入，保留为非法。
  switch(Cat(x(1, 0), x(15, 13))) {
    // ---------------- 象限 0 ----------------
    is("b00_000".U(5.W)) { // C.ADDI4SPN
      out := addi4spn
      illegal := immAddi4spn.orR === 0.U
    }
    is("b00_001".U(5.W)) { // C.FLD（需要 D 扩展）
      if (UseD) { out := fld }
      else { illegal := true.B }
    }
    is("b00_010".U(5.W)) { // C.LW
      out := lw
    }
    is("b00_011".U(5.W)) { // RV64 C.LD / RV32 C.FLW
      if (XLEN == 64) { out := ld }
      else if (UseF) { out := flw }
      else { illegal := true.B }
    }
    is("b00_100".U(5.W)) { // 保留
      illegal := true.B
    }
    is("b00_101".U(5.W)) { // C.FSD（需要 D 扩展）
      if (UseD) { out := fsd }
      else { illegal := true.B }
    }
    is("b00_110".U(5.W)) { // C.SW
      out := sw
    }
    is("b00_111".U(5.W)) { // RV64 C.SD / RV32 C.FSW
      if (XLEN == 64) { out := sd }
      else if (UseF) { out := fsw }
      else { illegal := true.B }
    }

    // ---------------- 象限 1 ----------------
    is("b01_000".U(5.W)) { // C.ADDI（rd=0 为 HINT）
      out := addi
    }
    is("b01_001".U(5.W)) { // RV32 C.JAL / RV64 C.ADDIW
      if (XLEN == 32) {
        out := jal
      } else {
        out := addiw
        // C.ADDIW 写 x0 是保留编码。
        illegal := rd === 0.U
      }
    }
    is("b01_010".U(5.W)) { // C.LI（rd=0 为 HINT）
      out := li
    }
    is("b01_011".U(5.W)) { // C.ADDI16SP / C.LUI
      when(rd === sp) {
        out := addi16sp
        illegal := immAddi16sp10.orR === 0.U
      }.otherwise {
        out := lui
        // C.LUI 的 nzimm=0 是保留编码；rd=0 且非零立即数是 HINT。
        illegal := immAddi.orR === 0.U
      }
    }
    is("b01_100".U(5.W)) { // MISC-ALU
      switch(x(11, 10)) {
        is("b00".U(2.W)) { // C.SRLI
          out := srli
          if (XLEN == 32) { illegal := x(12) }
        }
        is("b01".U(2.W)) { // C.SRAI
          out := srai
          if (XLEN == 32) { illegal := x(12) }
        }
        is("b10".U(2.W)) { // C.ANDI
          out := andi
        }
        is("b11".U(2.W)) { // 寄存器-寄存器
          when(x(12) === 0.U) {
            out := MuxLookup(x(6, 5), sub)(
              Seq(
                "b00".U(2.W) -> sub,
                "b01".U(2.W) -> xor,
                "b10".U(2.W) -> or,
                "b11".U(2.W) -> and
              )
            )
          }.otherwise {
            if (XLEN == 32) {
              // RV32 没有 C.SUBW/C.ADDW，bit12=1 全部保留。
              illegal := true.B
            } else {
              when(x(6, 5) === 0.U) { out := subw }
              .elsewhen(x(6, 5) === 1.U) { out := addw }
              .otherwise { illegal := true.B }
            }
          }
        }
      }
    }
    is("b01_101".U(5.W)) { // C.J
      out := j
    }
    is("b01_110".U(5.W)) { // C.BEQZ
      out := beqz
    }
    is("b01_111".U(5.W)) { // C.BNEZ
      out := bnez
    }

    // ---------------- 象限 2 ----------------
    is("b10_000".U(5.W)) { // C.SLLI（rd/shamt=0 为 HINT）
      out := slli
      if (XLEN == 32) { illegal := x(12) }
    }
    is("b10_001".U(5.W)) { // C.FLDSP（需要 D 扩展）
      if (UseD) {
        out := fldsp
        // C.FLDSP 的 rd=0 是保留编码。
        illegal := rd === 0.U
      } else { illegal := true.B }
    }
    is("b10_010".U(5.W)) { // C.LWSP
      out := lwsp
      illegal := rd === 0.U
    }
    is("b10_011".U(5.W)) { // RV64 C.LDSP / RV32 C.FLWSP
      if (XLEN == 64) {
        out := ldsp
        illegal := rd === 0.U
      } else if (UseF) {
        out := flwsp
        // C.FLWSP 的 rd=0 是保留编码。
        illegal := rd === 0.U
      } else {
        illegal := true.B
      }
    }
    is("b10_100".U(5.W)) { // C.JR/C.MV/C.EBREAK/C.JALR/C.ADD
      when(x(12) === 0.U) {
        when(rs2 === 0.U) { // C.JR
          out := jr
          illegal := rd === 0.U
        }.otherwise { // C.MV（rd=0 为 HINT）
          out := mv
        }
      }.otherwise {
        when(rs2 === 0.U) {
          when(rd === 0.U) { // C.EBREAK
            out := ebreak
          }.otherwise { // C.JALR
            out := jalr
          }
        }.otherwise { // C.ADD（rd=0 为 HINT）
          out := add
        }
      }
    }
    is("b10_101".U(5.W)) { // C.FSDSP（需要 D 扩展）
      if (UseD) { out := fsdsp }
      else { illegal := true.B }
    }
    is("b10_110".U(5.W)) { // C.SWSP
      out := swsp
    }
    is("b10_111".U(5.W)) { // RV64 C.SDSP / RV32 C.FSWSP
      if (XLEN == 64) { out := sdsp }
      else if (UseF) { out := fswsp }
      else { illegal := true.B }
    }

    // ---------------- 象限 3 ----------------
    is("b11_000".U(5.W)) { illegal := true.B }
    is("b11_001".U(5.W)) { illegal := true.B }
    is("b11_010".U(5.W)) { illegal := true.B }
    is("b11_011".U(5.W)) { illegal := true.B }
    is("b11_100".U(5.W)) { illegal := true.B }
    is("b11_101".U(5.W)) { illegal := true.B }
    is("b11_110".U(5.W)) { illegal := true.B }
    is("b11_111".U(5.W)) { illegal := true.B }
  }

  io.out := out
  io.illegal := illegal
}
