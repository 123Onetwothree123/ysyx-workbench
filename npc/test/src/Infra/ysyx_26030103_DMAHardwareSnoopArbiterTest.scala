package ysyx_26030103.infra

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** 仲裁器 DMA 写 B 与地址级 snoop 的握手测试。
  *
  * 验证：
  *   1. 一笔 INCR burst 的 [start, end] 按 beat 数展开，跨行部分交给 Cache 自行按行大小展开； 2. 下游 B 到达后 CPU slave 的 BVALID 不会提前出现，必须等
  *      dmaSnoopDone 才释放，保证“看到 DMA 完成”时失效已经发生； 3. FIXED burst 只覆盖单个 beat 的字节范围。
  */
class ysyx_26030103_DMAHardwareSnoopArbiterTest extends AnyFlatSpec with Matchers {
  private def asBool(value: Bool): Boolean = value.peek().litValue == 1
  private def asLong(value: UInt): Long    = value.peek().litValue.toLong

  private def idleSlaveInputs(p: ysyx_26030103_AXI5IO): Unit = {
    p.AW.AWVALID.poke(false.B)
    p.AW.AWID.poke(0.U)
    p.AW.AWADDR.poke(0.U)
    p.AW.AWLEN.poke(0.U)
    p.AW.AWSIZE.poke(2.U)
    p.AW.AWBURST.poke(1.U)
    p.AW.AWPROT.poke(0.U)
    p.AW.AWCACHE.poke(0.U)
    p.AW.AWQOS.poke(0.U)
    p.W.WVALID.poke(false.B)
    p.W.WDATA.poke(0.U)
    p.W.WSTRB.poke(0.U)
    p.W.WLAST.poke(false.B)
    p.B.BREADY.poke(true.B)
    p.AR.ARVALID.poke(false.B)
    p.AR.ARID.poke(0.U)
    p.AR.ARADDR.poke(0.U)
    p.AR.ARLEN.poke(0.U)
    p.AR.ARSIZE.poke(2.U)
    p.AR.ARBURST.poke(1.U)
    p.AR.ARPROT.poke(0.U)
    p.AR.ARCACHE.poke(0.U)
    p.AR.ARQOS.poke(0.U)
    p.R.RREADY.poke(true.B)
  }

  private def idleMasterInputs(p: ysyx_26030103_AXI5IO): Unit = {
    p.AW.AWREADY.poke(true.B)
    p.W.WREADY.poke(true.B)
    p.B.BVALID.poke(false.B)
    p.B.BID.poke(0.U)
    p.B.BRESP.poke(0.U)
    p.AR.ARREADY.poke(true.B)
    p.R.RVALID.poke(false.B)
    p.R.RID.poke(0.U)
    p.R.RDATA.poke(0.U)
    p.R.RRESP.poke(0.U)
    p.R.RLAST.poke(false.B)
  }

  private def waitUntil(clock: Clock, cond: => Boolean, message: String): Unit = {
    var cycles = 0
    while (!cond && cycles < 100) {
      clock.step()
      cycles += 1
    }
    withClue(message) { cond should be(true) }
  }

  private def dmaWrite(
    dut:   ysyx_26030103_AXI5Arbiter,
    clock: Clock,
    addr:  Long,
    len:   Int,
    size:  Int,
    burst: Int
  ): Unit = {
    dut.io.dma.AW.AWVALID.poke(true.B)
    dut.io.dma.AW.AWADDR.poke(addr.U)
    dut.io.dma.AW.AWID.poke(2.U)
    dut.io.dma.AW.AWLEN.poke(len.U)
    dut.io.dma.AW.AWSIZE.poke(size.U)
    dut.io.dma.AW.AWBURST.poke(burst.U)
    waitUntil(clock, asBool(dut.io.dma.AW.AWREADY), "DMA AW 未握手")
    clock.step()
    dut.io.dma.AW.AWVALID.poke(false.B)

    dut.io.dma.W.WVALID.poke(true.B)
    for (beat <- 0 to len) {
      dut.io.dma.W.WDATA.poke((0x1000 + beat).U)
      dut.io.dma.W.WSTRB.poke("hf".U)
      dut.io.dma.W.WLAST.poke((beat == len).B)
      waitUntil(clock, asBool(dut.io.dma.W.WREADY), s"DMA W beat $beat 未握手")
      clock.step()
    }
    dut.io.dma.W.WVALID.poke(false.B)
    dut.io.dma.W.WLAST.poke(false.B)
  }

