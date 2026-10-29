package ysyx_26030103.audit

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.ysyx_26030103.cache.ysyx_26030103_DCacheReg
import _root_.ysyx_26030103.common.ysyx_26030103_PMARegion
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** DCache 审计：refill 响应违反 AXI（缺 RLAST）时不能永久挂死。
  *
  * 疑点：DCacheReg 的 SDrain 只等 RLAST；ICache 有 drain_timer，DCache
  * 没有。若从设备在最后一次 R beat 不给 RLAST，DCache 会永久停在
  * SDrain，后续 req 永远不 ready。本测试发送完整一行但不带 RLAST，
  * 然后在合理的超时窗口内检查 DCache 是否恢复（返回 fault 或重新 ready）。
  */
class ysyx_26030103_DCacheDrainAuditTest
    extends AnyFlatSpec with Matchers {
  private def bool(v: Bool): Boolean = v.peek().litValue == 1
  private def long(v: UInt): Long = v.peek().litValue.toLong

  "DCache" should "在 refill 缺少 RLAST 时最终恢复而不是永久挂死" in {
    val region = ysyx_26030103_PMARegion(
      Base = 0x80000000L,
      Size = 0x10000000L,
      Cacheable = true,
      Executable = true
    )
    simulate(new ysyx_26030103_DCacheReg(
      Enable = true,
      BlockSizeLog2 = 4,
      IndexBits = 4,
      AddressWidth = 32,
      DataWidth = 32,
      PMARegions = Seq(region)
    )) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.req.valid.poke(false.B)
      dut.io.req.bits.addr.poke(0.U)
      dut.io.req.bits.vaddr.poke(0.U)
      dut.io.req.bits.WidthSelect.poke(2.U)
      dut.io.req.bits.signed.poke(false.B)
      dut.io.req_paddr_valid.poke(true.B)
      dut.io.req_paddr_vaddr.poke(0.U)
      dut.io.req_kill.poke(false.B)
      dut.io.resp.ready.poke(true.B)
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

      // 发一笔可缓存的 miss。
      dut.io.req.valid.poke(true.B)
      dut.io.req.bits.addr.poke("h80000000".U)
      var cycles = 0
      while (!bool(dut.io.req.ready) && cycles < 20) {
        dut.clock.step(); cycles += 1
      }
      bool(dut.io.req.ready) should be(true)
      dut.clock.step()
      dut.io.req.valid.poke(false.B)

      // 接受 refill AR。
      cycles = 0
      while (!bool(dut.io.AXI.AR.ARVALID) && cycles < 20) {
        dut.clock.step(); cycles += 1
      }
      bool(dut.io.AXI.AR.ARVALID) should be(true)
      long(dut.io.AXI.AR.ARLEN) should be(3L) // 16B line = 4 beats
      dut.io.AXI.AR.ARREADY.poke(true.B)
      dut.clock.step()
      dut.io.AXI.AR.ARREADY.poke(false.B)

      // 完整发送 4 拍，但最后一拍故意不给 RLAST：DCache 会进入 SDrain。
      for (beat <- 0 until 4) {
        dut.io.AXI.R.RVALID.poke(true.B)
        dut.io.AXI.R.RDATA.poke((0x1000 + beat).U)
        dut.io.AXI.R.RRESP.poke(0.U)
        dut.io.AXI.R.RLAST.poke(false.B)
        dut.clock.step()
        dut.io.AXI.R.RVALID.poke(false.B)
      }

      // 给一个宽松的超时窗口。合规实现应在 drain 超时后返回 fault 或
      // 重新允许请求；当前实现会永久停在 SDrain。
      var recovered = false
      for (_ <- 0 until 1000) {
        dut.clock.step()
        if (bool(dut.io.req.ready) || bool(dut.io.resp.valid)) {
          recovered = true
        }
      }
      withClue("DCache stayed in SDrain forever after a refill without RLAST: ") {
        recovered should be(true)
      }
    }
  }
}
