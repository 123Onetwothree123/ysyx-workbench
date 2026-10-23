package ysyx_26030103.cache

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import _root_.ysyx_26030103.common.ysyx_26030103_PMARegion

import scala.collection.mutable

/** DMA 地址级 snoop 的 ICache 定向测试。
  *
  * 覆盖：
  *   1. DMA 改写已取指行后，后续取指自动 miss 并读到新指令； 2. burst 字节范围跨行时只失效范围覆盖的行； 3. 在途 refill 已收到旧 beat 时，snoop 丢弃旧事务和旧响应，排空后 自动重取；
  *      4. 8B/16B/32B/64B line 大小与 Reg/SRAM 两种阵列。
  *
  * 语义边界：硬件的行失效只保证“之后的取指”看到新指令；已经取入流水线 的旧指令仍需软件执行 fence.i 冲刷，这一点与本测试的叶子模块无关。
  */
class ysyx_26030103_ICacheDmaSnoopTest extends AnyFlatSpec with Matchers {
  private val AddrW     = 32
  private val IndexBits = 2 // 4 组

  // 按 64B 行布置，较小行配置下也能取到不同 index。
  private val LineA = 0x80000100L
  private val LineB = 0x80000140L
  private val LineC = 0x80000180L

  private val Pma = Seq(
    ysyx_26030103_PMARegion(
      0x80000000L,
      0x00010000L,
      Cacheable = true,
      Executable = true
    )
  )

  private def bool(value: Bool): Boolean = value.peek().litValue == 1
  private def long(value: UInt): Long    = value.peek().litValue.toLong

  private class Memory {
    private val words = mutable.Map.empty[Long, Long]
    def read(address: Long):              Long =
      words.getOrElse(address & ~3L, ((address & ~3L) ^ 0x24680000L) & 0xffffffffL)
    def write(address: Long, data: Long): Unit =
      words(address & ~3L) = data & 0xffffffffL
  }

  private def waitUntil(clock: Clock, cond: => Boolean, message: String): Unit = {
    var cycles = 0
    while (!cond && cycles < 1000) {
      clock.step()
      cycles += 1
    }
    withClue(s"$message（等待 $cycles 拍）") { cond should be(true) }
  }

  private def create(
    kind:      String,
    blockLog2: Int
  ): ysyx_26030103_ICacheBase =
    kind match {
      case "reg"  =>
        new ysyx_26030103_ICacheReg(
          Enable = true,
          BlockSizeLog2 = blockLog2,
          IndexBits = IndexBits,
          AddressWidth = AddrW,
          DmaSnoop = true,
          PMARegions = Pma
        )
      case "sram" =>
        new ysyx_26030103_ICacheSRAM(
          Enable = true,
          BlockSizeLog2 = blockLog2,
          IndexBits = IndexBits,
          AddressWidth = AddrW,
          DmaSnoop = true,
          PMARegions = Pma
        )
      case other  => throw new IllegalArgumentException(other)
    }

  private def resetDut(
    io:    ysyx_26030103_ICacheInterface,
    clock: Clock,
    reset: Reset
  ): Unit = {
    reset.poke(true.B)
    clock.step(2)
    reset.poke(false.B)
    io.fetch_addr.poke(0.U)
    io.fetch_vaddr.poke(0.U)
    io.fetch_paddr_valid.poke(true.B)
    io.fetch_paddr_vaddr.poke(0.U)
    io.fetch_valid.poke(false.B)
    io.resp_ready.poke(false.B)
    io.axi.AR.ARREADY.poke(false.B)
    io.axi.R.RDATA.poke(0.U)
    io.axi.R.RRESP.poke(0.U)
    io.axi.R.RLAST.poke(false.B)
    io.axi.R.RVALID.poke(false.B)
    io.flush.poke(false.B)
    io.kill.poke(false.B)
    io.snoop_valid.poke(false.B)
    io.snoop_addr.poke(0.U)
    io.snoop_addr_hi.poke(0.U)
    io.dma_snoop_valid.poke(false.B)
    io.dma_snoop_start.poke(0.U)
    io.dma_snoop_end.poke(0.U)
  }

  private def issueFetch(
    io:    ysyx_26030103_ICacheInterface,
    clock: Clock,
    addr:  Long
  ): Unit = {
    io.fetch_addr.poke(addr.U)
    io.fetch_vaddr.poke(addr.U)
    io.fetch_paddr_valid.poke(true.B)
    io.fetch_paddr_vaddr.poke(addr.U)
    io.fetch_valid.poke(true.B)
    waitUntil(clock, bool(io.fetch_ready), s"ICache 未受理 0x${addr.toHexString}")
    clock.step()
    io.fetch_valid.poke(false.B)
  }