  "AXI5Arbiter" should "B 保持到地址级 snoop 完成并输出 burst 字节范围" in {
    simulate(
      new ysyx_26030103_AXI5Arbiter(
        AddressWidth = 32,
        HardwareSnoop = true
      )
    ) { dut =>
      val clock = dut.clock
      dut.reset.poke(true.B)
      clock.step(2)
      dut.reset.poke(false.B)
      idleSlaveInputs(dut.io.ifu)
      idleSlaveInputs(dut.io.lsu)
      idleSlaveInputs(dut.io.dma)
      idleMasterInputs(dut.io.memory)
      dut.io.dmaARLock.poke(false.B)
      dut.io.dmaAWLock.poke(false.B)
      dut.io.dmaSnoopDone.poke(false.B)

      // 8 个 4B beat = 32B 范围，故意跨多个 cache line。
      dmaWrite(dut, clock, 0xc0000000L, len = 7, size = 2, burst = 1)

      // 下游 B 到达后，snoop 请求出现，CPU slave B 必须继续等待。
      dut.io.memory.B.BVALID.poke(true.B)
      dut.io.memory.B.BID.poke(2.U)
      dut.io.memory.B.BRESP.poke(0.U)
      waitUntil(clock, asBool(dut.io.memory.B.BREADY), "下游 B 未握手")
      clock.step()
      dut.io.memory.B.BVALID.poke(false.B)

      waitUntil(clock, asBool(dut.io.dmaSnoopValid), "snoop 请求未出现")
      asLong(dut.io.dmaSnoopStart) should be(0xc0000000L)
      asLong(dut.io.dmaSnoopEnd) should be(0xc000001fL)
      for (_ <- 0 until 3) {
        asBool(dut.io.dma.B.BVALID) should be(false)
        clock.step()
      }

      // Cache 完成失效后才释放 CPU slave B。
      dut.io.dmaSnoopDone.poke(true.B)
      clock.step()
      dut.io.dmaSnoopDone.poke(false.B)
      asBool(dut.io.dma.B.BVALID) should be(true)
      asBool(dut.io.dmaSnoopValid) should be(false)
      waitUntil(clock, asBool(dut.io.dma.B.BREADY), "DMA B 未握手")
      clock.step()
      asBool(dut.io.dma.B.BVALID) should be(false)
    }
  }

  it should "FIXED burst 的 snoop 范围只覆盖一次 beat" in {
    simulate(
      new ysyx_26030103_AXI5Arbiter(
        AddressWidth = 32,
        HardwareSnoop = true
      )
    ) { dut =>
      val clock = dut.clock
      dut.reset.poke(true.B)
      clock.step(2)
      dut.reset.poke(false.B)
      idleSlaveInputs(dut.io.ifu)
      idleSlaveInputs(dut.io.lsu)
      idleSlaveInputs(dut.io.dma)
      idleMasterInputs(dut.io.memory)
      dut.io.dmaARLock.poke(false.B)
      dut.io.dmaAWLock.poke(false.B)
      dut.io.dmaSnoopDone.poke(false.B)

      dmaWrite(dut, clock, 0xc0000100L, len = 3, size = 2, burst = 0)
      dut.io.memory.B.BVALID.poke(true.B)
      dut.io.memory.B.BID.poke(2.U)
      dut.io.memory.B.BRESP.poke(0.U)
      waitUntil(clock, asBool(dut.io.memory.B.BREADY), "下游 B 未握手")
      clock.step()
      dut.io.memory.B.BVALID.poke(false.B)
      waitUntil(clock, asBool(dut.io.dmaSnoopValid), "snoop 请求未出现")
      asLong(dut.io.dmaSnoopStart) should be(0xc0000100L)
      asLong(dut.io.dmaSnoopEnd) should be(0xc0000103L)
    }
  }
}
