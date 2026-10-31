package ysyx_26030103.audit

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.ysyx_26030103.config._
import _root_.ysyx_26030103.fpu._
import _root_.ysyx_26030103.exu._
import _root_.ysyx_26030103.common.ysyx_26030103_MDUOp
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** 两个疑似功能 bug 的最小探针。
  *
  * 1) Fudian 后端在 RV64 下把 FCVT.WU 的 32 位无符号结果零扩展，而
  *    RISC-V 要求 W/WU 结果符号扩展到 XLEN。
  * 2) NutShell Divider 的 out.valid 只在 s_finish 保持一拍；下游
  *    ready 为低时结果被丢弃。
  */
class ysyx_26030103_FpuMduProbeAuditTest extends AnyFlatSpec with Matchers {

  private def bool(v: Bool): Boolean = v.peek().litValue == 1

  private def hex(v: BigInt): String = "0x" + v.toString(16)

  private def boxedS(low32: BigInt): BigInt =
    (BigInt(0xffffffffL) << 32) | (low32 & 0xffffffffL)

  private def fudianConfig64: ysyx_26030103_NPCConfig =
    ysyx_26030103_NPCConfig(
      XLEN = 64,
      AddressWidth = 64,
      UseF = true,
      UseD = true,
      FPUImpl = ysyx_26030103_FPUImpl.Fudian,
      FPULen = 64
    )

  private def nutShellMduConfig64: ysyx_26030103_NPCConfig =
    ysyx_26030103_NPCConfig(
      XLEN = 64,
      AddressWidth = 64,
      UseM = true,
      MDUImpl = ysyx_26030103_MDUImpl.NutShell
    )

  "FudianFPU" should "sign-extend RV64 FCVT.WU.S result to XLEN" in {
    simulate(new ysyx_26030103_FudianFPU(fudianConfig64)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.flush.poke(false.B)
      dut.io.req.valid.poke(false.B)
      dut.io.resp.ready.poke(false.B)

      var cycles = 0
      while (!bool(dut.io.req.ready) && cycles < 20) {
        dut.clock.step()
        cycles += 1
      }
      withClue("FudianFPU req.ready timeout: ") {
        bool(dut.io.req.ready) should be(true)
      }

      // Float 字面量 4294967295.0f 舍入为 2^32，其位模式正是 0x4f800000。
      val inputBits = BigInt(0x4f800000L)
      dut.io.req.bits.op.poke(ysyx_26030103_FpuOp.FCvtFpToUInt)
      dut.io.req.bits.fmt.poke(ysyx_26030103_FpuFmt.S)
      dut.io.req.bits.rm.poke(ysyx_26030103_FpuRm.RNE)
      dut.io.req.bits.intWord.poke(true.B)
      dut.io.req.bits.rs1.poke(boxedS(inputBits).U(64.W))
      dut.io.req.bits.rs2.poke(0.U(64.W))
      dut.io.req.bits.rs3.poke(0.U(64.W))
      dut.io.req.valid.poke(true.B)
      dut.clock.step()
      dut.io.req.valid.poke(false.B)

      cycles = 0
      while (!bool(dut.io.resp.valid) && cycles < 100) {
        dut.clock.step()
        cycles += 1
      }
      withClue("FudianFPU resp.valid timeout: ") {
        bool(dut.io.resp.valid) should be(true)
      }
      val result = dut.io.resp.bits.result.peek().litValue
      val fflags = dut.io.resp.bits.fflags.peek().litValue
      dut.io.resp.ready.poke(true.B)
      dut.clock.step()
      dut.io.resp.ready.poke(false.B)

      withClue(
        s"FCVT.WU.S(${hex(inputBits)}) returned ${hex(result)}, " +
          s"fflags=${hex(fflags)}; RV64 requires the 32-bit result " +
          "sign-extended to 0xffffffffffffffff: "
      ) {
        result should be(BigInt("ffffffffffffffff", 16))
      }
    }
  }

  "NutShellMDU" should "hold DIV response while Resp.ready stays low" in {
    val quotient = BigInt(14) // 100 / 7
    simulate(new ysyx_26030103_NutShellMDU(nutShellMduConfig64)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.Flush.poke(false.B)
      dut.io.Resp.ready.poke(false.B)
      dut.io.Req.valid.poke(false.B)
      dut.io.Req.bits.LHS.poke(0.U(64.W))
      dut.io.Req.bits.RHS.poke(0.U(64.W))
      dut.io.Req.bits.MDUOp.poke(0.U(ysyx_26030103_MDUOp.Width.W))

      var cycles = 0
      while (!bool(dut.io.Req.ready) && cycles < 20) {
        dut.clock.step()
        cycles += 1
      }
      withClue("NutShellMDU Req.ready timeout: ") {
        bool(dut.io.Req.ready) should be(true)
      }

      dut.io.Req.bits.LHS.poke(100.U(64.W))
      dut.io.Req.bits.RHS.poke(7.U(64.W))
      dut.io.Req.bits.MDUOp.poke(ysyx_26030103_MDUOp.DIV)
      dut.io.Req.valid.poke(true.B)
      dut.clock.step()
      dut.io.Req.valid.poke(false.B)

      cycles = 0
      while (!bool(dut.io.Resp.valid) && cycles < 300) {
        dut.clock.step()
        cycles += 1
      }
      withClue("NutShellMDU Resp.valid timeout (DIV not done): ") {
        bool(dut.io.Resp.valid) should be(true)
      }
      val firstResult = dut.io.Resp.bits.Result.peek().litValue
      withClue(s"first DIV response should be 14, got ${hex(firstResult)}: ") {
        firstResult should be(quotient)
      }

      // 下游保持 ready=0 八拍；正确实现应保持 valid/result 不变。
      var dropCycle = -1
      var held = 0
      while (held < 8) {
        dut.clock.step()
        held += 1
        if (dropCycle < 0 && !bool(dut.io.Resp.valid)) {
          dropCycle = held
        }
      }
      val heldValid = bool(dut.io.Resp.valid)
      val heldResult = dut.io.Resp.bits.Result.peek().litValue

      withClue(
        s"after 8 cycles with ready=0: valid=$heldValid (first dropped at " +
          s"cycle $dropCycle), result=${hex(heldResult)}; first response " +
          s"${hex(firstResult)}: "
      ) {
        heldValid should be(true)
        heldResult should be(quotient)
      }

      // 反压结束后正常完成一次握手。
      dut.io.Resp.ready.poke(true.B)
      dut.clock.step()
      dut.io.Resp.ready.poke(false.B)
    }
  }
}
