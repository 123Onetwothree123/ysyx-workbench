package ysyx_26030103.idu

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** RVC 浮点压缩编码的展开与保留编码定向测试。
  *
  * 重点验证 RV32/RV64 同编码位的差异：
  *   0x6188 在 RV64 是 C.LD，在 RV32+F 是 C.FLW；
  *   0xe588 在 RV64 是 C.SD，在 RV32+F 是 C.FSW；
  *   0x6522/0xe82e 同理分别是 C.LDSP/C.FLWSP 与 C.SDSP/C.FSWSP。
  * 这些编码绝不能按 XLEN/扩展开关“猜”错方向。
  */
class ysyx_26030103_RVCDecoderTest extends AnyFlatSpec with Matchers {
  private def bits(value: BigInt, hi: Int, lo: Int): BigInt =
    (value >> lo) & ((BigInt(1) << (hi - lo + 1)) - 1)

  private def expand(
      word: Int,
      xlen: Int,
      useF: Boolean,
      useD: Boolean
  ): (BigInt, Boolean) = {
    var out = BigInt(0)
    var illegal = false
    simulate(new ysyx_26030103_RVCDecoder(xlen, useF, useD)) { dut =>
      dut.io.in.poke(word.U(32.W))
      dut.clock.step()
      out = dut.io.out.peek().litValue
      illegal = dut.io.illegal.peek().litValue == 1
    }
    (out, illegal)
  }

  private def opcode(word: BigInt): BigInt = bits(word, 6, 0)
  private def rd(word: BigInt): BigInt = bits(word, 11, 7)
  private def funct3(word: BigInt): BigInt = bits(word, 14, 12)
  private def rs1(word: BigInt): BigInt = bits(word, 19, 15)
  private def rs2(word: BigInt): BigInt = bits(word, 24, 20)
  private def immI(word: BigInt): BigInt = bits(word, 31, 20)
  private def immS(word: BigInt): BigInt =
    (bits(word, 31, 25) << 5) | bits(word, 11, 7)

  "RVCDecoder" should "在 RV32D 下正确展开 C.FLD/C.FSD 及 SP 形式" in {
    // c.fld f8, 0(a0)
    val (fld, fldIllegal) = expand(0x2100, 32, useF = true, useD = true)
    fldIllegal should be(false)
    opcode(fld) should be(0x07)
    funct3(fld) should be(3)
    rd(fld) should be(8)
    rs1(fld) should be(10)
    immI(fld) should be(0)

    // c.fld f15, 248(a5)：覆盖 offset[7:6]、offset[5]、offset[4:3]。
    val (fldMax, _) = expand(0x3ffc, 32, useF = true, useD = true)
    opcode(fldMax) should be(0x07)
    funct3(fldMax) should be(3)
    rd(fldMax) should be(15)
    rs1(fldMax) should be(15)
    immI(fldMax) should be(248)

    // c.fsd f9, 8(a1)
    val (fsd, fsdIllegal) = expand(0xa584, 32, useF = true, useD = true)
    fsdIllegal should be(false)
    opcode(fsd) should be(0x27)
    funct3(fsd) should be(3)
    rs1(fsd) should be(10 + 1)
    rs2(fsd) should be(9)
    immS(fsd) should be(8)

    // c.fsd f14, 248(a5)
    val (fsdMax, _) = expand(0xbff8, 32, useF = true, useD = true)
    opcode(fsdMax) should be(0x27)
    funct3(fsdMax) should be(3)
    rs1(fsdMax) should be(15)
    rs2(fsdMax) should be(14)
    immS(fsdMax) should be(248)

    // c.fldsp f8, 8(sp)
    val (fldsp, fldspIllegal) = expand(0x2422, 32, useF = true, useD = true)
    fldspIllegal should be(false)
    opcode(fldsp) should be(0x07)
    funct3(fldsp) should be(3)
    rd(fldsp) should be(8)
    rs1(fldsp) should be(2)
    immI(fldsp) should be(8)

    // c.fldsp f31, 504(sp)
    val (fldspMax, _) = expand(0x3ffe, 32, useF = true, useD = true)
    opcode(fldspMax) should be(0x07)
    funct3(fldspMax) should be(3)
    rd(fldspMax) should be(31)
    immI(fldspMax) should be(504)

    // c.fsdsp f9, 16(sp)
    val (fsdsp, fsdspIllegal) = expand(0xa826, 32, useF = true, useD = true)
    fsdspIllegal should be(false)
    opcode(fsdsp) should be(0x27)
    funct3(fsdsp) should be(3)
    rs1(fsdsp) should be(2)
    rs2(fsdsp) should be(9)
    immS(fsdsp) should be(16)

    // c.fsdsp f31, 504(sp)
    val (fsdspMax, _) = expand(0xbffe, 32, useF = true, useD = true)
    opcode(fsdspMax) should be(0x27)
    funct3(fsdspMax) should be(3)
    rs2(fsdspMax) should be(31)
    immS(fsdspMax) should be(504)
  }

