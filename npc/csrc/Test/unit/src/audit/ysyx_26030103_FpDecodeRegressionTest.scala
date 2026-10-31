package ysyx_26030103.audit

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.ysyx_26030103.config._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** F/D 译码合法性回归。
  *
  * 1. FCVT.S.D 的源格式是 D；只有 RISCV_D=y 时才允许执行。UseD=false
  *    时即使目的格式是 S，也必须按非法指令陷入。
  * 2. FMV.X.D / FMV.D.X 在 RV32D 中不存在：它们需要 64 位整数寄存器
  *    与 64 位浮点寄存器之间的完整搬移，只在 RV64D 中定义。
  *
  * 当前 IDU 只检查目的格式/FpOp5 等字段，没有把源格式的 D 依赖和
  * XLEN 纳入合法性判断。
  */
class ysyx_26030103_FpDecodeRegressionTest
    extends AnyFlatSpec with Matchers {
  private val FsDirty = BigInt(0x6000)

  private def config(
      xlen: Int,
      useF: Boolean,
      useD: Boolean
  ): ysyx_26030103_NPCConfig =
    ysyx_26030103_NPCConfig(
      XLEN = xlen,
      AddressWidth = xlen,
      UseF = useF,
      UseD = useD,
      FPUImpl = ysyx_26030103_FPUImpl.HardFloat,
      FPULen = 64,
      MMU = ysyx_26030103_MMUProfile(
        enabled = false,
        xlen = xlen,
        hasS = true,
        hasU = true,
        pteSizeBytes = if (xlen == 64) 8 else 4,
        pteFormat = if (xlen == 64) "riscv64" else "riscv32",
        pteConfig = ysyx_26030103_PteConfig(
          pteBits = if (xlen == 64) 64 else 32,
          ppnHigh = if (xlen == 64) 53 else 21
        )
      )
    )

  private def bool(value: Bool): Boolean = value.peek().litValue == 1
  private def big(value: UInt): BigInt = value.peek().litValue

  private def setup(
      dut: ysyx_26030103_IDUDecodeAuditHarness,
      mstatus: BigInt,
      privilege: Int = 3,
      frm: Int = 0
  ): Unit = {
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
    dut.io.instruction.poke(0.U)
    dut.io.isCompressed.poke(false.B)
    dut.io.privilege.poke(privilege.U)
    dut.io.mstatus.poke(mstatus.U)
    dut.io.frm.poke(frm.U)
    dut.io.mcounteren.poke("hffffffff".U)
    dut.io.scounteren.poke("hffffffff".U)
  }

  // OP-FP：funct7 = op5[4:0] << 2 | fmt[1:0]。
  private def opFp(
      funct7: Int,
      rs2: Int,
      rs1: Int,
      funct3: Int,
      rd: Int
  ): BigInt =
    (BigInt(funct7) << 25) | (BigInt(rs2) << 20) |
      (BigInt(rs1) << 15) | (BigInt(funct3) << 12) |
      (BigInt(rd) << 7) | 0x53

  private def assertIllegal(
      dut: ysyx_26030103_IDUDecodeAuditHarness,
      instruction: BigInt,
      clue: String
  ): Unit = {
    dut.io.instruction.poke(instruction.U)
    withClue(clue + " exceptionValid: ") {
      bool(dut.io.exceptionValid) should be(true)
    }
    withClue(clue + " exceptionCause: ") {
      big(dut.io.exceptionCause) should be(BigInt(2))
    }
  }

  "IDU F/D decode" should
    "在 UseD=false 时把 FCVT.S.D 判为非法指令" in {
      simulate(new ysyx_26030103_IDUDecodeAuditHarness(
        config(xlen = 64, useF = true, useD = false))) { dut =>
        setup(dut, mstatus = FsDirty, privilege = 3, frm = 0)
        // op5=8(FCVT), fmt=S(0), rs2=1(源格式 D), rs1=2, rd=1。
        val fcvtSD = opFp(funct7 = 0x20, rs2 = 1, rs1 = 2, funct3 = 0, rd = 1)
        assertIllegal(dut, fcvtSD, "FCVT.S.D without D")
      }
    }

  it should "在 RV32D 下把 FMV.X.D 判为非法指令" in {
    simulate(new ysyx_26030103_IDUDecodeAuditHarness(
      config(xlen = 32, useF = true, useD = true))) { dut =>
      setup(dut, mstatus = FsDirty, privilege = 3, frm = 0)
      // op5=28(FMV.X), fmt=D(1), rs2=0, rs1=2, funct3=0, rd=1。
      val fmvXd = opFp(funct7 = 0x71, rs2 = 0, rs1 = 2, funct3 = 0, rd = 1)
      assertIllegal(dut, fmvXd, "FMV.X.D on RV32D")
    }
  }

  it should "在 RV32D 下把 FMV.D.X 判为非法指令" in {
    simulate(new ysyx_26030103_IDUDecodeAuditHarness(
      config(xlen = 32, useF = true, useD = true))) { dut =>
      setup(dut, mstatus = FsDirty, privilege = 3, frm = 0)
      // op5=30(FMV.D.X), fmt=D(1), rs2=0, rs1=2, funct3=0, rd=1。
      val fmvDx = opFp(funct7 = 0x79, rs2 = 0, rs1 = 2, funct3 = 0, rd = 1)
      assertIllegal(dut, fmvDx, "FMV.D.X on RV32D")
    }
  }
}
