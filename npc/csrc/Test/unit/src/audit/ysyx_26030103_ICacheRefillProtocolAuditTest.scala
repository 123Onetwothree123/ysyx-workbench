package ysyx_26030103.cache

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import _root_.ysyx_26030103.common.ysyx_26030103_PMARegion

import scala.collection.mutable

/** 已有 ICache 测试未覆盖的 refill 协议/冲刷组合。
  *
  * 重点验证：
  *   1. flush 落在 serial 扫描的 deferred 窗口时，扫描结束后不得补装；
  *   2. 从设备在 ARLEN 最后一拍漏掉 RLAST 时，当前取指必须明确报错，
  *      该行不得安装，迟到 RLAST 排空后仍能重新取指；
  *   3. 最后一个 beat 的 RRESP 错误不得把整行安装成 valid；
  *   4. 响应已对外可见但尚未握手时 store snoop 到达，旧数据不能被交付。
  */
class ysyx_26030103_ICacheRefillProtocolAuditTest
    extends AnyFlatSpec
    with Matchers {
  private val AddrW     = 32
  private val BlockLog2 = 4 // 16B/line，4 拍
  private val IndexBits = 4 // 16 组，serial 扫描足够长
  private val Line      = 0x80001000L
  private val OldData   = 0x11111113L
  private val NewData   = 0x22222213L
  private val Nop       = 0x00000013L

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
    def read(address: Long): Long =
      words.getOrElse(
        address & ~3L,
        if ((address & ~3L) == Line) OldData
        else ((address & ~3L) ^ 0x24680000L) & 0xffffffffL
      )
    def write(address: Long, data: Long): Unit =
      words(address & ~3L) = data & 0xffffffffL
  }

  private def waitUntil(clock: Clock, cond: => Boolean, message: String): Unit = {
    var cycles = 0
    while (!cond && cycles < 500) {
      clock.step()
      cycles += 1
    }
    withClue(s"$message（等待 $cycles 拍）") { cond should be(true) }
  }

  private class Driver(val clock: Clock) {
    var elapsed: Int = 0
    def step(): Unit = {
      clock.step()
      elapsed += 1
    }
  }

  private def create(
      kind:     String,
      viptScan: Boolean,
      snoop:    Boolean,
      dma:      Boolean = false
  ): ysyx_26030103_ICacheBase =
    kind match {
      case "reg" =>
        new ysyx_26030103_ICacheReg(
          Enable = true,
          BlockSizeLog2 = BlockLog2,
          IndexBits = IndexBits,
          AddressWidth = AddrW,
          Vipt = viptScan,
          ViptAliasSafe = viptScan,
          ViptAliasScanMode =
            if (viptScan) ysyx_26030103_ViptAliasScan.Serial
            else ysyx_26030103_ViptAliasScan.AllSets,
          ViptAliasScanWidth = 1,
          Snoop = snoop,
          DmaSnoop = dma,
          PMARegions = Pma
        )
      case "sram" =>
        new ysyx_26030103_ICacheSRAM(
          Enable = true,
          BlockSizeLog2 = BlockLog2,
          IndexBits = IndexBits,
          AddressWidth = AddrW,
          Vipt = viptScan,
          ViptAliasSafe = viptScan,
          ViptAliasScanMode =
            if (viptScan) ysyx_26030103_ViptAliasScan.Serial
            else ysyx_26030103_ViptAliasScan.AllSets,
          ViptAliasScanWidth = 1,
          Snoop = snoop,
          DmaSnoop = dma,
          PMARegions = Pma
        )
      case other => throw new IllegalArgumentException(other)
    }

  private def resetDut(
      io:    ysyx_26030103_ICacheInterface,
      clock: Clock,
      reset: Reset
  ): Unit = {
    reset.poke(true.B)
    clock.step(2)
    reset.poke(false.B)
    io.fetch_addr.poke(0.U(AddrW.W))
    io.fetch_vaddr.poke(0.U(AddrW.W))
    io.fetch_paddr_valid.poke(true.B)
    io.fetch_paddr_vaddr.poke(0.U(AddrW.W))
    io.fetch_valid.poke(false.B)
    io.resp_ready.poke(false.B)
    io.axi.AR.ARREADY.poke(false.B)
    io.axi.R.RDATA.poke(0.U(32.W))
    io.axi.R.RRESP.poke(0.U(2.W))
    io.axi.R.RLAST.poke(false.B)
    io.axi.R.RVALID.poke(false.B)
    io.flush.poke(false.B)
    io.kill.poke(false.B)
    io.snoop_valid.poke(false.B)
    io.snoop_addr.poke(0.U(AddrW.W))
    io.snoop_addr_hi.poke(0.U(AddrW.W))
    io.dma_snoop_valid.poke(false.B)
    io.dma_snoop_start.poke(0.U(AddrW.W))
    io.dma_snoop_end.poke(0.U(AddrW.W))
  }

  private def issueFetch(
      io:    ysyx_26030103_ICacheInterface,
      clock: Clock,
      addr:  Long
  ): Unit = {
    io.fetch_addr.poke(addr.U(AddrW.W))
    io.fetch_vaddr.poke(addr.U(AddrW.W))
    io.fetch_paddr_valid.poke(true.B)
    io.fetch_paddr_vaddr.poke(addr.U(AddrW.W))
    io.fetch_valid.poke(true.B)
    waitUntil(clock, bool(io.fetch_ready), s"ICache 未受理取指 0x${addr.toHexString}")
    clock.step()
    io.fetch_valid.poke(false.B)
  }

  private def acceptAr(
      io:    ysyx_26030103_ICacheInterface,
      clock: Clock,
      line:  Long
  ): Int = {
    waitUntil(clock, bool(io.axi.AR.ARVALID), "ICache 没有 refill AR")
    withClue("refill AR 地址错误") { long(io.axi.AR.ARADDR) should be(line) }
    val beats = long(io.axi.AR.ARLEN).toInt + 1
    io.axi.AR.ARREADY.poke(true.B)
    clock.step()
    io.axi.AR.ARREADY.poke(false.B)
    beats
  }

  private def sendBeats(
      io:    ysyx_26030103_ICacheInterface,
      clock: Clock,
      mem:   Memory,
      line:  Long,
      beats: Int
  ): Unit = {
    for (beat <- 0 until beats) {
      io.axi.R.RDATA.poke(mem.read(line + beat.toLong * 4L).U(32.W))
      io.axi.R.RRESP.poke(0.U(2.W))
      io.axi.R.RLAST.poke((beat + 1 == beats).B)
      io.axi.R.RVALID.poke(true.B)
      clock.step()
    }
    io.axi.R.RVALID.poke(false.B)
    io.axi.R.RLAST.poke(false.B)
  }

  private def expectResponse(
      io:     ysyx_26030103_ICacheInterface,
      clock:  Clock,
      data:   Long,
      fault:  Boolean,
      label:  String
  ): Unit = {
    waitUntil(clock, bool(io.resp_valid), s"$label 没有取指响应")
    withClue(s"$label 取指数据错误") { long(io.resp_data) should be(data) }
    withClue(s"$label 故障标记错误") { bool(io.resp_fault) should be(fault) }
    io.resp_ready.poke(true.B)
    clock.step()
    io.resp_ready.poke(false.B)
  }

  private def runFlushDeferred(kind: String): Unit = {
    simulate(create(kind, viptScan = true, snoop = true)) { dut =>
      val io    = dut.io
      val clock = dut.clock
      resetDut(io, clock, dut.reset)
      val mem = new Memory

      issueFetch(io, clock, Line)
      val beats = acceptAr(io, clock, Line)
      withClue("16B line 的 burst 拍数错误") { beats should be(4) }
      sendBeats(io, clock, mem, Line, beats)
      expectResponse(io, clock, OldData, fault = false, s"$kind flush 前响应")

      // 16 组 serial 扫描远长于 4 拍 refill，此时新行只能挂在 deferred。
      bool(io.fetch_ready) should be(false)
      io.flush.poke(true.B)
      clock.step()
      io.flush.poke(false.B)

      // 若 flush 没有清掉 deferred，扫描窗口结束后旧行会被补装。
      mem.write(Line, NewData)
      clock.step(30)
      issueFetch(io, clock, Line)
      var cycles = 0
      while (!bool(io.axi.AR.ARVALID) && cycles < 100) {
        clock.step()
        cycles += 1
      }
      withClue(s"$kind flush 后仍命中扫描窗口中的旧行") {
        bool(io.axi.AR.ARVALID) should be(true)
      }
      acceptAr(io, clock, Line)
      sendBeats(io, clock, mem, Line, beats)
      expectResponse(io, clock, NewData, fault = false, s"$kind flush 后重取")
    }
  }

  private def runMissingRlast(kind: String): Unit = {
    simulate(create(kind, viptScan = false, snoop = false)) { dut =>
      val io    = dut.io
      val clock = dut.clock
      resetDut(io, clock, dut.reset)
      val mem = new Memory
      val demand = Line + 12L // 关键词是最后一个 beat

      issueFetch(io, clock, demand)
      val beats = acceptAr(io, clock, Line)
      withClue("16B line 的 burst 拍数错误") { beats should be(4) }

      // 最后一拍漏掉 RLAST：AXI 事务不完整，当前取指必须明确报错。
      for (beat <- 0 until beats) {
        io.axi.R.RDATA.poke(mem.read(Line + beat.toLong * 4L).U(32.W))
        io.axi.R.RRESP.poke(0.U(2.W))
        io.axi.R.RLAST.poke(false.B)
        io.axi.R.RVALID.poke(true.B)
        clock.step()
      }
      io.axi.R.RVALID.poke(false.B)
      expectResponse(io, clock, Nop, fault = true, s"$kind 缺 RLAST 响应")

      // 迟到的 RLAST 用于排空坏事务，不能被当作新 refill 的数据。
      io.axi.R.RDATA.poke(0xdeadbeefL.U(32.W))
      io.axi.R.RRESP.poke(0.U(2.W))
      io.axi.R.RLAST.poke(true.B)
      io.axi.R.RVALID.poke(true.B)
      clock.step()
      io.axi.R.RVALID.poke(false.B)
      io.axi.R.RLAST.poke(false.B)

      // 错误行绝不能安装；store 后的新指令必须重新从内存取。
      mem.write(demand, NewData)
      issueFetch(io, clock, demand)
      var cycles = 0
      while (!bool(io.axi.AR.ARVALID) && cycles < 100) {
        clock.step()
        cycles += 1
      }
      withClue(s"$kind 缺 RLAST 的坏行被错误安装") {
        bool(io.axi.AR.ARVALID) should be(true)
      }
      acceptAr(io, clock, Line)
      sendBeats(io, clock, mem, Line, beats)
      expectResponse(io, clock, NewData, fault = false, s"$kind 缺 RLAST 后重取")
    }
  }

  private def runFinalBeatError(kind: String): Unit = {
    simulate(create(kind, viptScan = false, snoop = false)) { dut =>
      val io    = dut.io
      val clock = dut.clock
      resetDut(io, clock, dut.reset)
      val mem = new Memory

      issueFetch(io, clock, Line)
      val beats = acceptAr(io, clock, Line)
      withClue("16B line 的 burst 拍数错误") { beats should be(4) }

      for (beat <- 0 until beats) {
        io.axi.R.RDATA.poke(mem.read(Line + beat.toLong * 4L).U(32.W))
        io.axi.R.RRESP.poke(if (beat + 1 == beats) 2.U else 0.U)
        io.axi.R.RLAST.poke((beat + 1 == beats).B)
        io.axi.R.RVALID.poke(true.B)
        clock.step()
      }
      io.axi.R.RVALID.poke(false.B)
      io.axi.R.RLAST.poke(false.B)

      // 关键词在 beat 0，early restart 已经交付；后续错误只要求整行不安装。
      expectResponse(io, clock, OldData, fault = false, s"$kind 末拍错误响应")

      mem.write(Line, NewData)
      issueFetch(io, clock, Line)
      var cycles = 0
      while (!bool(io.axi.AR.ARVALID) && cycles < 100) {
        clock.step()
        cycles += 1
      }
      withClue(s"$kind 末拍 RRESP 错误后坏行仍被安装") {
        bool(io.axi.AR.ARVALID) should be(true)
      }
      acceptAr(io, clock, Line)
      sendBeats(io, clock, mem, Line, beats)
      expectResponse(io, clock, NewData, fault = false, s"$kind 末拍错误后重取")
    }
  }

  /** DMA 范围 snoop 恰好落在 serial 扫描最后一拍（deferred 补装同拍）。 */
  private def runDmaDeferredInstall(kind: String): Unit = {
    simulate(create(kind, viptScan = true, snoop = true, dma = true)) { dut =>
      val io    = dut.io
      val clock = dut.clock
      val d     = new Driver(clock)
      resetDut(io, clock, dut.reset)
      val mem   = new Memory
      val chunks = (1 << IndexBits) / 1

      io.fetch_addr.poke(Line.U(AddrW.W))
      io.fetch_vaddr.poke(Line.U(AddrW.W))
      io.fetch_paddr_valid.poke(true.B)
      io.fetch_paddr_vaddr.poke(Line.U(AddrW.W))
      io.fetch_valid.poke(true.B)
      var cycles = 0
      while (!bool(io.fetch_ready) && cycles < 100) {
        d.step()
        cycles += 1
      }
      bool(io.fetch_ready) should be(true)
      d.step()
      io.fetch_valid.poke(false.B)

      cycles = 0
      while (!bool(io.axi.AR.ARVALID) && cycles < 100) {
        d.step()
        cycles += 1
      }
      bool(io.axi.AR.ARVALID) should be(true)
      long(io.axi.AR.ARADDR) should be(Line)
      val beats = long(io.axi.AR.ARLEN).toInt + 1
      beats should be(4)
      io.axi.AR.ARREADY.poke(true.B)
      d.step()
      io.axi.AR.ARREADY.poke(false.B)
      val triggerElapsed = d.elapsed

      for (beat <- 0 until beats) {
        io.axi.R.RDATA.poke(mem.read(Line + beat.toLong * 4L).U(32.W))
        io.axi.R.RRESP.poke(0.U(2.W))
        io.axi.R.RLAST.poke((beat + 1 == beats).B)
        io.axi.R.RVALID.poke(true.B)
        d.step()
      }
      io.axi.R.RVALID.poke(false.B)
      io.axi.R.RLAST.poke(false.B)

      cycles = 0
      while (!bool(io.resp_valid) && cycles < 100) {
        d.step()
        cycles += 1
      }
      bool(io.resp_valid) should be(true)
      long(io.resp_data) should be(OldData)
      io.resp_ready.poke(true.B)
      d.step()
      io.resp_ready.poke(false.B)

      var wait = 0
      while (d.elapsed < triggerElapsed + chunks - 1 && wait < 100) {
        d.step()
        wait += 1
      }
      d.elapsed should be(triggerElapsed + chunks - 1)

      mem.write(Line, NewData)
      io.dma_snoop_start.poke(Line.U(AddrW.W))
      io.dma_snoop_end.poke((Line + 15L).U(AddrW.W))
      io.dma_snoop_valid.poke(true.B)
      bool(io.dma_snoop_ready) should be(true)
      d.step()
      io.dma_snoop_valid.poke(false.B)

      // 补装若未被同拍 DMA 压掉，这里会直接命中旧行而不发 AR。
      io.fetch_addr.poke(Line.U(AddrW.W))
      io.fetch_vaddr.poke(Line.U(AddrW.W))
      io.fetch_paddr_valid.poke(true.B)
      io.fetch_paddr_vaddr.poke(Line.U(AddrW.W))
      io.fetch_valid.poke(true.B)
      cycles = 0
      while (!bool(io.fetch_ready) && cycles < 100) {
        d.step()
        cycles += 1
      }
      bool(io.fetch_ready) should be(true)
      d.step()
      io.fetch_valid.poke(false.B)

      cycles = 0
      while (!bool(io.axi.AR.ARVALID) && !bool(io.resp_valid) &&
          cycles < 100) {
        d.step()
        cycles += 1
      }
      withClue(s"$kind DMA 与 deferred 补装同拍后仍命中旧行") {
        bool(io.resp_valid) should be(false)
      }
      bool(io.axi.AR.ARVALID) should be(true)
      acceptAr(io, clock, Line)
      sendBeats(io, clock, mem, Line, beats)
      expectResponse(io, clock, NewData, fault = false, s"$kind DMA 后重取")
    }
  }

  private def runSnoopPendingResponse(kind: String): Unit = {
    simulate(create(kind, viptScan = false, snoop = true)) { dut =>
      val io    = dut.io
      val clock = dut.clock
      resetDut(io, clock, dut.reset)
      val mem = new Memory

      issueFetch(io, clock, Line)
      val beats = acceptAr(io, clock, Line)
      sendBeats(io, clock, mem, Line, beats)
      expectResponse(io, clock, OldData, fault = false, s"$kind 首次填充")

      // 命中响应已经可见但未握手；此时 store 完成，旧数据不能交付。
      issueFetch(io, clock, Line)
      waitUntil(clock, bool(io.resp_valid), s"$kind 命中响应未出现")
      long(io.resp_data) should be(OldData)
      mem.write(Line, NewData)
      io.snoop_addr.poke(Line.U(AddrW.W))
      io.snoop_addr_hi.poke((Line + 3L).U(AddrW.W))
      io.snoop_valid.poke(true.B)
      clock.step()
      io.snoop_valid.poke(false.B)

      // snoop 之后要么重新填充，要么直接给出新数据；绝不能交付旧指令。
      var cycles = 0
      while (!bool(io.resp_valid) && cycles < 100) {
        clock.step()
        cycles += 1
      }
      waitUntil(clock, bool(io.axi.AR.ARVALID) || bool(io.resp_valid),
        s"$kind snoop 后既不重取也无响应")
      if (bool(io.axi.AR.ARVALID)) {
        acceptAr(io, clock, Line)
        sendBeats(io, clock, mem, Line, beats)
      }
      expectResponse(io, clock, NewData, fault = false, s"$kind snoop 后响应")
    }
  }

  for (kind <- Seq("reg", "sram")) {
    s"ICache($kind)" should "flush 落在 deferred 扫描窗口后不得补装旧行" in {
      runFlushDeferred(kind)
    }
    it should "ARLEN 最后一拍缺 RLAST 时明确报错且不安装坏行" in {
      runMissingRlast(kind)
    }
    it should "最后一个 beat 的 RRESP 错误不得安装整行" in {
      runFinalBeatError(kind)
    }
    it should "响应未握手时 snoop 不得交付旧指令" in {
      runSnoopPendingResponse(kind)
    }
    it should "DMA snoop 落在 deferred 补装同拍时不得安装旧行" in {
      runDmaDeferredInstall(kind)
    }
  }
}
