package ysyx_26030103.cache

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import _root_.ysyx_26030103.common.ysyx_26030103_PhysicalMemoryMap
import _root_.ysyx_26030103.config._

/** DMA 窗口在 CPU 侧是否进入 DCache 的定向测试。
  *
  * 不启动完整 SoC/DMA 引擎，只验证两条真实数据路径：
  *   - software_flush（默认）：0xc0000000 仍可缓存，第二次 load 命中旧行；
  *     这正是现有“DMA 写 B 完成时全局失效”契约的前提。
  *   - uncached：NPCConfig 把 DMA 窗口从 Cache PMA 视图中去掉可缓存属性，
  *     每次 load 都发单拍 AR，内存更新后 CPU 立即看到。
  */
class ysyx_26030103_DmaCoherenceCacheTest
    extends AnyFlatSpec with Matchers {
  private val AddrW     = 32
  private val BlockLog2 = 4 // 16B/line
  private val IndexBits = 2

  private val DmaAddr  = 0xc0000000L
  private val SdramAddr = 0xa0000000L

  private def bool(value: Bool): Boolean = value.peek().litValue == 1
  private def long(value: UInt): Long    = value.peek().litValue.toLong

  private def waitUntil(clock: Clock, cond: => Boolean, message: String): Unit = {
    var cycles = 0
    while (!cond && cycles < 500) {
      clock.step()
      cycles += 1
    }
    withClue(s"$message（等待 $cycles 拍）") { cond should be(true) }
  }

  private def resetDut(
      io:    ysyx_26030103_DCacheInterface,
      clock: Clock,
      reset: Reset
  ): Unit = {
    reset.poke(true.B)
    clock.step(2)
    reset.poke(false.B)
    io.req.valid.poke(false.B)
    io.req.bits.addr.poke(0.U(AddrW.W))
    io.req.bits.vaddr.poke(0.U(AddrW.W))
    io.req.bits.WidthSelect.poke(2.U(2.W))
    io.req.bits.signed.poke(false.B)
    io.req_paddr_valid.poke(true.B)
    io.req_paddr_vaddr.poke(0.U(AddrW.W))
    io.req_kill.poke(false.B)
    io.resp.ready.poke(false.B)
    io.StoreValid.poke(false.B)
    io.StoreResp.poke(0.U(2.W))
    io.StoreAddr.poke(0.U(AddrW.W))
    io.StoreVAddr.poke(0.U(AddrW.W))
    io.StoreData.poke(0.U(32.W))
    io.StoreStrb.poke(0.U(4.W))
    io.AXI.AR.ARREADY.poke(false.B)
    io.AXI.R.RVALID.poke(false.B)
    io.AXI.R.RDATA.poke(0.U(32.W))
    io.AXI.R.RRESP.poke(0.U(2.W))
    io.AXI.R.RLAST.poke(false.B)
    io.flush.poke(false.B)
  }

  private def issueLoad(
      io:    ysyx_26030103_DCacheInterface,
      clock: Clock,
      addr:  Long
  ): Unit = {
    io.req.bits.addr.poke(addr.U(AddrW.W))
    io.req.bits.vaddr.poke(addr.U(AddrW.W))
    io.req.bits.WidthSelect.poke(2.U(2.W))
    io.req_paddr_vaddr.poke(addr.U(AddrW.W))
    io.req_paddr_valid.poke(true.B)
    io.req.valid.poke(true.B)
    waitUntil(clock, bool(io.req.ready), s"DCache 未受理 0x${addr.toHexString}")
    clock.step()
    io.req.valid.poke(false.B)
  }

  /** 完成当前 AR；返回实际 beats 数，供断言缓存/非缓存路径差异。 */
  private def completeRead(
      io:    ysyx_26030103_DCacheInterface,
      clock: Clock,
      word:  Long
  ): Int = {
    waitUntil(clock, bool(io.AXI.AR.ARVALID), "DCache 没有 AR")
    val beats = long(io.AXI.AR.ARLEN).toInt + 1
    io.AXI.AR.ARREADY.poke(true.B)
    clock.step()
    io.AXI.AR.ARREADY.poke(false.B)
    for (beat <- 0 until beats) {
      io.AXI.R.RDATA.poke((word + beat.toLong).U(32.W))
      io.AXI.R.RRESP.poke(0.U(2.W))
      io.AXI.R.RLAST.poke((beat + 1 == beats).B)
      io.AXI.R.RVALID.poke(true.B)
      clock.step()
    }
    io.AXI.R.RVALID.poke(false.B)
    io.AXI.R.RLAST.poke(false.B)
    beats
  }

  private def expectResponse(
      io:    ysyx_26030103_DCacheInterface,
      clock: Clock,
      data:  Long
  ): Unit = {
    waitUntil(clock, bool(io.resp.valid), "DCache 没有响应")
    long(io.resp.bits.data) should be(data)
    bool(io.resp.bits.fault) should be(false)
    io.resp.ready.poke(true.B)
    clock.step()
    io.resp.ready.poke(false.B)
  }

  private def expectNoAr(
      io:    ysyx_26030103_DCacheInterface,
      clock: Clock,
      label: String
  ): Unit = {
    for (_ <- 0 until 3) {
      withClue(label) { bool(io.AXI.AR.ARVALID) should be(false) }
      clock.step()
    }
  }

  private def cacheFor(config: ysyx_26030103_NPCConfig): ysyx_26030103_DCacheReg =
    new ysyx_26030103_DCacheReg(
      Enable = true,
      BlockSizeLog2 = BlockLog2,
      IndexBits = IndexBits,
      AddressWidth = AddrW,
      CacheableBase = 0L,
      CacheableMask = 0L,
      PMARegions = config.CachePMARegions
    )

  "DCache" should "software_flush 模式保持 DMA 窗口可缓存（现有行为）" in {
    val config = ysyx_26030103_NPCConfig(
      ResetAddr = 0x80000000L,
      PMARegions = ysyx_26030103_PhysicalMemoryMap.SoC(HasChipLink = true),
      DmaCoherence = ysyx_26030103_DmaCoherence.SoftwareFlush
    )
    simulate(cacheFor(config)) { dut =>
      val io = dut.io
      val clock = dut.clock
      resetDut(io, clock, dut.reset)

      issueLoad(io, clock, DmaAddr)
      completeRead(io, clock, 0x11110000L) should be(4)
      expectResponse(io, clock, 0x11110000L)

      // 未收到新的 DMA 写 B 失效事件前，第二次 load 必须命中旧行。
      issueLoad(io, clock, DmaAddr)
      bool(io.req_cacheable) should be(true)
      expectNoAr(io, clock, "software_flush 下 DMA 窗口应命中 DCache")
      expectResponse(io, clock, 0x11110000L)
    }
  }

  it should "uncached 模式让 DMA 窗口每次 load 都绕过 DCache" in {
    val config = ysyx_26030103_NPCConfig(
      ResetAddr = 0x80000000L,
      PMARegions = ysyx_26030103_PhysicalMemoryMap.SoC(HasChipLink = true),
      DmaCoherence = ysyx_26030103_DmaCoherence.Uncached
    )
    simulate(cacheFor(config)) { dut =>
      val io = dut.io
      val clock = dut.clock
      resetDut(io, clock, dut.reset)

      issueLoad(io, clock, DmaAddr)
      bool(io.req_cacheable) should be(false)
      completeRead(io, clock, 0x22220000L) should be(1)
      expectResponse(io, clock, 0x22220000L)

      // 模拟 DMA 更新同一地址；UNCACHED 下 CPU 再读必须重新上总线。
      issueLoad(io, clock, DmaAddr)
      bool(io.req_cacheable) should be(false)
      completeRead(io, clock, 0x33330000L) should be(1)
      expectResponse(io, clock, 0x33330000L)
    }
  }

  it should "uncached 只作用于 DMA 窗口，普通 SDRAM 仍然缓存" in {
    val config = ysyx_26030103_NPCConfig(
      ResetAddr = 0x80000000L,
      PMARegions = ysyx_26030103_PhysicalMemoryMap.SoC(HasChipLink = true),
      DmaCoherence = ysyx_26030103_DmaCoherence.Uncached
    )
    simulate(cacheFor(config)) { dut =>
      val io = dut.io
      val clock = dut.clock
      resetDut(io, clock, dut.reset)

      issueLoad(io, clock, SdramAddr)
      completeRead(io, clock, 0x44440000L) should be(4)
      expectResponse(io, clock, 0x44440000L)

      issueLoad(io, clock, SdramAddr)
      bool(io.req_cacheable) should be(true)
      expectNoAr(io, clock, "SDRAM 不应被 DMA uncached 模式影响")
      expectResponse(io, clock, 0x44440000L)
    }
  }
}
