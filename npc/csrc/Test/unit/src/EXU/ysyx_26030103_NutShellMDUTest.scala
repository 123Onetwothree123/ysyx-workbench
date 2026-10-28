package ysyx_26030103.exu

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import _root_.ysyx_26030103.common.ysyx_26030103_MDUOp
import _root_.ysyx_26030103.config._

class ysyx_26030103_NutShellMDUTest extends AnyFlatSpec with Matchers {
  private def operation(op: UInt, lhs: BigInt, rhs: BigInt,
      xlen: Int = 64): BigInt = {
    val config = ysyx_26030103_NPCConfig(
      XLEN = xlen,
      AddressWidth = xlen,
      UseM = true,
      MDUImpl = ysyx_26030103_MDUImpl.NutShell
    )
    var result = BigInt(0)
    simulate(new ysyx_26030103_NutShellMDU(config)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.Flush.poke(false.B)
      dut.io.Resp.ready.poke(true.B)
      dut.io.Req.valid.poke(true.B)
      dut.io.Req.bits.LHS.poke(lhs.U(xlen.W))
      dut.io.Req.bits.RHS.poke(rhs.U(xlen.W))
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
        "NutShell MDU response timeout")
      result = dut.io.Resp.bits.Result.peek().litValue
      dut.clock.step()
    }
    result
  }

  "NutShell MDU" should "执行 RV64 M 扩展乘除法" in {
    operation(ysyx_26030103_MDUOp.MUL, 6, 7) should be(42)
    operation(ysyx_26030103_MDUOp.MULH,
      BigInt("ffffffffffffffff", 16), BigInt("ffffffffffffffff", 16)) should be(0)
    operation(ysyx_26030103_MDUOp.MULHU,
      BigInt("ffffffffffffffff", 16), 2) should be(1)
    operation(ysyx_26030103_MDUOp.DIV, 20, 3) should be(6)
    operation(ysyx_26030103_MDUOp.REM, 20, 3) should be(2)
    operation(ysyx_26030103_MDUOp.DIV,
      BigInt("ffffffffffffffec", 16), 3) should be(BigInt("fffffffffffffffa", 16))
    operation(ysyx_26030103_MDUOp.REM,
      BigInt("ffffffffffffffec", 16), 3) should be(BigInt("fffffffffffffffe", 16))
  }

  it should "执行 RV32 M 扩展乘除法" in {
    operation(ysyx_26030103_MDUOp.MUL, 6, 7, 32) should be(42)
    operation(ysyx_26030103_MDUOp.DIV, 20, 3, 32) should be(6)
    operation(ysyx_26030103_MDUOp.REM, 20, 3, 32) should be(2)
  }
}
