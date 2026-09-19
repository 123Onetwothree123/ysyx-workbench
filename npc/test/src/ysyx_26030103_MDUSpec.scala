package ysyx_26030103.test

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import _root_.ysyx_26030103.common.ysyx_26030103_DIVImpl
import _root_.ysyx_26030103.common.ysyx_26030103_NPCConfig
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

  private def waitResponse(dut: ysyx_26030103_MDU): Unit = {
    var cycles = 0
    while (!dut.io.Resp.valid.peek().litToBoolean && cycles < 128) {
      dut.clock.step()
      cycles += 1
    }
    dut.io.Resp.valid.expect(true.B)
  }

  private def waitResponseBlocked(dut: ysyx_26030103_MDU): Unit = {
    var cycles = 0
    while (!dut.io.Resp.valid.peek().litToBoolean && cycles < 128) {
      dut.clock.step()
      cycles += 1
    }
    dut.io.Resp.valid.expect(true.B)
  }

  "MDU" should "compute all multiply variants" in {
    simulate(new ysyx_26030103_MDU) { dut =>
      dut.io.Flush.poke(false.B)
      dut.io.Resp.ready.poke(true.B)

      issue(dut, 7.U, 6.U, ysyx_26030103_MDUOp.MUL)
      waitResponse(dut)
      dut.io.Resp.bits.Result.expect(42.U)
      dut.clock.step()

      issue(dut, "hfffffffe".U, 3.U, ysyx_26030103_MDUOp.MULH)
      waitResponse(dut)
      dut.io.Resp.bits.Result.expect("hffffffff".U)
      dut.clock.step()

      issue(dut, "hffffffff".U, 2.U, ysyx_26030103_MDUOp.MULHSU)
      waitResponse(dut)
      dut.io.Resp.bits.Result.expect("hffffffff".U)
      dut.clock.step()

      issue(dut, "hffffffff".U, 2.U, ysyx_26030103_MDUOp.MULHU)
      waitResponse(dut)
      dut.io.Resp.bits.Result.expect(1.U)
      dut.clock.step()

      issue(dut, "h80000000".U, "h80000000".U, ysyx_26030103_MDUOp.MULH)
      waitResponse(dut)
      dut.io.Resp.bits.Result.expect("h40000000".U)
      dut.clock.step()

      issue(dut, "h80000000".U, "hffffffff".U, ysyx_26030103_MDUOp.MULHSU)
      waitResponse(dut)
      dut.io.Resp.bits.Result.expect("h80000000".U)
      dut.clock.step()
    }
  }

  it should "handle signed and unsigned divide/remainder" in {
    simulate(new ysyx_26030103_MDU) { dut =>
      dut.io.Flush.poke(false.B)
      dut.io.Resp.ready.poke(true.B)

      issue(dut, "hfffffff9".U, 3.U, ysyx_26030103_MDUOp.DIV)
      waitResponse(dut)
      dut.io.Resp.bits.Result.expect("hfffffffe".U)
      dut.clock.step()

      issue(dut, 7.U, 3.U, ysyx_26030103_MDUOp.DIVU)
      waitResponse(dut)
      dut.io.Resp.bits.Result.expect(2.U)
      dut.clock.step()

      issue(dut, "hfffffff9".U, 3.U, ysyx_26030103_MDUOp.REM)
      waitResponse(dut)
      dut.io.Resp.bits.Result.expect("hffffffff".U)
      dut.clock.step()

      issue(dut, 7.U, 3.U, ysyx_26030103_MDUOp.REMU)
      waitResponse(dut)
      dut.io.Resp.bits.Result.expect(1.U)
      dut.clock.step()
    }
  }

  it should "support parameterized SRT divide and remainder" in {
    val config = ysyx_26030103_NPCConfig(
      UseM = true,
      DIVImpl = ysyx_26030103_DIVImpl.SRT,
      DIVRadix = 4
    )
    simulate(new ysyx_26030103_MDU(config)) { dut =>
      dut.io.Flush.poke(false.B)
      dut.io.Resp.ready.poke(true.B)

      issue(dut, 13.U, 3.U, ysyx_26030103_MDUOp.DIVU)
      waitResponse(dut)
      dut.io.Resp.bits.Result.expect(4.U)
      dut.clock.step()

      issue(dut, 13.U, 3.U, ysyx_26030103_MDUOp.REMU)
      waitResponse(dut)
      dut.io.Resp.bits.Result.expect(1.U)
      dut.clock.step()
    }
  }

  it should "apply RV32 divide corner cases and flush" in {
    simulate(new ysyx_26030103_MDU) { dut =>
      dut.io.Flush.poke(false.B)
      dut.io.Resp.ready.poke(true.B)

      issue(dut, LHS, RHS, ysyx_26030103_MDUOp.DIV)
      waitResponse(dut)
      dut.io.Resp.bits.Result.expect(LHS)
      dut.clock.step()

      issue(dut, LHS, RHS, ysyx_26030103_MDUOp.DIVU)
      waitResponse(dut)
      dut.io.Resp.bits.Result.expect(0.U)
      dut.clock.step()

      issue(dut, 9.U, 0.U, ysyx_26030103_MDUOp.DIV)
      waitResponse(dut)
      dut.io.Resp.bits.Result.expect("hffffffff".U)
      dut.clock.step()

      issue(dut, 9.U, 0.U, ysyx_26030103_MDUOp.REM)
      waitResponse(dut)
      dut.io.Resp.bits.Result.expect(9.U)
      dut.clock.step()

      issue(dut, 11.U, 3.U, ysyx_26030103_MDUOp.MUL)
      dut.io.Flush.poke(true.B)
      dut.clock.step()
      dut.io.Resp.valid.expect(false.B)
      dut.io.Flush.poke(false.B)
    }
  }

  it should "hold a routed response while backpressured" in {
    simulate(new ysyx_26030103_MDU) { dut =>
      dut.io.Flush.poke(false.B)
      dut.io.Resp.ready.poke(false.B)
      dut.io.Req.bits.LHS.poke(7.U)
      dut.io.Req.bits.RHS.poke(6.U)
      dut.io.Req.bits.MDUOp.poke(ysyx_26030103_MDUOp.MUL)
      dut.io.Req.valid.poke(true.B)
      dut.io.Req.ready.expect(true.B)
      dut.clock.step()

      dut.io.Req.valid.poke(false.B)
      waitResponseBlocked(dut)
      dut.io.Resp.bits.Result.expect(42.U)
      dut.clock.step()
      dut.io.Resp.valid.expect(true.B)
      dut.io.Resp.bits.Result.expect(42.U)

      dut.io.Resp.ready.poke(true.B)
      dut.clock.step()
      dut.io.Resp.valid.expect(false.B)

      dut.io.Flush.poke(true.B)
      dut.io.Req.valid.poke(true.B)
      dut.io.Req.ready.expect(false.B)
      dut.io.Resp.valid.expect(false.B)
      dut.clock.step()
      dut.io.Flush.poke(false.B)
      dut.io.Req.valid.poke(false.B)
      dut.io.Resp.valid.expect(false.B)
    }
  }

  it should "replace a consumed result with the next routed operation" in {
    simulate(new ysyx_26030103_MDU) { dut =>
      dut.io.Flush.poke(false.B)
      dut.io.Resp.ready.poke(true.B)

      dut.io.Req.bits.LHS.poke(7.U)
      dut.io.Req.bits.RHS.poke(6.U)
      dut.io.Req.bits.MDUOp.poke(ysyx_26030103_MDUOp.MUL)
      dut.io.Req.valid.poke(true.B)
      dut.clock.step()

      waitResponse(dut)
      dut.io.Req.bits.LHS.poke("hfffffff9".U)
      dut.io.Req.bits.RHS.poke(3.U)
      dut.io.Req.bits.MDUOp.poke(ysyx_26030103_MDUOp.DIV)
      dut.io.Req.ready.expect(true.B)
      dut.io.Resp.bits.Result.expect(42.U)
      dut.clock.step()

      dut.io.Req.valid.poke(false.B)
      waitResponse(dut)
      dut.io.Resp.bits.Result.expect("hfffffffe".U)
      dut.clock.step()
      dut.io.Resp.valid.expect(false.B)
    }
  }

  it should "not let a blocked request enter the other unit" in {
    simulate(new ysyx_26030103_MDU) { dut =>
      dut.io.Flush.poke(false.B)
      dut.io.Resp.ready.poke(false.B)

      dut.io.Req.bits.LHS.poke(7.U)
      dut.io.Req.bits.RHS.poke(6.U)
      dut.io.Req.bits.MDUOp.poke(ysyx_26030103_MDUOp.MUL)
      dut.io.Req.valid.poke(true.B)
      dut.clock.step()

      waitResponseBlocked(dut)
      // MUL 响应被回压时，新的 DIV 请求不能偷偷进入 DIVUnit。
      dut.io.Req.bits.LHS.poke(9.U)
      dut.io.Req.bits.RHS.poke(3.U)
      dut.io.Req.bits.MDUOp.poke(ysyx_26030103_MDUOp.DIV)
      dut.io.Req.ready.expect(false.B)
      dut.clock.step()

      dut.io.Req.valid.poke(false.B)
      dut.io.Resp.ready.poke(true.B)
      dut.clock.step()

      // 旧 MUL 已消费，DIVUnit 应该仍为空闲，可以正式接收 DIV。
      dut.io.Req.valid.poke(true.B)
      dut.io.Req.ready.expect(true.B)
      dut.clock.step()
      dut.io.Req.valid.poke(false.B)
      waitResponse(dut)
      dut.io.Resp.bits.Result.expect(3.U)
      dut.clock.step()
      dut.io.Resp.valid.expect(false.B)
    }
  }
}
