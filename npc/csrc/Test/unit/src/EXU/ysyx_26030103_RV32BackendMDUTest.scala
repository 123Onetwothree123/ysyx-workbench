package ysyx_26030103.exu

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import _root_.ysyx_26030103.common.ysyx_26030103_MDUOp
import _root_.ysyx_26030103.config._

class ysyx_26030103_RV32BackendMDUTest extends AnyFlatSpec with Matchers {
  private def operation(impl: ysyx_26030103_MDUImpl, op: UInt,
      lhs: BigInt, rhs: BigInt): BigInt = {
    val config = ysyx_26030103_NPCConfig(
      XLEN = 32,
      AddressWidth = 32,
      UseM = true,
      MDUImpl = impl
    )
    var result = BigInt(0)
    simulate(ysyx_26030103_MDU(config)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.Flush.poke(false.B)
      dut.io.Resp.ready.poke(true.B)
      dut.io.Req.valid.poke(true.B)
      dut.io.Req.bits.LHS.poke(lhs.U(32.W))
      dut.io.Req.bits.RHS.poke(rhs.U(32.W))
      dut.io.Req.bits.MDUOp.poke(op)
      var cycles = 0
      while (dut.io.Req.ready.peek().litValue == 0 && cycles < 20) {
        dut.clock.step()
        cycles += 1
      }
      dut.clock.step()
      dut.io.Req.valid.poke(false.B)
      cycles = 0
      while (dut.io.Resp.valid.peek().litValue == 0 && cycles < 200) {
        dut.clock.step()
        cycles += 1
      }
      require(dut.io.Resp.valid.peek().litValue == 1,
        s"${impl.Name} response timeout")
      result = dut.io.Resp.bits.Result.peek().litValue
      dut.clock.step()
    }
    result
  }

  private val backends = Seq(
    ysyx_26030103_MDUImpl.Ibex,
    ysyx_26030103_MDUImpl.Cve2,
    ysyx_26030103_MDUImpl.Cv32e40p,
    ysyx_26030103_MDUImpl.PicoRV32
  )

  "RV32-only MDU backends" should "执行乘除法" in {
    for (impl <- backends) {
      operation(impl, ysyx_26030103_MDUOp.MUL, 6, 7) should be(42)
      operation(impl, ysyx_26030103_MDUOp.DIV, 20, 3) should be(6)
      operation(impl, ysyx_26030103_MDUOp.REM, 20, 3) should be(2)
    }
  }

  it should "拒绝将 RV32-only 后端用于 XLEN=64" in {
    for (impl <- backends) {
      val error = intercept[IllegalArgumentException] {
        ysyx_26030103_NPCConfig(
          XLEN = 64,
          AddressWidth = 64,
          UseM = true,
          MDUImpl = impl
        )
      }
      error.getMessage should include("不支持 XLEN=64")
    }
  }
}
