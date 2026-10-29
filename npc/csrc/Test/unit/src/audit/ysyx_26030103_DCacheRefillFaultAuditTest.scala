package ysyx_26030103.audit

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.ysyx_26030103.cache._
import _root_.ysyx_26030103.common.ysyx_26030103_PMARegion
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** DCache refill 中非关键词 beat 出错时，64 位 load 不能永远等不到响应。
  *
  * 64 位 load 的地址落在行内 word2，因此关键词是 beat2、宽访问高字是
  * beat3。若 beat0 返回 SLVERR，当前实现只置 RefillError 而不产生响应，
  * 后续 beat3 又被 !RefillError 挡住，LSU 会永久等待。正确实现应在
  * refill 结束时为尚未交付的 demand 产生 fault 响应。
  */
class ysyx_26030103_DCacheRefillFaultAuditTest
    extends AnyFlatSpec with Matchers {
  private val LineBase = 0x80000000L
  private val LoadAddr = LineBase + 8

  private def bool(value: Bool): Boolean = value.peek().litValue == 1
  private def long(value: UInt): Long = value.peek().litValue.toLong

  private val region = ysyx_26030103_PMARegion(
    Base = 0x80000000L,
    Size = 0x10000000L,
    Cacheable = true,
    Executable = true
  )

  private def create(kind: String): ysyx_26030103_DCacheBase =
    if (kind == "reg") {
      new ysyx_26030103_DCacheReg(
        Enable = true,
        BlockSizeLog2 = 4,
        IndexBits = 4,
        AddressWidth = 32,
        DataWidth = 64,
        PMARegions = Seq(region)
      )
    } else {
      new ysyx_26030103_DCacheSRAM(
        Enable = true,
        BlockSizeLog2 = 4,
        IndexBits = 4,
        AddressWidth = 32,
        DataWidth = 64,
        PMARegions = Seq(region)
      )
    }

  private def resetDut(
      io: ysyx_26030103_DCacheInterface,
      clock: Clock,
      reset: Reset
  ): Unit = {
    reset.poke(true.B)
    clock.step(2)
    reset.poke(false.B)
    io.req.valid.poke(false.B)
    io.req.bits.addr.poke(0.U)
    io.req.bits.vaddr.poke(0.U)
    io.req.bits.WidthSelect.poke(3.U)
    io.req.bits.signed.poke(false.B)
    io.req_paddr_valid.poke(true.B)
    io.req_paddr_vaddr.poke(0.U)
    io.req_kill.poke(false.B)
    io.resp.ready.poke(false.B)
    io.StoreValid.poke(false.B)
    io.StoreResp.poke(0.U)
    io.StoreAddr.poke(0.U)
    io.StoreVAddr.poke(0.U)
    io.StoreData.poke(0.U)
    io.StoreStrb.poke(0.U)
    io.flush.poke(false.B)
    io.dma_snoop_valid.poke(false.B)
    io.dma_snoop_start.poke(0.U)
    io.dma_snoop_end.poke(0.U)
    io.AXI.AR.ARREADY.poke(false.B)
    io.AXI.R.RVALID.poke(false.B)
    io.AXI.R.RDATA.poke(0.U)
    io.AXI.R.RRESP.poke(0.U)
    io.AXI.R.RLAST.poke(false.B)
    io.AXI.R.RID.poke(0.U)
  }

  private def run(kind: String): Unit = {
    simulate(create(kind)) { dut =>
      val clock = dut.clock
      resetDut(dut.io, clock, dut.reset)

      // 64 位 load，地址 line_base + 8 => 关键词 beat2，高字 beat3。
      dut.io.req.bits.addr.poke(LoadAddr.U)
      dut.io.req.bits.vaddr.poke(LoadAddr.U)
      dut.io.req.bits.WidthSelect.poke(3.U)
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
      long(dut.io.AXI.AR.ARLEN) should be(3L)
      dut.io.AXI.AR.ARREADY.poke(true.B)
      clock.step()
      dut.io.AXI.AR.ARREADY.poke(false.B)

      // beat0 非关键词，返回 SLVERR；关键词 beat2/high beat3 都正常。
      for (beat <- 0 until 4) {
        dut.io.AXI.R.RVALID.poke(true.B)
        dut.io.AXI.R.RDATA.poke((0x50000000L + beat).U)
        dut.io.AXI.R.RRESP.poke(if (beat == 0) 2.U else 0.U)
        dut.io.AXI.R.RLAST.poke((beat == 3).B)
        clock.step()
        dut.io.AXI.R.RVALID.poke(false.B)
        dut.io.AXI.R.RLAST.poke(false.B)
      }
      dut.io.AXI.R.RRESP.poke(0.U)

      // 响应槽保持不消费，等待 refill 结束后的 fault。
      cycles = 0
      while (!bool(dut.io.resp.valid) && cycles < 50) {
        clock.step(); cycles += 1
      }
      withClue(
        s"$kind DCache 在非关键词 beat 出错后没有在 50 拍内给出响应: ") {
        bool(dut.io.resp.valid) should be(true)
      }
      bool(dut.io.resp.bits.fault) should be(true)
      long(dut.io.resp.bits.FaultResp) should be(2L)
    }
  }

  "DCacheReg" should
    "64 位 load 遇到非关键词 beat 的 SLVERR 时最终返回 fault" in {
    run("reg")
  }

  "DCacheSRAM" should
    "64 位 load 遇到非关键词 beat 的 SLVERR 时最终返回 fault" in {
    run("sram")
  }
}
