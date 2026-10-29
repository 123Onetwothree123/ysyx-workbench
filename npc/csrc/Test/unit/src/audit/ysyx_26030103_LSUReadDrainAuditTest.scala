package ysyx_26030103.audit

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.ysyx_26030103.mem.ysyx_26030103_LSU
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** 非 DCache 直读路径缺少 RLAST 时，LSU 的 StatesReadDrain 不能永久挂死。
  *
  * 单拍 AXI 读本应在第一拍给出 RLAST。若从设备违规一直不给，LSU 应以
  * 访问故障完成该 load，让流水线恢复。
  */
class ysyx_26030103_LSUReadDrainAuditTest
    extends AnyFlatSpec with Matchers {
  private def bool(value: Bool): Boolean = value.peek().litValue == 1

  "LSU without DCache" should
    "直读缺少 RLAST 时在有限时间内以 fault 恢复" in {
    simulate(new ysyx_26030103_LSU(XLEN = 32, DCacheEnable = false)) { dut =>
      val clock = dut.clock
      dut.reset.poke(true.B)
      clock.step(2)
      dut.reset.poke(false.B)
      dut.io.in.valid.poke(false.B)
      dut.io.in.bits.poke(0.U.asTypeOf(chiselTypeOf(dut.io.in.bits)))
      dut.io.out.ready.poke(true.B)
      dut.io.DCacheFlush.poke(false.B)
      dut.io.BusQuiet.poke(true.B)
      dut.io.ReservationClear.poke(false.B)
      dut.io.DCacheDmaSnoopValid.poke(false.B)
      dut.io.DCacheDmaSnoopStart.poke(0.U)
      dut.io.DCacheDmaSnoopEnd.poke(0.U)
      dut.io.ViptPAddr.poke(0.U)
      dut.io.ViptPAddrValid.poke(false.B)
      dut.io.ViptPAddrVaddr.poke(0.U)
      dut.io.ViptKill.poke(false.B)
      dut.io.PmpFault.poke(false.B)
      dut.io.DataBus.AW.AWREADY.poke(false.B)
      dut.io.DataBus.W.WREADY.poke(false.B)
      dut.io.DataBus.B.BVALID.poke(false.B)
      dut.io.DataBus.B.BID.poke(0.U)
      dut.io.DataBus.B.BRESP.poke(0.U)
      dut.io.DataBus.AR.ARREADY.poke(false.B)
      dut.io.DataBus.R.RVALID.poke(false.B)
      dut.io.DataBus.R.RID.poke(0.U)
      dut.io.DataBus.R.RDATA.poke(0.U)
      dut.io.DataBus.R.RRESP.poke(0.U)
      dut.io.DataBus.R.RLAST.poke(false.B)

      val addrWidth = dut.io.in.bits.ALUResult.getWidth
      dut.io.in.bits.poke(0.U.asTypeOf(chiselTypeOf(dut.io.in.bits)))
      dut.io.in.bits.Retire.poke(true.B)
      dut.io.in.bits.RegisterWrite.poke(true.B)
      dut.io.in.bits.WBSelect.poke(1.U(2.W))
      dut.io.in.bits.Rd.poke(5.U)
      dut.io.in.bits.MemoryValid.poke(true.B)
      dut.io.in.bits.MemoryWrite.poke(false.B)
      dut.io.in.bits.ALUResult.poke(0x80000000L.U(addrWidth.W))
      dut.io.in.bits.VAddr.poke(0x80000000L.U(addrWidth.W))
      dut.io.in.bits.WidthSelect.poke(2.U(2.W))
      dut.io.in.bits.pc.poke(0x80000000L.U(addrWidth.W))
      dut.io.in.valid.poke(true.B)

      var cycles = 0
      while (!(bool(dut.io.in.valid) && bool(dut.io.in.ready)) &&
          cycles < 20) {
        clock.step(); cycles += 1
      }
      withClue("load 未被受理") { cycles should be < 20 }
      clock.step()
      dut.io.in.valid.poke(false.B)

      cycles = 0
      while (!bool(dut.io.DataBus.AR.ARVALID) && cycles < 20) {
        clock.step(); cycles += 1
      }
      bool(dut.io.DataBus.AR.ARVALID) should be(true)
      dut.io.DataBus.AR.ARREADY.poke(true.B)
      clock.step()
      dut.io.DataBus.AR.ARREADY.poke(false.B)

      // 单拍读却缺少 RLAST：LSU 进入 StatesReadDrain。
      dut.io.DataBus.R.RVALID.poke(true.B)
      dut.io.DataBus.R.RDATA.poke("h12345678".U)
      dut.io.DataBus.R.RRESP.poke(0.U)
      dut.io.DataBus.R.RLAST.poke(false.B)
      clock.step()
      dut.io.DataBus.R.RVALID.poke(false.B)

      var recovered = false
      var faulted = false
      for (_ <- 0 until 500) {
        if (bool(dut.io.out.valid) || bool(dut.io.MemTrapCommit)) {
          recovered = true
        }
        if (bool(dut.io.MemTrapCommit) || bool(dut.io.AccessFault)) {
          faulted = true
        }
        clock.step()
      }
      withClue(
        s"LSU 永久停在 StatesReadDrain (active=${bool(dut.io.Active)}, " +
          s"stallR=${bool(dut.io.StallReadR)}): ") {
        recovered should be(true)
      }
      withClue("缺少 RLAST 应按访问故障提交: ") { faulted should be(true) }
    }
  }
}
