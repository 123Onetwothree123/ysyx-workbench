package ysyx_26030103.audit

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.ysyx_26030103.cache.ysyx_26030103_DCacheReg
import _root_.ysyx_26030103.common.ysyx_26030103_PMARegion
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** DMA snoop replay 不能吞掉已经产生的 refill fault 响应。
  *
  * 一次 refill 的某个 beat 出错后，DCache 已为 demand 产生 fault 响应但
  * 尚未交付。此时 DMA 命中同一行会触发“丢弃旧响应并重新读取”的 replay；
  * 若 replay 不排除 ResponseFault，成功的新读取会覆盖原 fault，LSU 会把
  * 错误访问当成成功。正确行为是保留 fault 响应，先让 CPU 看到错误。
  */
class ysyx_26030103_DCacheDmaReplayFaultAuditTest
    extends AnyFlatSpec with Matchers {
  private val LineBase = 0x80000000L

  private def bool(value: Bool): Boolean = value.peek().litValue == 1
  private def long(value: UInt): Long = value.peek().litValue.toLong

  private val region = ysyx_26030103_PMARegion(
    Base = 0x80000000L,
    Size = 0x10000000L,
    Cacheable = true,
    Executable = true
  )

  "DCacheReg" should
    "DMA snoop replay 不得覆盖尚未交付的 refill fault" in {
    simulate(new ysyx_26030103_DCacheReg(
      Enable = true,
      BlockSizeLog2 = 4,
      IndexBits = 4,
      AddressWidth = 32,
      DataWidth = 32,
      DmaSnoop = true,
      PMARegions = Seq(region)
    )) { dut =>
      val clock = dut.clock
      dut.reset.poke(true.B)
      clock.step(2)
      dut.reset.poke(false.B)
      dut.io.req.valid.poke(false.B)
      dut.io.req.bits.addr.poke(0.U)
      dut.io.req.bits.vaddr.poke(0.U)
      dut.io.req.bits.WidthSelect.poke(2.U)
      dut.io.req.bits.signed.poke(false.B)
      dut.io.req_paddr_valid.poke(true.B)
      dut.io.req_paddr_vaddr.poke(0.U)
      dut.io.req_kill.poke(false.B)
      dut.io.resp.ready.poke(false.B)
      dut.io.StoreValid.poke(false.B)
      dut.io.StoreResp.poke(0.U)
      dut.io.StoreAddr.poke(0.U)
      dut.io.StoreVAddr.poke(0.U)
      dut.io.StoreData.poke(0.U)
      dut.io.StoreStrb.poke(0.U)
      dut.io.flush.poke(false.B)
      dut.io.dma_snoop_valid.poke(false.B)
      dut.io.dma_snoop_start.poke(0.U)
      dut.io.dma_snoop_end.poke(0.U)
      dut.io.AXI.AR.ARREADY.poke(false.B)
      dut.io.AXI.R.RVALID.poke(false.B)
      dut.io.AXI.R.RDATA.poke(0.U)
      dut.io.AXI.R.RRESP.poke(0.U)
      dut.io.AXI.R.RLAST.poke(false.B)
      dut.io.AXI.R.RID.poke(0.U)

      // load miss：refill beat0 出错，其余 beat 正常，refill 完整结束。
      dut.io.req.bits.addr.poke(LineBase.U)
      dut.io.req.bits.vaddr.poke(LineBase.U)
      dut.io.req.bits.WidthSelect.poke(2.U)
      dut.io.req.valid.poke(true.B)
      var cycles = 0
      while (!bool(dut.io.req.ready) && cycles < 20) {
        clock.step(); cycles += 1
      }
      bool(dut.io.req.ready) should be(true)
      clock.step()
      dut.io.req.valid.poke(false.B)

      cycles = 0
      while (!bool(dut.io.AXI.AR.ARVALID) && cycles < 20) {
        clock.step(); cycles += 1
      }
      bool(dut.io.AXI.AR.ARVALID) should be(true)
      dut.io.AXI.AR.ARREADY.poke(true.B)
      clock.step()
      dut.io.AXI.AR.ARREADY.poke(false.B)

      for (beat <- 0 until 4) {
        dut.io.AXI.R.RVALID.poke(true.B)
        dut.io.AXI.R.RDATA.poke((0x70000000L + beat).U)
        dut.io.AXI.R.RRESP.poke(if (beat == 0) 2.U else 0.U)
        dut.io.AXI.R.RLAST.poke((beat == 3).B)
        clock.step()
        dut.io.AXI.R.RVALID.poke(false.B)
        dut.io.AXI.R.RLAST.poke(false.B)
      }
      dut.io.AXI.R.RRESP.poke(0.U)

      // refill 结束后 fault 响应已在槽中但尚未交付。
      cycles = 0
      while (!bool(dut.io.resp.valid) && cycles < 30) {
        clock.step(); cycles += 1
      }
      bool(dut.io.resp.valid) should be(true)
      bool(dut.io.resp.bits.fault) should be(true)

      // DMA 命中同一行，触发 snoop。旧实现会在这里丢弃 fault 并重放。
      dut.io.dma_snoop_start.poke(LineBase.U)
      dut.io.dma_snoop_end.poke((LineBase + 3).U)
      dut.io.dma_snoop_valid.poke(true.B)
      clock.step()
      dut.io.dma_snoop_valid.poke(false.B)

      // 解冻响应槽；旧实现已经丢掉 fault，只能等 replay 的成功数据。
      dut.io.resp.ready.poke(true.B)
      var observed: Option[Boolean] = None
      cycles = 0
      while (observed.isEmpty && cycles < 200) {
        if (bool(dut.io.resp.valid)) {
          observed = Some(bool(dut.io.resp.bits.fault))
        } else if (bool(dut.io.AXI.AR.ARVALID)) {
          // 只有错误实现才会走到 replay：给它一笔成功的行读取，让旧行为
          // 暴露为 fault=false，而不是单纯超时。
          long(dut.io.AXI.AR.ARADDR) should be(LineBase)
          dut.io.AXI.AR.ARREADY.poke(true.B)
          clock.step()
          dut.io.AXI.AR.ARREADY.poke(false.B)
          for (beat <- 0 until 4) {
            dut.io.AXI.R.RVALID.poke(true.B)
            dut.io.AXI.R.RDATA.poke((0x71000000L + beat).U)
            dut.io.AXI.R.RRESP.poke(0.U)
            dut.io.AXI.R.RLAST.poke((beat == 3).B)
            clock.step()
            dut.io.AXI.R.RVALID.poke(false.B)
            dut.io.AXI.R.RLAST.poke(false.B)
          }
        } else {
          clock.step(); cycles += 1
        }
      }
      withClue("DMA replay 后没有返回响应") { observed.isDefined should be(true) }
      withClue("原 refill fault 被 DMA replay 的成功响应覆盖: ") {
        observed.get should be(true)
      }
    }
  }
}
