package ysyx_26030103.mem

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import _root_.ysyx_26030103.common.ysyx_26030103_EXUMessage

/** LSU + DCache VIPT 并行路径的叶级测试。
  *
  * 验证 PA 未到达时 load 已被 LSU/DCache 持有，MMU 翻译故障的 ViptKill 能撤销该请求且不产生总线事务/响应。
  */
class ysyx_26030103_LSUViptTest extends AnyFlatSpec with Matchers {
  private val AddrW = 32
  private val Va    = 0x00001050L

  private def bool(value: Bool): Boolean = value.peek().litValue == 1
  private def long(value: UInt): Long    = value.peek().litValue.toLong

  private def waitUntil(clock: Clock, cond: => Boolean, message: String): Unit = {
    var cycles = 0
    while (!cond && cycles < 200) {
      clock.step()
      cycles += 1
    }
    withClue(s"$message（等待 $cycles 拍）") { cond should be(true) }
  }

  private def pokeLoad(dut: ysyx_26030103_LSU): Unit = {
    dut.io.in.bits.poke(0.U.asTypeOf(new ysyx_26030103_EXUMessage))
    dut.io.in.bits.MemoryValid.poke(true.B)
    dut.io.in.bits.MemoryWrite.poke(false.B)
    dut.io.in.bits.ALUResult.poke(Va.U(AddrW.W))
    dut.io.in.bits.VAddr.poke(Va.U(AddrW.W))
    dut.io.in.bits.WidthSelect.poke(2.U(2.W))
    dut.io.in.bits.pc.poke(0x1000.U(AddrW.W))
  }

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
    dut.io.ViptPAddr.poke(0.U(AddrW.W))
    dut.io.ViptPAddrValid.poke(false.B)
    dut.io.ViptPAddrVaddr.poke(Va.U(AddrW.W))
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

  "LSU VIPT parallel" should "PA 未到时持有 load，ViptKill 撤销请求且不发 AR" in {
    simulate(
      new ysyx_26030103_LSU(
        DCacheEnable = true,
        DCacheBlockSizeLog2 = 4,
        DCacheIndexBits = 2,
        DCacheVipt = true,
        ViptParallel = true
      )
    ) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      pokeLoad(dut)
      dut.io.in.valid.poke(true.B)
      waitUntil(clock, bool(dut.io.in.ready), "LSU 未受理 load")
      clock.step()
      dut.io.in.valid.poke(false.B)
      // DCache 已经受理但 PA 未到：LSU 保持忙，不能发 AR。
      clock.step(6)
      bool(dut.io.Busy) should be(true)
      bool(dut.io.DataBus.AR.ARVALID) should be(false)
      // 翻译故障 kill：请求被撤销，流水级释放，仍无总线事务。
      dut.io.ViptKill.poke(true.B)
      clock.step()
      dut.io.ViptKill.poke(false.B)
      clock.step()
      bool(dut.io.Busy) should be(false)
      bool(dut.io.DataBus.AR.ARVALID) should be(false)
      long(dut.io.DebugLoadDATA) should be(0)
    }
  }
}
