package ysyx_26030103.test

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import _root_.ysyx_26030103.common.ysyx_26030103_MDUOp
import _root_.ysyx_26030103.common.ysyx_26030103_MULImpl
import _root_.ysyx_26030103.common.ysyx_26030103_MULEncoding
import _root_.ysyx_26030103.common.ysyx_26030103_NPCConfig
import _root_.ysyx_26030103.exu.ysyx_26030103_MDU

// 覆盖稀疏列式 Dadda 在普通部分积、Booth 和内部流水配置下的算术等价性。
class ysyx_26030103_DaddaSpec
    extends AnyFlatSpec
    with Matchers
    with ChiselSim {

  private case class MulCase(
      LHS: BigInt,
      RHS: BigInt,
      Op: UInt,
      Expected: BigInt
  )

  private val Mask32 = (BigInt(1) << 32) - 1
  private val Mask64 = (BigInt(1) << 64) - 1

  private def signed32(Value: BigInt): BigInt = {
    if (Value.testBit(31)) Value - (BigInt(1) << 32) else Value
  }

  private def high32(Value: BigInt): BigInt = {
    ((Value & Mask64) >> 32) & Mask32
  }

  private val DirectedCases = Seq(
    MulCase(7, 6, ysyx_26030103_MDUOp.MUL, 42),
    MulCase("hffffffff".U.litValue, 2, ysyx_26030103_MDUOp.MUL, "hfffffffe".U.litValue),
    MulCase("hfffffffe".U.litValue, 3, ysyx_26030103_MDUOp.MULH, "hffffffff".U.litValue),
    MulCase("hffffffff".U.litValue, 2, ysyx_26030103_MDUOp.MULHSU, "hffffffff".U.litValue),
    MulCase("hffffffff".U.litValue, 2, ysyx_26030103_MDUOp.MULHU, 1),
    MulCase("h80000000".U.litValue, "h80000000".U.litValue, ysyx_26030103_MDUOp.MULH, "h40000000".U.litValue),
    MulCase("h80000000".U.litValue, "hffffffff".U.litValue, ysyx_26030103_MDUOp.MULHSU, "h80000000".U.litValue)
  )

  private val RandomCases = {
    val Random = new scala.util.Random(0x26030103L)
    (0 until 16).flatMap { _ =>
      val LHS = BigInt(Random.nextInt().toLong) & Mask32
      val RHS = BigInt(Random.nextInt().toLong) & Mask32
      Seq(
        MulCase(LHS, RHS, ysyx_26030103_MDUOp.MUL, (LHS * RHS) & Mask32),
        MulCase(LHS, RHS, ysyx_26030103_MDUOp.MULH, high32(signed32(LHS) * signed32(RHS))),
        MulCase(LHS, RHS, ysyx_26030103_MDUOp.MULHSU, high32(signed32(LHS) * RHS)),
        MulCase(LHS, RHS, ysyx_26030103_MDUOp.MULHU, high32(LHS * RHS))
      )
    }
  }

  private val Cases = DirectedCases ++ RandomCases

  private def issue(
      dut: ysyx_26030103_MDU,
      testCase: MulCase
  ): Unit = {
    dut.io.Req.bits.LHS.poke(testCase.LHS.U(32.W))
    dut.io.Req.bits.RHS.poke(testCase.RHS.U(32.W))
    dut.io.Req.bits.MDUOp.poke(testCase.Op)
    dut.io.Req.valid.poke(true.B)
    dut.io.Req.ready.expect(true.B)
    dut.clock.step()
    dut.io.Req.valid.poke(false.B)
  }

  private def check(
      dut: ysyx_26030103_MDU,
      testCase: MulCase
  ): Unit = {
    var Cycles = 0
    while (!dut.io.Resp.valid.peek().litToBoolean && Cycles < 128) {
      dut.clock.step()
      Cycles += 1
    }
    dut.io.Resp.valid.expect(true.B)
    dut.io.Resp.bits.Result.expect(testCase.Expected.U(32.W))
    dut.clock.step()
  }

  "Sparse Dadda" should "match the reference multiply cases across configurable modes" in {
    val Configs = Seq(
      ysyx_26030103_NPCConfig(
        UseM = true,
        MULImpl = ysyx_26030103_MULImpl.Dadda,
        MULEncoding = ysyx_26030103_MULEncoding.Plain,
        MULPipeline = 0
      ),
      ysyx_26030103_NPCConfig(
        UseM = true,
        MULImpl = ysyx_26030103_MULImpl.Dadda,
        MULEncoding = ysyx_26030103_MULEncoding.Booth,
        MULRadix = 4,
        MULPipeline = 0
      ),
      ysyx_26030103_NPCConfig(
        UseM = true,
        MULImpl = ysyx_26030103_MULImpl.Dadda,
        MULEncoding = ysyx_26030103_MULEncoding.Booth,
        MULRadix = 8,
        MULPipeline = 2
      ),
      ysyx_26030103_NPCConfig(
        UseM = true,
        MULImpl = ysyx_26030103_MULImpl.Dadda,
        MULEncoding = ysyx_26030103_MULEncoding.Booth,
        MULRadix = 2,
        MULPipeline = 1
      ),
      ysyx_26030103_NPCConfig(
        UseM = true,
        MULImpl = ysyx_26030103_MULImpl.Dadda,
        MULEncoding = ysyx_26030103_MULEncoding.Booth,
        MULRadix = 16,
        MULPipeline = 4
      )
    )

    for (Config <- Configs) {
      simulate(new ysyx_26030103_MDU(Config)) { dut =>
        dut.io.Flush.poke(false.B)
        dut.io.Resp.ready.poke(true.B)
        for (TestCase <- Cases) {
          issue(dut, TestCase)
          check(dut, TestCase)
        }
      }
    }
  }

  it should "freeze sparse pipeline columns under backpressure and clear them on flush" in {
    val Config = ysyx_26030103_NPCConfig(
      UseM = true,
      MULImpl = ysyx_26030103_MULImpl.Dadda,
      MULEncoding = ysyx_26030103_MULEncoding.Booth,
      MULRadix = 4,
      MULPipeline = 4
    )
    simulate(new ysyx_26030103_MDU(Config)) { dut =>
      dut.io.Flush.poke(false.B)
      dut.io.Resp.ready.poke(false.B)

      val BlockedCase = MulCase("hffffffff".U.litValue, 2, ysyx_26030103_MDUOp.MULHU, 1)
      issue(dut, BlockedCase)
      var Cycles = 0
      while (!dut.io.Resp.valid.peek().litToBoolean && Cycles < 128) {
        dut.clock.step()
        Cycles += 1
      }
      dut.io.Resp.valid.expect(true.B)
      dut.io.Resp.bits.Result.expect(BlockedCase.Expected.U(32.W))
      dut.clock.step(3)
      dut.io.Resp.valid.expect(true.B)
      dut.io.Resp.bits.Result.expect(BlockedCase.Expected.U(32.W))

      dut.io.Resp.ready.poke(true.B)
      dut.clock.step()
      dut.io.Resp.valid.expect(false.B)

      issue(dut, MulCase(1234567, 7654321, ysyx_26030103_MDUOp.MUL, 0))
      dut.io.Flush.poke(true.B)
      dut.clock.step()
      dut.io.Flush.poke(false.B)
      dut.io.Resp.valid.expect(false.B)
      dut.clock.step(8)
      dut.io.Resp.valid.expect(false.B)
    }
  }
}
