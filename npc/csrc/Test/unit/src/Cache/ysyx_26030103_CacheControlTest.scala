package ysyx_26030103.cache

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** ICache/DCache 共享 drain/error/RLAST 判定的纯组合回归。 */
class ysyx_26030103_CacheControlTest
    extends AnyFlatSpec with Matchers with ChiselSim {

  private class ControlHarness extends Module {
    val io = IO(new Bundle {
      val timer = Input(UInt(10.W))
      val valid = Input(Bool())
      val last = Input(Bool())
      val mustBeLast = Input(Bool())
      val response = Input(UInt(2.W))
      val timeout = Output(Bool())
      val retryQuiet = Output(Bool())
      val readLast = Output(Bool())
      val missingLast = Output(Bool())
      val earlyLast = Output(Bool())
      val beatError = Output(Bool())
      val faultResponse = Output(UInt(2.W))
    })

    io.timeout := ysyx_26030103_CacheControl.DrainTimedOut(io.timer, 255)
    io.retryQuiet := ysyx_26030103_CacheControl.DrainRetryQuiet(
      io.timer, 255, io.valid)
    io.readLast := ysyx_26030103_CacheControl.ReadLastBeat(
      io.valid, io.last)
    io.missingLast := ysyx_26030103_CacheControl.MissingLast(
      io.valid, io.last, io.mustBeLast)
    io.earlyLast := ysyx_26030103_CacheControl.EarlyLast(
      io.last, io.mustBeLast)
    io.beatError := ysyx_26030103_CacheControl.BeatError(io.response)
    io.faultResponse := ysyx_26030103_CacheControl.FaultResponse(
      io.response =/= 0.U, io.response)
  }

  "CacheControl" should "在阈值拍报告超时，且只在通道安静时重试" in {
    simulate(new ControlHarness) { dut =>
      dut.io.timer.poke(254.U)
      dut.io.valid.poke(false.B)
      dut.io.timeout.peek().litValue.toInt shouldBe 0
      dut.io.retryQuiet.peek().litValue.toInt shouldBe 0

      dut.io.timer.poke(255.U)
      dut.io.timeout.peek().litValue.toInt shouldBe 1
      dut.io.retryQuiet.peek().litValue.toInt shouldBe 1

      dut.io.valid.poke(true.B)
      dut.io.retryQuiet.peek().litValue.toInt shouldBe 0
    }
  }

  it should "区分正常 RLAST、缺失 RLAST 和提前 RLAST" in {
    simulate(new ControlHarness) { dut =>
      dut.io.valid.poke(true.B)
      dut.io.last.poke(true.B)
      dut.io.mustBeLast.poke(true.B)
      dut.io.readLast.peek().litValue.toInt shouldBe 1
      dut.io.missingLast.peek().litValue.toInt shouldBe 0
      dut.io.earlyLast.peek().litValue.toInt shouldBe 0

      dut.io.last.poke(false.B)
      dut.io.missingLast.peek().litValue.toInt shouldBe 1
      dut.io.earlyLast.peek().litValue.toInt shouldBe 0

      dut.io.last.poke(true.B)
      dut.io.mustBeLast.poke(false.B)
      dut.io.missingLast.peek().litValue.toInt shouldBe 0
      dut.io.earlyLast.peek().litValue.toInt shouldBe 1
    }
  }

  it should "保留 AXI 错误码，缺省回退 SLVERR" in {
    simulate(new ControlHarness) { dut =>
      dut.io.response.poke(0.U)
      dut.io.beatError.peek().litValue.toInt shouldBe 0
      dut.io.faultResponse.peek().litValue.toInt shouldBe 2

      dut.io.response.poke(3.U)
      dut.io.beatError.peek().litValue.toInt shouldBe 1
      dut.io.faultResponse.peek().litValue.toInt shouldBe 3
    }
  }
}
