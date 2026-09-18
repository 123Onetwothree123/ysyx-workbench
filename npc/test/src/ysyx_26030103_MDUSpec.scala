package ysyx_26030103.test

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import _root_.ysyx_26030103.common.ysyx_26030103_MDUOp
import _root_.ysyx_26030103.exu.ysyx_26030103_MDU

class ysyx_26030103_MDUSpec
    extends AnyFlatSpec
    with Matchers
    with ChiselSim {

  private val LHS = "h80000000".U(32.W)
  private val RHS = "hffffffff".U(32.W)

  private def issue(
      dut: ysyx_26030103_MDU,
      lhs: UInt,
      rhs: UInt,
      op: UInt
  ): Unit = {
    dut.io.Req.bits.LHS.poke(lhs)
    dut.io.Req.bits.RHS.poke(rhs)
    dut.io.Req.bits.MDUOp.poke(op)
    dut.io.Req.valid.poke(true.B)
    dut.clock.step()
    dut.io.Req.valid.poke(false.B)
  }

  "MDU" should "compute all multiply variants" in {
    simulate(new ysyx_26030103_MDU) { dut =>
      dut.io.Flush.poke(false.B)
      dut.io.Resp.ready.poke(true.B)

      issue(dut, 7.U, 6.U, ysyx_26030103_MDUOp.MUL)
      dut.io.Resp.bits.Result.expect(42.U)
      dut.clock.step()

      issue(dut, "hfffffffe".U, 3.U, ysyx_26030103_MDUOp.MULH)
      dut.io.Resp.bits.Result.expect("hffffffff".U)
      dut.clock.step()

      issue(dut, "hffffffff".U, 2.U, ysyx_26030103_MDUOp.MULHSU)
      dut.io.Resp.bits.Result.expect("hffffffff".U)
      dut.clock.step()

      issue(dut, "hffffffff".U, 2.U, ysyx_26030103_MDUOp.MULHU)
      dut.io.Resp.bits.Result.expect(1.U)
      dut.clock.step()
    }
  }

  it should "handle signed and unsigned divide/remainder" in {
    simulate(new ysyx_26030103_MDU) { dut =>
      dut.io.Flush.poke(false.B)
      dut.io.Resp.ready.poke(true.B)

      issue(dut, "hfffffff9".U, 3.U, ysyx_26030103_MDUOp.DIV)
      dut.io.Resp.bits.Result.expect("hfffffffe".U)
      dut.clock.step()

      issue(dut, 7.U, 3.U, ysyx_26030103_MDUOp.DIVU)
      dut.io.Resp.bits.Result.expect(2.U)
      dut.clock.step()

      issue(dut, "hfffffff9".U, 3.U, ysyx_26030103_MDUOp.REM)
      dut.io.Resp.bits.Result.expect("hffffffff".U)
      dut.clock.step()

      issue(dut, 7.U, 3.U, ysyx_26030103_MDUOp.REMU)
      dut.io.Resp.bits.Result.expect(1.U)
      dut.clock.step()
    }
  }

  it should "apply RV32 divide corner cases and flush" in {
    simulate(new ysyx_26030103_MDU) { dut =>
      dut.io.Flush.poke(false.B)
      dut.io.Resp.ready.poke(true.B)

      issue(dut, LHS, RHS, ysyx_26030103_MDUOp.DIV)
      dut.io.Resp.bits.Result.expect(LHS)
      dut.clock.step()

      issue(dut, LHS, RHS, ysyx_26030103_MDUOp.DIVU)
      dut.io.Resp.bits.Result.expect(0.U)
      dut.clock.step()

      issue(dut, 9.U, 0.U, ysyx_26030103_MDUOp.DIV)
      dut.io.Resp.bits.Result.expect("hffffffff".U)
      dut.clock.step()

      issue(dut, 9.U, 0.U, ysyx_26030103_MDUOp.REM)
      dut.io.Resp.bits.Result.expect(9.U)
      dut.clock.step()

      issue(dut, 11.U, 3.U, ysyx_26030103_MDUOp.MUL)
      dut.io.Flush.poke(true.B)
      dut.clock.step()
      dut.io.Resp.valid.expect(false.B)
      dut.io.Flush.poke(false.B)
    }
  }
}
