package ysyx_26030103.cache

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import _root_.ysyx_26030103.common.ysyx_26030103_PhysicalMemoryMap

import scala.collection.mutable

/** DMA 地址级 snoop 的 DCache 定向测试。
  *
  * 覆盖：
  *   1. CPU 先 load 使行命中，DMA 写同一行后必须 miss 并读到新值； 2. burst 覆盖多行时只失效范围内行，范围外的行继续命中； 3. 在途 refill 已收到旧 R beat 时，DMA snoop
  *      必须丢弃旧响应和旧 安装，排空后按原访问重新读取并返回新值； 4. Reg/SRAM 两种阵列与 RV32/RV64 地址宽度。
  *
  * 这里直接把 [start, end] 当成 DMA burst 的字节范围，和仲裁器输出一致。
  */
class ysyx_26030103_DCacheDmaSnoopTest extends AnyFlatSpec with Matchers {
  private val BlockLog2 = 4 // 16B/line
  private val IndexBits = 2 // 4 组
  private val LineA     = 0xc0000100L
  private val LineB     = 0xc0000110L
  private val LineC     = 0xc0000120L
  private val Pma       = ysyx_26030103_PhysicalMemoryMap.SoC(HasChipLink = true)

  private def bool(value: Bool): Boolean = value.peek().litValue == 1
  private def long(value: UInt): Long    = value.peek().litValue.toLong

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
    xlen:      Int,
    blockLog2: Int = BlockLog2
  ): ysyx_26030103_DCacheBase = {
    val addrW  = if (xlen == 64) 64 else 32
    val params = Seq(
      "reg"  -> (() =>
        new ysyx_26030103_DCacheReg(
          Enable = true,
          BlockSizeLog2 = blockLog2,
          IndexBits = IndexBits,
          AddressWidth = addrW,
          DataWidth = xlen,
          DmaSnoop = true,
          CacheableBase = 0L,
          CacheableMask = 0L,
          PMARegions = Pma
        )
      ),
      "sram" -> (() =>
        new ysyx_26030103_DCacheSRAM(
          Enable = true,
          BlockSizeLog2 = blockLog2,
          IndexBits = IndexBits,
          AddressWidth = addrW,
          DataWidth = xlen,
          DmaSnoop = true,
          CacheableBase = 0L,
          CacheableMask = 0L,
          PMARegions = Pma
        )
      )
    ).toMap
    params(kind)()
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
    io.req.bits.addr.poke(0.U)
    io.req.bits.vaddr.poke(0.U)
    io.req.bits.WidthSelect.poke(2.U)
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
    io.AXI.AR.ARREADY.poke(false.B)
    io.AXI.R.RVALID.poke(false.B)
    io.AXI.R.RDATA.poke(0.U)
    io.AXI.R.RRESP.poke(0.U)
    io.AXI.R.RLAST.poke(false.B)
    io.flush.poke(false.B)
    io.dma_snoop_valid.poke(false.B)
    io.dma_snoop_start.poke(0.U)
    io.dma_snoop_end.poke(0.U)
  }

  private def issueLoad(
    io:    ysyx_26030103_DCacheInterface,
    clock: Clock,
    addr:  Long
  ): Unit = {
    io.req.bits.addr.poke(addr.U)
    io.req.bits.vaddr.poke(addr.U)
    io.req.bits.WidthSelect.poke(2.U)
    io.req_paddr_valid.poke(true.B)
    io.req_paddr_vaddr.poke(addr.U)
    io.req.valid.poke(true.B)
    waitUntil(clock, bool(io.req.ready), s"DCache 未受理 0x${addr.toHexString}")
    clock.step()
    io.req.valid.poke(false.B)
  }

  private def acceptAr(
    io:    ysyx_26030103_DCacheInterface,
    clock: Clock
  ): Int = {
    waitUntil(clock, bool(io.AXI.AR.ARVALID), "DCache 没有 AR")
    val beats = long(io.AXI.AR.ARLEN).toInt + 1
    io.AXI.AR.ARREADY.poke(true.B)
    clock.step()
    io.AXI.AR.ARREADY.poke(false.B)
    beats
  }

  private def feedBeat(
    io:    ysyx_26030103_DCacheInterface,
    clock: Clock,
    data:  Long,
    last:  Boolean
  ): Unit = {
    io.AXI.R.RDATA.poke(data.U)
    io.AXI.R.RRESP.poke(0.U)
    io.AXI.R.RLAST.poke(last.B)
    io.AXI.R.RVALID.poke(true.B)
    clock.step()
    io.AXI.R.RVALID.poke(false.B)
    io.AXI.R.RLAST.poke(false.B)
  }

  private def fillLine(
    io:    ysyx_26030103_DCacheInterface,
    clock: Clock,
    addr:  Long,
    base:  Long,
    label: String
  ): Unit = {
    issueLoad(io, clock, addr)
    val beats = acceptAr(io, clock)
    for (beat <- 0 until beats) {
      feedBeat(io, clock, base + beat.toLong, beat + 1 == beats)
    }
    expectResponse(io, clock, base, label)
  }

  private def expectResponse(
    io:    ysyx_26030103_DCacheInterface,
    clock: Clock,
    data:  Long,
    label: String
  ): Unit = {
    waitUntil(clock, bool(io.resp.valid), s"$label 没有响应")
    withClue(s"$label 数据错误") { long(io.resp.bits.data) should be(data) }
    bool(io.resp.bits.fault) should be(false)
    io.resp.ready.poke(true.B)
    clock.step()
    io.resp.ready.poke(false.B)
  }

  private def expectHit(
    io:    ysyx_26030103_DCacheInterface,
    clock: Clock,
    addr:  Long,
    data:  Long,
    label: String
  ): Unit = {
    issueLoad(io, clock, addr)
    for (_ <- 0 until 3) {
      bool(io.AXI.AR.ARVALID) should be(false)
      clock.step()
    }
    expectResponse(io, clock, data, label)
  }

  private def pulseSnoop(
    io:    ysyx_26030103_DCacheInterface,
    clock: Clock,
    lo:    Long,
    hi:    Long
  ): Unit = {
    io.dma_snoop_start.poke(lo.U)
    io.dma_snoop_end.poke(hi.U)
    io.dma_snoop_valid.poke(true.B)
    waitUntil(clock, bool(io.dma_snoop_ready), "DMA snoop 未 ready")
    clock.step()
    io.dma_snoop_valid.poke(false.B)
  }

  private def runHitInvalidate(kind: String, xlen: Int): Unit = {
    simulate(create(kind, xlen)) { dut =>
      val io    = dut.io
      val clock = dut.clock
      resetDut(io, clock, dut.reset)

      fillLine(io, clock, LineA, 0x11110000L, s"$kind RV$xlen 填充 A")
      fillLine(io, clock, LineB, 0x22220000L, s"$kind RV$xlen 填充 B")

      // DMA 只写 A 所在行；B 必须继续命中。
      pulseSnoop(io, clock, LineA, LineA + 3L)
      expectHit(io, clock, LineB, 0x22220000L, s"$kind RV$xlen B 不应失效")
      issueLoad(io, clock, LineA)
      acceptAr(io, clock) should be(1 << (BlockLog2 - 2))
      for (beat <- 0 until (1 << (BlockLog2 - 2))) {
        feedBeat(io, clock, 0x33330000L + beat.toLong, beat + 1 == (1 << (BlockLog2 - 2)))
      }
      expectResponse(io, clock, 0x33330000L, s"$kind RV$xlen A 失效后")
    }
  }

  private def runBurstCrossLine(kind: String, xlen: Int): Unit = {
    simulate(create(kind, xlen)) { dut =>
      val io    = dut.io
      val clock = dut.clock
      resetDut(io, clock, dut.reset)

      fillLine(io, clock, LineA, 0x44440000L, s"$kind RV$xlen burst 填充 A")
      fillLine(io, clock, LineB, 0x55550000L, s"$kind RV$xlen burst 填充 B")
      fillLine(io, clock, LineC, 0x66660000L, s"$kind RV$xlen burst 填充 C")

      // 一次跨 A/B 的 burst 范围；C 在范围外必须保留。
      pulseSnoop(io, clock, LineA + 4L, LineB + 11L)
      expectHit(io, clock, LineC, 0x66660000L, s"$kind RV$xlen C 保留")
      for ((line, word) <- Seq(LineA -> 0x77770000L, LineB -> 0x88880000L)) {
        issueLoad(io, clock, line)
        acceptAr(io, clock) should be(1 << (BlockLog2 - 2))
        for (beat <- 0 until (1 << (BlockLog2 - 2))) {
          feedBeat(io, clock, word + beat.toLong, beat + 1 == (1 << (BlockLog2 - 2)))
        }
        expectResponse(io, clock, word, s"$kind RV$xlen burst 失效")
      }
    }
  }

  private def runInflightReplay(kind: String, xlen: Int): Unit = {
    simulate(create(kind, xlen)) { dut =>
      val io    = dut.io
      val clock = dut.clock
      resetDut(io, clock, dut.reset)

      fillLine(io, clock, LineA, 0x99990000L, s"$kind RV$xlen in-flight 填充")
      pulseSnoop(io, clock, LineA, LineA + 3L)

      issueLoad(io, clock, LineA)
      val beats = acceptAr(io, clock)
      // 第一拍是关键词：旧数据可能已经形成响应，必须被随后的 snoop 撤回。
      feedBeat(io, clock, 0xaaaa0000L, beats == 1)

      io.dma_snoop_start.poke(LineA.U)
      io.dma_snoop_end.poke((LineA + 3L).U)
      io.dma_snoop_valid.poke(true.B)
      // 在途 refill 未排空前不能放行 DMA B。
      bool(io.dma_snoop_ready) should be(false)
      for (beat <- 1 until beats) {
        feedBeat(io, clock, 0xdead0000L + beat.toLong, beat + 1 == beats)
      }
      // 排空后自动重取；此时才允许 DMA B 完成。
      waitUntil(clock, bool(io.dma_snoop_ready), "in-flight snoop 未完成")
      clock.step()
      io.dma_snoop_valid.poke(false.B)

      val replayBeats = acceptAr(io, clock)
      replayBeats should be(beats)
      for (beat <- 0 until replayBeats) {
        feedBeat(io, clock, 0xbbbb0000L + beat.toLong, beat + 1 == replayBeats)
      }
      expectResponse(io, clock, 0xbbbb0000L, s"$kind RV$xlen replay")
    }
  }

  private def runLineSizes(kind: String, blockLog2: Int): Unit = {
    simulate(create(kind, 32, blockLog2)) { dut =>
      val io       = dut.io
      val clock    = dut.clock
      resetDut(io, clock, dut.reset)
      val lineSize = 1L << blockLog2
      val a        = 0xc0001000L
      val b        = a + 2L * lineSize
      val c        = a + 4L * lineSize
      fillLine(io, clock, a, 0x11110000L, s"$kind ${lineSize}B 填充 A")
      fillLine(io, clock, b, 0x22220000L, s"$kind ${lineSize}B 填充 B")
      fillLine(io, clock, c, 0x33330000L, s"$kind ${lineSize}B 填充 C")
      // 范围同时覆盖 A/B，但结束点故意落在 B 行内而不是行边界。
      pulseSnoop(io, clock, a + 1L, b + lineSize / 2L)
      expectHit(io, clock, c, 0x33330000L, s"$kind ${lineSize}B C 保留")
      for ((line, word) <- Seq(a -> 0x44440000L, b -> 0x55550000L)) {
        issueLoad(io, clock, line)
        acceptAr(io, clock) should be(1 << (blockLog2 - 2))
        for (beat <- 0 until (1 << (blockLog2 - 2))) {
          feedBeat(io, clock, word + beat.toLong, beat + 1 == (1 << (blockLog2 - 2)))
        }
        expectResponse(io, clock, word, s"$kind ${lineSize}B 失效")
      }
    }
  }

  for {
    kind <- Seq("reg", "sram")
    xlen <- Seq(32, 64)
  } {
    s"DCache($kind,RV$xlen)" should "DMA 写命中行后 CPU 必须看到新值" in {
      runHitInvalidate(kind, xlen)
    }
    it should "按 burst 字节范围失效跨行且保留范围外行" in {
      runBurstCrossLine(kind, xlen)
    }
    it should "在途 refill 被 snoop 时丢弃旧响应并重取" in {
      runInflightReplay(kind, xlen)
    }
  }

  for {
    kind      <- Seq("reg", "sram")
    blockLog2 <- Seq(3, 5, 6)
  } {
    val lineSize = 1 << blockLog2
    s"DCache($kind,${lineSize}B)" should "支持 8B/32B/64B line 的跨行 burst 失效" in {
      runLineSizes(kind, blockLog2)
    }
  }
}
