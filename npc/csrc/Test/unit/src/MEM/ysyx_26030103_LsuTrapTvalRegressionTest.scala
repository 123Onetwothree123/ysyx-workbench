package ysyx_26030103.mem

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import _root_.ysyx_26030103.common.ysyx_26030103_EXUMessage

/** LSU 访存故障 mtval 回归。
  *
  * DMMU 翻译后 LSU 入端的 ALUResult 已经是物理地址，而 VAddr 保存原始
  * 虚拟地址。RISC-V 规定地址非对齐异常的 mtval 是出错的有效地址（VA），
  * 因此 MemTrapTval 必须取 VAddr，不能取 EffectiveALUResult（PA）。
  *
  * 这里直接例化默认 LSU，用 PA 与 VA 不同的非对齐 load/store 复现：
  * 当前实现把 MemTrapTval 接到 EffectiveALUResult，会错误地给出物理地址。
  */
class ysyx_26030103_LsuTrapTvalRegressionTest
    extends AnyFlatSpec with Matchers {
  private def bool(value: Bool): Boolean = value.peek().litValue == 1
  private def long(value: UInt): Long    = value.peek().litValue.toLong

  private def resetDut(
      dut:   ysyx_26030103_LSU,
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

  /** 驱动一条 ALUResult 为 PA、VAddr 为原始 VA 的非对齐访存。 */
  private def pokeMisalignedMem(
      dut:   ysyx_26030103_LSU,
      paddr: Long,
      vaddr: Long,
      write: Boolean,
      pc:    Long
  ): Unit = {
    dut.io.in.bits.poke(0.U.asTypeOf(new ysyx_26030103_EXUMessage))
    dut.io.in.bits.Retire.poke(true.B)
    dut.io.in.bits.RegisterWrite.poke((!write).B)
    dut.io.in.bits.MemoryValid.poke(true.B)
    dut.io.in.bits.MemoryWrite.poke(write.B)
    dut.io.in.bits.WidthSelect.poke(2.U(2.W))
    dut.io.in.bits.ALUResult.poke(paddr.U(32.W))
    dut.io.in.bits.VAddr.poke(vaddr.U(32.W))
    dut.io.in.bits.pc.poke(pc.U(32.W))
  }

  /** 等待并检查一次访存故障提交；MemTrapTval 必须是虚拟地址。 */
  private def expectTrap(
      dut:   ysyx_26030103_LSU,
      clock: Clock,
      cause: Long,
      tval:  Long,
      pc:    Long
  ): Unit = {
    var trapped = false
    var cycles  = 0
    while (!trapped && cycles < 20) {
      if (bool(dut.io.MemTrapCommit)) {
        trapped = true
        long(dut.io.MemTrapCause) should be(cause)
        long(dut.io.MemTrapPC) should be(pc)
        // 关键断言：PA=0x80001002 不能出现在 mtval 中，必须是 VA。
        long(dut.io.MemTrapTval) should be(tval)
        bool(dut.io.out.bits.Retire) should be(false)
      } else {
        clock.step()
        cycles += 1
      }
    }
    withClue(s"等待 MemTrapCommit 超时（已等 $cycles 拍）") {
      trapped should be(true)
    }
  }

  "LSU trap tval" should "非对齐 load 的 MemTrapTval 使用虚拟地址而非物理地址" in {
    simulate(new ysyx_26030103_LSU()) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      // 模拟 PIPT DMMU 翻译：0x80000002 -> 0x80001002。
      pokeMisalignedMem(
        dut,
        paddr = 0x80001002L,
        vaddr = 0x80000002L,
        write = false,
        pc = 0x80000000L
      )
      dut.io.in.valid.poke(true.B)

      expectTrap(
        dut,
        clock,
        cause = 4L, // load address misaligned
        tval = 0x80000002L,
        pc = 0x80000000L
      )
    }
  }

  it should "非对齐 store 的 MemTrapTval 使用虚拟地址而非物理地址" in {
    simulate(new ysyx_26030103_LSU()) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      pokeMisalignedMem(
        dut,
        paddr = 0x80001006L,
        vaddr = 0x80000006L,
        write = true,
        pc = 0x80000004L
      )
      dut.io.in.valid.poke(true.B)

      expectTrap(
        dut,
        clock,
        cause = 6L, // store/AMO address misaligned
        tval = 0x80000006L,
        pc = 0x80000004L
      )
    }
  }
}