  private def refill(
    io:     ysyx_26030103_ICacheInterface,
    clock:  Clock,
    line:   Long,
    mem:    Memory,
    label:  String,
    oldMem: Option[Memory] = None
  ): Unit = {
    waitUntil(clock, bool(io.axi.AR.ARVALID), s"$label 没有 refill AR")
    long(io.axi.AR.ARADDR) should be(line)
    val beats = long(io.axi.AR.ARLEN).toInt + 1
    io.axi.AR.ARREADY.poke(true.B)
    clock.step()
    io.axi.AR.ARREADY.poke(false.B)
    for (beat <- 0 until beats) {
      val source = oldMem.getOrElse(mem)
      io.axi.R.RDATA.poke(source.read(line + beat.toLong * 4L).U)
      io.axi.R.RRESP.poke(0.U)
      io.axi.R.RLAST.poke((beat + 1 == beats).B)
      io.axi.R.RVALID.poke(true.B)
      clock.step()
    }
    io.axi.R.RVALID.poke(false.B)
    io.axi.R.RLAST.poke(false.B)
  }

  private def expectResponse(
    io:    ysyx_26030103_ICacheInterface,
    clock: Clock,
    data:  Long,
    label: String
  ): Unit = {
    waitUntil(clock, bool(io.resp_valid), s"$label 没有取指响应")
    withClue(s"$label 取指数据错误") { long(io.resp_data) should be(data) }
    bool(io.resp_fault) should be(false)
    io.resp_ready.poke(true.B)
    clock.step()
    io.resp_ready.poke(false.B)
  }

  private def fetchExpectHit(
    io:    ysyx_26030103_ICacheInterface,
    clock: Clock,
    addr:  Long,
    data:  Long,
    label: String
  ): Unit = {
    issueFetch(io, clock, addr)
    for (_ <- 0 until 3) {
      bool(io.axi.AR.ARVALID) should be(false)
      clock.step()
    }
    expectResponse(io, clock, data, label)
  }

  private def fetchExpectMiss(
    io:    ysyx_26030103_ICacheInterface,
    clock: Clock,
    addr:  Long,
    line:  Long,
    mem:   Memory,
    label: String
  ): Unit = {
    issueFetch(io, clock, addr)
    refill(io, clock, line, mem, label)
    expectResponse(io, clock, mem.read(addr), label)
  }

  private def pulseSnoop(
    io:    ysyx_26030103_ICacheInterface,
    clock: Clock,
    lo:    Long,
    hi:    Long
  ): Unit = {
    io.dma_snoop_start.poke(lo.U)
    io.dma_snoop_end.poke(hi.U)
    io.dma_snoop_valid.poke(true.B)
    waitUntil(clock, bool(io.dma_snoop_ready), "ICache DMA snoop 未 ready")
    clock.step()
    io.dma_snoop_valid.poke(false.B)
  }

  private def runHitInvalidate(kind: String, blockLog2: Int): Unit = {
    simulate(create(kind, blockLog2)) { dut =>
      val io       = dut.io
      val clock    = dut.clock
      resetDut(io, clock, dut.reset)
      val mem      = new Memory
      val lineSize = 1L << blockLog2
      val justA    = LineA & ~(lineSize - 1L)

      fetchExpectMiss(io, clock, LineA, justA, mem, s"$kind/${lineSize}B 填充 A")
      fetchExpectMiss(io, clock, LineB, LineB & ~(lineSize - 1L), mem, s"$kind/${lineSize}B 填充 B")

      mem.write(LineA, 0x33333313L)
      pulseSnoop(io, clock, justA, justA + lineSize - 1L)

      // B 不在 DMA 范围内，必须继续命中。
      fetchExpectHit(io, clock, LineB, mem.read(LineB), s"$kind/${lineSize}B B 不应失效")
      // A 已经失效，无需 fence.i 就应重新取到新指令。
      issueFetch(io, clock, LineA)
      refill(io, clock, justA, mem, s"$kind/${lineSize}B A 重取")
      expectResponse(io, clock, 0x33333313L, s"$kind/${lineSize}B A 新指令")

      // fence.i 仍然有效：冲刷后再次取指必须重新走内存。
      io.flush.poke(true.B)
      clock.step()
      io.flush.poke(false.B)
      issueFetch(io, clock, LineA)
      refill(io, clock, justA, mem, s"$kind/${lineSize}B fence.i 后")
      expectResponse(io, clock, 0x33333313L, s"$kind/${lineSize}B fence.i 后")
    }
  }

