package ysyx_26030103.mem

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import _root_.ysyx_26030103.common.ysyx_26030103_EXUMessage

/** 无 MMU 时 LSU 的 PMP 故障通路测试。
  *
  * PmpFault 由顶层 PMP 组合给出。故障指令必须在任何 AW/W/AR 握手之前
  * 被拦下，并按 load=5、store=7 精确提交，不能写回或退休。
  */
class ysyx_26030103_LSUPmpTest extends AnyFlatSpec with Matchers {
  private def bool(value: Bool): Boolean = value.peek().litValue == 1
  private def long(value: UInt): Long = value.peek().litValue.toLong

  private def resetDut(
      dut: ysyx_26030103_LSU,
      clock: Clock,
      reset: Reset
  ): Unit = {
    reset.poke(true.B)
    clock.step(2)
    reset.poke(false.B)
    dut.io.in.valid.poke(false.B)
    dut.io.in.bits.poke(0.U.asTypeOf(new ysyx_26030103_EXUMessage))
    dut.io.out.ready.poke(true.B)
    dut.io.DCacheFlush.poke(false.B)
    dut.io.BusQuiet.poke(true.B)
    dut.io.ReservationClear.poke(false.B)
    dut.io.ViptPAddr.poke(0.U(32.W))
    dut.io.ViptPAddrValid.poke(false.B)
    dut.io.ViptPAddrVaddr.poke(0.U(32.W))
    dut.io.ViptKill.poke(false.B)
    dut.io.PmpFault.poke(false.B)
    dut.io.DataBus.AW.AWREADY.poke(false.B)
    dut.io.DataBus.W.WREADY.poke(false.B)
    dut.io.DataBus.B.BVALID.poke(false.B)
    dut.io.DataBus.B.BID.poke(0.U(4.W))
    dut.io.DataBus.B.BRESP.poke(0.U(2.W))
    dut.io.DataBus.AR.ARREADY.poke(false.B)
    dut.io.DataBus.R.RVALID.poke(false.B)
    dut.io.DataBus.R.RID.poke(0.U(4.W))
    dut.io.DataBus.R.RDATA.poke(0.U(32.W))
    dut.io.DataBus.R.RRESP.poke(0.U(2.W))
    dut.io.DataBus.R.RLAST.poke(false.B)
  }

  private def pokeMem(
      dut: ysyx_26030103_LSU,
      address: Long,
      write: Boolean,
      pc: Long
  ): Unit = {
    dut.io.in.bits.poke(0.U.asTypeOf(new ysyx_26030103_EXUMessage))
    dut.io.in.bits.Retire.poke(true.B)
    dut.io.in.bits.RegisterWrite.poke(if (write) false.B else true.B)
    dut.io.in.bits.MemoryValid.poke(true.B)
    dut.io.in.bits.MemoryWrite.poke(if (write) true.B else false.B)
    dut.io.in.bits.ALUResult.poke(address.U(32.W))
    dut.io.in.bits.VAddr.poke(address.U(32.W))
    dut.io.in.bits.WidthSelect.poke(2.U(2.W))
    dut.io.in.bits.pc.poke(pc.U(32.W))
  }

  private def expectNoBus(dut: ysyx_26030103_LSU): Unit = {
    bool(dut.io.DataBus.AW.AWVALID) should be(false)
    bool(dut.io.DataBus.W.WVALID) should be(false)
    bool(dut.io.DataBus.AR.ARVALID) should be(false)
  }

  "LSU PMP" should "在 store 上产生 cause=7 且不发出 AW/W" in {
    simulate(new ysyx_26030103_LSU()) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      val pc = 0x80000100L
      pokeMem(dut, 0x80002000L, write = true, pc)
      dut.io.PmpFault.poke(true.B)
      dut.io.in.valid.poke(true.B)

      var trapped = false
      for (_ <- 0 until 12) {
        expectNoBus(dut)
        if (bool(dut.io.MemTrapCommit)) {
          trapped = true
          long(dut.io.MemTrapCause) should be(7L)
          long(dut.io.MemTrapPC) should be(pc)
          long(dut.io.MemTrapTval) should be(0x80002000L)
          bool(dut.io.out.bits.Retire) should be(false)
        }
        clock.step()
      }
      trapped should be(true)
      expectNoBus(dut)
    }
  }

  it should "在 load 上产生 cause=5 且不发出 AR" in {
    simulate(new ysyx_26030103_LSU()) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      val pc = 0x80000200L
      pokeMem(dut, 0x80003000L, write = false, pc)
      dut.io.PmpFault.poke(true.B)
      dut.io.in.valid.poke(true.B)

      var trapped = false
      for (_ <- 0 until 12) {
        expectNoBus(dut)
        if (bool(dut.io.MemTrapCommit)) {
          trapped = true
          long(dut.io.MemTrapCause) should be(5L)
          long(dut.io.MemTrapPC) should be(pc)
          long(dut.io.MemTrapTval) should be(0x80003000L)
          bool(dut.io.out.bits.Retire) should be(false)
        }
        clock.step()
      }
      trapped should be(true)
      expectNoBus(dut)
    }
  }

  it should "PMP 放行时仍按原路径访问总线" in {
    simulate(new ysyx_26030103_LSU()) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      pokeMem(dut, 0x80004000L, write = false, 0x80000300L)
      dut.io.PmpFault.poke(false.B)
      dut.io.in.valid.poke(true.B)

      var sawAr = false
      for (_ <- 0 until 8) {
        if (bool(dut.io.DataBus.AR.ARVALID)) {
          sawAr = true
          long(dut.io.DataBus.AR.ARADDR) should be(0x80004000L)
        }
        // 不真正完成读事务，只确认 PMP 放行后 AR 仍会正常出现。
        clock.step()
      }
      sawAr should be(true)
    }
  }
}