  it should "在 RV32F 下正确展开 C.FLW/C.FSW 及 SP 形式" in {
    // 与 RV64 C.LD/C.SD 同编码位，RV32+F 必须展开为浮点访存。
    val (flw, flwIllegal) = expand(0x6188, 32, useF = true, useD = false)
    flwIllegal should be(false)
    opcode(flw) should be(0x07)
    funct3(flw) should be(2)
    rd(flw) should be(10)
    rs1(flw) should be(11)
    immI(flw) should be(0)

    // c.flw fa3, 124(a5)：offset[6] 来自 x5，offset[5:3] 来自 x12:10。
    val (flwMax, _) = expand(0x7ff4, 32, useF = true, useD = false)
    opcode(flwMax) should be(0x07)
    funct3(flwMax) should be(2)
    rd(flwMax) should be(13)
    rs1(flwMax) should be(15)
    immI(flwMax) should be(124)

    val (fsw, fswIllegal) = expand(0xe588, 32, useF = true, useD = false)
    fswIllegal should be(false)
    opcode(fsw) should be(0x27)
    funct3(fsw) should be(2)
    rs1(fsw) should be(11)
    rs2(fsw) should be(10)
    immS(fsw) should be(8)

    // c.fsw fa2, 124(a5)
    val (fswMax, _) = expand(0xfff0, 32, useF = true, useD = false)
    opcode(fswMax) should be(0x27)
    funct3(fswMax) should be(2)
    rs1(fswMax) should be(15)
    rs2(fswMax) should be(12)
    immS(fswMax) should be(124)

    // c.flwsp fa0, 4(sp)
    val (flwsp, flwspIllegal) = expand(0x6512, 32, useF = true, useD = false)
    flwspIllegal should be(false)
    opcode(flwsp) should be(0x07)
    funct3(flwsp) should be(2)
    rd(flwsp) should be(10)
    rs1(flwsp) should be(2)
    immI(flwsp) should be(4)

    // c.flwsp ft11, 252(sp)
    val (flwspMax, _) = expand(0x7ffe, 32, useF = true, useD = false)
    opcode(flwspMax) should be(0x07)
    funct3(flwspMax) should be(2)
    rd(flwspMax) should be(31)
    immI(flwspMax) should be(252)

    // c.fswsp fa1, 8(sp)
    val (fswsp, fswspIllegal) = expand(0xe42e, 32, useF = true, useD = false)
    fswspIllegal should be(false)
    opcode(fswsp) should be(0x27)
    funct3(fswsp) should be(2)
    rs1(fswsp) should be(2)
    rs2(fswsp) should be(11)
    immS(fswsp) should be(8)

    // c.fswsp ft11, 252(sp)
    val (fswspMax, _) = expand(0xfffe, 32, useF = true, useD = false)
    opcode(fswspMax) should be(0x27)
    funct3(fswspMax) should be(2)
    rs2(fswspMax) should be(31)
    immS(fswspMax) should be(252)
  }

  it should "RV64 同编码位必须展开成 C.LD/C.SD，而不是 C.FLW/C.FSW" in {
    val (ld, ldIllegal) = expand(0x6188, 64, useF = true, useD = true)
    ldIllegal should be(false)
    opcode(ld) should be(0x03)
    funct3(ld) should be(3)

    val (sd, sdIllegal) = expand(0xe588, 64, useF = true, useD = true)
    sdIllegal should be(false)
    opcode(sd) should be(0x23)
    funct3(sd) should be(3)

    val (ldsp, _) = expand(0x6522, 64, useF = true, useD = true)
    opcode(ldsp) should be(0x03)
    funct3(ldsp) should be(3)

    val (sdsp, _) = expand(0xe82e, 64, useF = true, useD = true)
    opcode(sdsp) should be(0x23)
    funct3(sdsp) should be(3)
  }

  it should "F/D 关闭时浮点压缩编码保持非法" in {
    // C.FLD/C.FSD/C.FLDSP/C.FSDSP
    expand(0x2100, 32, useF = false, useD = false)._2 should be(true)
    expand(0xa584, 32, useF = false, useD = false)._2 should be(true)
    expand(0x2422, 32, useF = false, useD = false)._2 should be(true)
    expand(0xa826, 32, useF = false, useD = false)._2 should be(true)

    // RV32 的 C.FLW/C.FSW/C.FLWSP/C.FSWSP
    expand(0x6188, 32, useF = false, useD = false)._2 should be(true)
    expand(0xe588, 32, useF = false, useD = false)._2 should be(true)
    expand(0x6512, 32, useF = false, useD = false)._2 should be(true)
    expand(0xe42e, 32, useF = false, useD = false)._2 should be(true)

    // 只有 F 没有 D 时，C.FLD/C.FSD 仍必须非法。
    expand(0x2100, 32, useF = true, useD = false)._2 should be(true)
    expand(0xa584, 32, useF = true, useD = false)._2 should be(true)
  }

  it should "C.FLDSP/C.FLWSP 的 rd=0 保留编码必须非法" in {
    // 0x2002: C.FLDSP f0, 0(sp)；0x6002: RV32 C.FLWSP f0, 0(sp)。
    expand(0x2002, 32, useF = true, useD = true)._2 should be(true)
    expand(0x2002, 64, useF = true, useD = true)._2 should be(true)
    expand(0x6002, 32, useF = true, useD = false)._2 should be(true)
  }
}
