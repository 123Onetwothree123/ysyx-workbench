package ysyx_26030103.audit

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.ysyx_26030103.cache.ysyx_26030103_DCacheReg
import _root_.ysyx_26030103.common.ysyx_26030103_EXUMessage
import _root_.ysyx_26030103.common.ysyx_26030103_PMARegion
import _root_.ysyx_26030103.mmu._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** 审计探针：DCache DMA replay 持续 valid 与 VIPT LSUMMU xwait flush。 */
class ysyx_26030103_CacheMmuProbeAuditTest
    extends AnyFlatSpec with Matchers {
  private val LineBase = 0x80000000L

  private def bool(value: Bool): Boolean = value.peek().litValue == 1
  private def long(value: UInt): Long    = value.peek().litValue.toLong

  private val region = ysyx_26030103_PMARegion(
    Base = 0x80000000L,
    Size = 0x10000000L,
    Cacheable = true,
    Executable = true
  )

  private def newDcache: ysyx_26030103_DCacheReg =
    new ysyx_26030103_DCacheReg(
      Enable = true,
      BlockSizeLog2 = 4,
      IndexBits = 4,
      AddressWidth = 32,
      DataWidth = 32,
      DmaSnoop = true,
      PMARegions = Seq(region)
    )

  /** 初始化为空闲，并完成一笔成功 4 拍 refill；resp.ready 保持 0。 */
  private def setupDcacheWithPendingResponse(
    dut:   ysyx_26030103_DCacheReg,
    clock: Clock
  ): Unit = {
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

    // 失效 load，触发 4 拍 refill。
    dut.io.req.bits.addr.poke(LineBase.U)
    dut.io.req.bits.vaddr.poke(LineBase.U)
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

    for (beat <- 0 until 4) {
      dut.io.AXI.R.RVALID.poke(true.B)
      dut.io.AXI.R.RDATA.poke((0x70000000L + beat).U)
      dut.io.AXI.R.RRESP.poke(0.U)
      dut.io.AXI.R.RLAST.poke((beat == 3).B)
      clock.step()
      dut.io.AXI.R.RVALID.poke(false.B)
      dut.io.AXI.R.RLAST.poke(false.B)
    }

    cycles = 0
    while (!bool(dut.io.resp.valid) && cycles < 30) {
      clock.step(); cycles += 1
    }
    withClue("refill 后未产生待接收响应: ") {
      bool(dut.io.resp.valid) should be(true)
    }
    bool(dut.io.resp.ready) should be(false)
  }

  private def startSnoop(dut: ysyx_26030103_DCacheReg): Unit = {
    dut.io.dma_snoop_start.poke(LineBase.U)
    dut.io.dma_snoop_end.poke((LineBase + 3L).U)
    dut.io.dma_snoop_valid.poke(true.B)
  }

  /** 接受一笔 replay AR 并喂 4 拍成功数据，同时采样响应/ready。 */
  private def stepAndServiceBus(
    dut:    ysyx_26030103_DCacheReg,
    clock:  Clock,
    tag:    Long,
    gotResp:    () => Unit,
    sawReady:   () => Unit
  ): Unit = {
    if (bool(dut.io.dma_snoop_ready)) sawReady()
    if (bool(dut.io.resp.valid)) gotResp()
    if (bool(dut.io.AXI.AR.ARVALID)) {
      dut.io.AXI.AR.ARREADY.poke(true.B)
      if (bool(dut.io.dma_snoop_ready)) sawReady()
      if (bool(dut.io.resp.valid)) gotResp()
      clock.step()
      dut.io.AXI.AR.ARREADY.poke(false.B)
      val beats = long(dut.io.AXI.AR.ARLEN).toInt + 1
      for (beat <- 0 until beats) {
        dut.io.AXI.R.RVALID.poke(true.B)
        dut.io.AXI.R.RDATA.poke((tag + beat).U)
        dut.io.AXI.R.RRESP.poke(0.U)
        dut.io.AXI.R.RLAST.poke((beat + 1 == beats).B)
        if (bool(dut.io.dma_snoop_ready)) sawReady()
        if (bool(dut.io.resp.valid)) gotResp()
        clock.step()
        dut.io.AXI.R.RVALID.poke(false.B)
        dut.io.AXI.R.RLAST.poke(false.B)
      }
    } else {
      clock.step()
    }
  }

  "DCacheReg" should
    "在 dma_snoop_ready 握手后释放 valid 时仍能完成 replay" in {
    simulate(newDcache) { dut =>
      val clock = dut.clock
      setupDcacheWithPendingResponse(dut, clock)

      startSnoop(dut)
      var cycles = 0
      while (!bool(dut.io.dma_snoop_ready) && cycles < 100) {
        clock.step(); cycles += 1
      }
      withClue(s"DMA snoop 在 $cycles 拍内未 ready: ") {
        bool(dut.io.dma_snoop_ready) should be(true)
      }
      // AXI 握手完成，arbiter 释放 valid。
      clock.step()
      dut.io.dma_snoop_valid.poke(false.B)
      dut.io.resp.ready.poke(true.B)

      var gotResp = false
      var readyHigh = false
      cycles = 0
      while (!gotResp && cycles < 200) {
        stepAndServiceBus(
          dut,
          clock,
          tag = 0x72000000L,
          () => gotResp = true,
          () => readyHigh = true
        )
        cycles += 1
      }
      withClue(s"握手释放 valid 后 $cycles 拍内仍未交付 replay 响应: ") {
        gotResp should be(true)
      }
      withClue("交付的不是 replay 读回的新数据: ") {
        long(dut.io.resp.bits.data) should be(0x72000000L)
      }
    }
  }

  private def runFlushXwait(xlen: Int): Unit = {
    simulate(
      new ysyx_26030103_LSUMMU(
        ParallelVipt = true,
        XLEN = xlen,
        AddressWidth = xlen
      )
    ) { dut =>
      val clock = dut.clock
      val Va    = 0x00001050L
      dut.reset.poke(true.B)
      clock.step(2)
      dut.reset.poke(false.B)
      dut.io.in.valid.poke(false.B)
      dut.io.in.bits.poke(0.U.asTypeOf(new ysyx_26030103_EXUMessage(xlen)))
      dut.io.out.ready.poke(true.B)
      dut.io.privilege.poke(1.U)
      dut.io.satp.poke(
        (if (xlen == 32) BigInt("80000000", 16)
         else BigInt("8000000000000000", 16)).U(xlen.W)
      )
      dut.io.sum.poke(false.B)
      dut.io.mxr.poke(false.B)
      dut.io.flush.poke(false.B)
      dut.io.pmpFault.poke(false.B)
      dut.io.memActive.poke(false.B)
      dut.io.memTrap.poke(false.B)
      dut.io.req.ready.poke(false.B)
      dut.io.resp.valid.poke(false.B)
      dut.io.resp.bits.poke(
        0.U.asTypeOf(new ysyx_26030103_MMUResponse(xlen, xlen))
      )

      // 发起一笔 load，让它先转发给 LSU，再进入 xwait 等 MMU。
      dut.io.in.bits.poke(0.U.asTypeOf(new ysyx_26030103_EXUMessage(xlen)))
      dut.io.in.bits.MemoryValid.poke(true.B)
      dut.io.in.bits.MemoryWrite.poke(false.B)
      dut.io.in.bits.ALUResult.poke(Va.U(xlen.W))
      dut.io.in.bits.VAddr.poke(Va.U(xlen.W))
      dut.io.in.bits.WidthSelect.poke(2.U)
      dut.io.in.bits.pc.poke(0x1000L.U(xlen.W))
      dut.io.in.valid.poke(true.B)

      var cycles = 0
      while (!bool(dut.io.in.ready) && cycles < 20) {
        clock.step(); cycles += 1
      }
      withClue("LSUMMU 未受理 load: ") { bool(dut.io.in.ready) should be(true) }
      clock.step()
      dut.io.in.valid.poke(false.B)

      cycles = 0
      while (!bool(dut.io.out.valid) && cycles < 20) {
        clock.step(); cycles += 1
      }
      withClue("load 未转发给 LSU: ") { bool(dut.io.out.valid) should be(true) }
      clock.step() // out.ready=true，LSU 接收

      cycles = 0
      while (!bool(dut.io.req.valid) && cycles < 20) {
        clock.step(); cycles += 1
      }
      withClue("MMU 翻译请求未发出: ") {
        bool(dut.io.req.valid) should be(true)
      }
      dut.io.req.ready.poke(true.B)
      clock.step()
      dut.io.req.ready.poke(false.B)

      // 已进入 xwait：req 握手完成，resp.valid 保持 0。
      bool(dut.io.busy) should be(true)
      bool(dut.io.resp.valid) should be(false)

      // 冲刷一拍；MMU 会丢弃在途翻译且不再返回响应。
      dut.io.flush.poke(true.B)
      clock.step()
      dut.io.flush.poke(false.B)

      cycles = 0
      while (bool(dut.io.busy) && cycles < 20) {
        clock.step(); cycles += 1
      }
      withClue(
        s"RV$xlen flush 后 ${cycles} 拍 busy 仍为高（xwait 未复位）: "
      ) {
        bool(dut.io.busy) should be(false)
      }
      withClue("flush 后 LSUMMU 未能重新受理请求: ") {
        bool(dut.io.in.ready) should be(true)
      }
    }
  }

  "LSUMMU VIPT parallel" should "flush 必须释放等待翻译的 busy (RV32)" in {
    runFlushXwait(32)
  }

  it should "flush 必须释放等待翻译的 busy (RV64)" in {
    runFlushXwait(64)
  }
}
