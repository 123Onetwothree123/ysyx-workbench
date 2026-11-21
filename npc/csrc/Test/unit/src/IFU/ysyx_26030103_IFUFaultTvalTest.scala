package ysyx_26030103.ifu

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** IFU 取指故障的 tval 使用架构 PC；跨字第二拍故障才用实际取指字地址 */
class ysyx_26030103_IFUFaultTvalTest extends AnyFlatSpec with Matchers {
  private def big(value: UInt): BigInt = value.peek().litValue

  private def setup(dut: ysyx_26030103_IFU): Unit = {
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
    dut.io.FetchReady.poke(true.B)
    dut.io.ResponseValid.poke(false.B)
    dut.io.ResponseData.poke(0.U)
    dut.io.ResponseExceptionValid.poke(false.B)
    dut.io.ResponseExceptionCause.poke(0.U)
    dut.io.ResponseAccessFaultResponse.poke(0.U)
    dut.io.Redirect.poke(false.B)
    dut.io.RedirectTarget.poke(0.U)
    dut.io.ExceptionTaken.poke(false.B)
    dut.io.ExceptionTarget.poke(0.U)
    dut.io.PredHit.poke(false.B)
    dut.io.PredTarget.poke(0.U)
    dut.io.Out.ready.poke(true.B)
  }

  private def acceptRequest(dut: ysyx_26030103_IFU): Unit = {
    var cycles = 0
    while (dut.io.FetchValid.peek().litValue.toInt == 0 && cycles < 8) {
      dut.clock.step()
      cycles += 1
    }
    assert(cycles < 8, "IFU 未发出取指请求")
    dut.clock.step()
  }

  /** 在响应有效的当拍返回故障输出，随后推进时钟 */
  private def faultResponse(
      dut: ysyx_26030103_IFU,
      cause: Int
  ): (BigInt, BigInt) = {
    dut.io.ResponseValid.poke(true.B)
    dut.io.ResponseExceptionValid.poke(true.B)
    dut.io.ResponseExceptionCause.poke(cause.U(4.W))
    dut.io.ResponseAccessFaultResponse.poke(2.U(2.W))
    dut.io.Out.valid.peek().litValue.toInt should be(1)
    val pc = big(dut.io.Out.bits.PC)
    val tval = big(dut.io.Out.bits.PredTarget)
    dut.clock.step()
    dut.io.ResponseValid.poke(false.B)
    dut.io.ResponseExceptionValid.poke(false.B)
    (pc, tval)
  }

  "IFU" should "首个取指字故障时 tval 是未对齐的架构 PC" in {
    simulate(new ysyx_26030103_IFU(0x80000102L, 32, UseC = true)) { dut =>
      setup(dut)
      acceptRequest(dut)
      big(dut.io.FetchAddress) should be(BigInt("80000100", 16))

      val (pc, tval) = faultResponse(dut, cause = 1)
      pc should be(BigInt("80000102", 16))
      tval should be(BigInt("80000102", 16))
      dut.io.Out.bits.Instruction.peek().litValue should be(BigInt("13", 16))
    }
  }

  it should "跨字第二拍故障时 tval 是第二拍取指地址" in {
    simulate(new ysyx_26030103_IFU(0x80000102L, 32, UseC = true)) { dut =>
      setup(dut)
      acceptRequest(dut)

      // 高半字 bits[1:0]=11 表示 32 位指令，低半字来自下一个字
      dut.io.ResponseValid.poke(true.B)
      dut.io.ResponseData.poke(BigInt("00030000", 16).U(32.W))
      dut.clock.step()
      dut.io.ResponseValid.poke(false.B)

      acceptRequest(dut)
      big(dut.io.FetchAddress) should be(BigInt("80000104", 16))

      val (pc, tval) = faultResponse(dut, cause = 1)
      pc should be(BigInt("80000102", 16))
      tval should be(BigInt("80000104", 16))
    }
  }
}