  private def runBurstCrossLine(kind: String, blockLog2: Int): Unit = {
    simulate(create(kind, blockLog2)) { dut =>
      val io       = dut.io
      val clock    = dut.clock
      resetDut(io, clock, dut.reset)
      val mem      = new Memory
      val lineSize = 1L << blockLog2
      val aLine    = LineA & ~(lineSize - 1L)
      val bLine    = LineB & ~(lineSize - 1L)
      val cLine    = LineC & ~(lineSize - 1L)

      for (line <- Seq(aLine, bLine, cLine)) {
        fetchExpectMiss(io, clock, line, line, mem, s"$kind/${lineSize}B 填充")
      }
      // 范围覆盖 A、B 两行但故意不覆盖 C（跨越边界也按行展开）。
      pulseSnoop(io, clock, aLine + 1L, bLine + lineSize - 1L)
      fetchExpectHit(io, clock, cLine, mem.read(cLine), s"$kind/${lineSize}B C 保留")
      for (line <- Seq(aLine, bLine)) {
        issueFetch(io, clock, line)
        refill(io, clock, line, mem, s"$kind/${lineSize}B burst 失效")
        expectResponse(io, clock, mem.read(line), s"$kind/${lineSize}B burst")
      }
    }
  }

  private def runInflight(kind: String, blockLog2: Int): Unit = {
    simulate(create(kind, blockLog2)) { dut =>
      val io       = dut.io
      val clock    = dut.clock
      resetDut(io, clock, dut.reset)
      val mem      = new Memory
      val lineSize = 1L << blockLog2
      val line     = LineA & ~(lineSize - 1L)

      fetchExpectMiss(io, clock, line, line, mem, s"$kind/${lineSize}B in-flight 填充")
      val oldMem = new Memory
      // 先失效，让下一次取指启动一笔会读到旧数据的 refill。
      pulseSnoop(io, clock, line, line + lineSize - 1L)
      issueFetch(io, clock, line)
      waitUntil(clock, bool(io.axi.AR.ARVALID), "in-flight 没有 AR")
      val beats  = long(io.axi.AR.ARLEN).toInt + 1
      io.axi.AR.ARREADY.poke(true.B)
      clock.step()
      io.axi.AR.ARREADY.poke(false.B)

      // 回一个旧 word，可能已经 early-restart 出旧响应。
      io.axi.R.RDATA.poke(oldMem.read(line).U)
      io.axi.R.RRESP.poke(0.U)
      io.axi.R.RLAST.poke((beats == 1).B)
      io.axi.R.RVALID.poke(true.B)
      clock.step()
      io.axi.R.RVALID.poke(false.B)

      mem.write(line, 0x44444413L)
      io.dma_snoop_start.poke(line.U)
      io.dma_snoop_end.poke((line + lineSize - 1L).U)
      io.dma_snoop_valid.poke(true.B)
      // 旧 refill 未排空前 DMA B 必须等待。
      bool(io.dma_snoop_ready) should be(false)
      for (beat <- 1 until beats) {
        io.axi.R.RDATA.poke(0xdeadbeefL.U)
        io.axi.R.RRESP.poke(0.U)
        io.axi.R.RLAST.poke((beat + 1 == beats).B)
        io.axi.R.RVALID.poke(true.B)
        clock.step()
      }
      io.axi.R.RVALID.poke(false.B)
      io.axi.R.RLAST.poke(false.B)
      waitUntil(clock, bool(io.dma_snoop_ready), "in-flight snoop 未完成")
      clock.step()
      io.dma_snoop_valid.poke(false.B)

      // 自动重取必须读到新指令。
      refill(io, clock, line, mem, s"$kind/${lineSize}B replay")
      expectResponse(io, clock, 0x44444413L, s"$kind/${lineSize}B replay")
    }
  }

  for (kind <- Seq("reg", "sram")) {
    for (blockLog2 <- Seq(3, 4, 5, 6)) {
      val lineSize = 1 << blockLog2
      s"ICache($kind,${lineSize}B)" should "DMA 改写后无需 fence.i 即重新取指" in {
        runHitInvalidate(kind, blockLog2)
      }
      it should "按 burst 范围失效跨行且保留范围外行" in {
        runBurstCrossLine(kind, blockLog2)
      }
      it should "在途 refill 被 snoop 时丢弃旧事务并重取" in {
        runInflight(kind, blockLog2)
      }
    }
  }
}
