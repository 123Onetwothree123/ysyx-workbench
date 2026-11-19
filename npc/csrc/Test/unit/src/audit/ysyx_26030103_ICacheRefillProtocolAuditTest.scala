package ysyx_26030103.audit

import _root_.ysyx_26030103.cache._

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
  private val AddressW     = 32
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
      DMA:      Boolean = false
  ): ysyx_26030103_ICacheBase =
    kind match {
      case "reg" =>
        new ysyx_26030103_ICacheReg(
          Enable = true,
          BlockSizeLog2 = BlockLog2,
          IndexBits = IndexBits,
          AddressWidth = AddressW,
          DataWidth = 32,
          VIPT = viptScan,
          VIPTAliasSafe = viptScan,
          VIPTAliasScanMode =
            if (viptScan) ysyx_26030103_VIPTAliasScan.Serial
            else ysyx_26030103_VIPTAliasScan.AllSets,
          VIPTAliasScanWidth = 1,
          Snoop = snoop,
          DMASnoop = DMA,
          PMARegions = Pma
        )
      case "sram" =>
        new ysyx_26030103_ICacheSRAM(
          Enable = true,
          BlockSizeLog2 = BlockLog2,
          IndexBits = IndexBits,
          AddressWidth = AddressW,
          DataWidth = 32,
          VIPT = viptScan,
          VIPTAliasSafe = viptScan,
          VIPTAliasScanMode =
            if (viptScan) ysyx_26030103_VIPTAliasScan.Serial
            else ysyx_26030103_VIPTAliasScan.AllSets,
          VIPTAliasScanWidth = 1,
          Snoop = snoop,
          DMASnoop = DMA,
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
    io.FetchAddress.poke(0.U(AddressW.W))
    io.FetchVaddr.poke(0.U(AddressW.W))
    io.FetchPaddrValid.poke(true.B)
    io.FetchPaddrVaddr.poke(0.U(AddressW.W))
    io.FetchValid.poke(false.B)
    io.ResponseReady.poke(false.B)
    io.AXI.AR.ARREADY.poke(false.B)
    io.AXI.R.RDATA.poke(0.U(32.W))
    io.AXI.R.RRESP.poke(0.U(2.W))
    io.AXI.R.RLAST.poke(false.B)
    io.AXI.R.RVALID.poke(false.B)
    io.flush.poke(false.B)
    io.kill.poke(false.B)
    io.SnoopValid.poke(false.B)
    io.SnoopAddress.poke(0.U(AddressW.W))
    io.SnoopAddressHi.poke(0.U(AddressW.W))
    io.DMASnoopValid.poke(false.B)
    io.DMASnoopStart.poke(0.U(AddressW.W))
    io.DMASnoopEnd.poke(0.U(AddressW.W))
  }

  private def issueFetch(
      io:    ysyx_26030103_ICacheInterface,
      clock: Clock,
      addr:  Long
  ): Unit = {
    io.FetchAddress.poke(addr.U(AddressW.W))
    io.FetchVaddr.poke(addr.U(AddressW.W))
    io.FetchPaddrValid.poke(true.B)
    io.FetchPaddrVaddr.poke(addr.U(AddressW.W))
    io.FetchValid.poke(true.B)
    waitUntil(clock, bool(io.FetchReady), s"ICache 未受理取指 0x${addr.toHexString}")
    clock.step()
    io.FetchValid.poke(false.B)
  }

  private def acceptAr(
      io:    ysyx_26030103_ICacheInterface,
      clock: Clock,
      line:  Long
  ): Int = {
    waitUntil(clock, bool(io.AXI.AR.ARVALID), "ICache 没有 refill AR")
    withClue("refill AR 地址错误") { long(io.AXI.AR.ARADDR) should be(line) }
    val beats = long(io.AXI.AR.ARLEN).toInt + 1
    io.AXI.AR.ARREADY.poke(true.B)
    clock.step()
    io.AXI.AR.ARREADY.poke(false.B)
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
      io.AXI.R.RDATA.poke(mem.read(line + beat.toLong * 4L).U(32.W))
      io.AXI.R.RRESP.poke(0.U(2.W))
      io.AXI.R.RLAST.poke((beat + 1 == beats).B)
      io.AXI.R.RVALID.poke(true.B)
      clock.step()
    }
    io.AXI.R.RVALID.poke(false.B)
    io.AXI.R.RLAST.poke(false.B)
  }

  private def expectResponse(
      io:     ysyx_26030103_ICacheInterface,
      clock:  Clock,
      data:   Long,
      fault:  Boolean,
      label:  String
  ): Unit = {
    waitUntil(clock, bool(io.ResponseValid), s"$label 没有取指响应")
    withClue(s"$label 取指数据错误") { long(io.ResponseData) should be(data) }
    withClue(s"$label 故障标记错误") { bool(io.ResponseFault) should be(fault) }
    io.ResponseReady.poke(true.B)
    clock.step()
    io.ResponseReady.poke(false.B)
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
      bool(io.FetchReady) should be(false)
      io.flush.poke(true.B)
      clock.step()
      io.flush.poke(false.B)

      // 若 flush 没有清掉 deferred，扫描窗口结束后旧行会被补装。
      mem.write(Line, NewData)
      clock.step(30)
      issueFetch(io, clock, Line)
      var cycles = 0
      while (!bool(io.AXI.AR.ARVALID) && cycles < 100) {
        clock.step()
        cycles += 1
      }
      withClue(s"$kind flush 后仍命中扫描窗口中的旧行") {
        bool(io.AXI.AR.ARVALID) should be(true)
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
        io.AXI.R.RDATA.poke(mem.read(Line + beat.toLong * 4L).U(32.W))
        io.AXI.R.RRESP.poke(0.U(2.W))
        io.AXI.R.RLAST.poke(false.B)
        io.AXI.R.RVALID.poke(true.B)
        clock.step()
      }
      io.AXI.R.RVALID.poke(false.B)
      expectResponse(io, clock, Nop, fault = true, s"$kind 缺 RLAST 响应")

      // 迟到的 RLAST 用于排空坏事务，不能被当作新 refill 的数据。
      io.AXI.R.RDATA.poke(0xdeadbeefL.U(32.W))
      io.AXI.R.RRESP.poke(0.U(2.W))
      io.AXI.R.RLAST.poke(true.B)
      io.AXI.R.RVALID.poke(true.B)
      clock.step()
      io.AXI.R.RVALID.poke(false.B)
      io.AXI.R.RLAST.poke(false.B)

      // 错误行绝不能安装；store 后的新指令必须重新从内存取。
      mem.write(demand, NewData)
      issueFetch(io, clock, demand)
      var cycles = 0
      while (!bool(io.AXI.AR.ARVALID) && cycles < 100) {
        clock.step()
        cycles += 1
      }
      withClue(s"$kind 缺 RLAST 的坏行被错误安装") {
        bool(io.AXI.AR.ARVALID) should be(true)
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
        io.AXI.R.RDATA.poke(mem.read(Line + beat.toLong * 4L).U(32.W))
        io.AXI.R.RRESP.poke(if (beat + 1 == beats) 2.U else 0.U)
        io.AXI.R.RLAST.poke((beat + 1 == beats).B)
        io.AXI.R.RVALID.poke(true.B)
        clock.step()
      }
      io.AXI.R.RVALID.poke(false.B)
      io.AXI.R.RLAST.poke(false.B)

      // 关键词在 beat 0，early restart 已经交付；后续错误只要求整行不安装。
      expectResponse(io, clock, OldData, fault = false, s"$kind 末拍错误响应")

      mem.write(Line, NewData)
      issueFetch(io, clock, Line)
      var cycles = 0
      while (!bool(io.AXI.AR.ARVALID) && cycles < 100) {
        clock.step()
        cycles += 1
      }
      withClue(s"$kind 末拍 RRESP 错误后坏行仍被安装") {
        bool(io.AXI.AR.ARVALID) should be(true)
      }
      acceptAr(io, clock, Line)
      sendBeats(io, clock, mem, Line, beats)
      expectResponse(io, clock, NewData, fault = false, s"$kind 末拍错误后重取")
    }
  }

  /** DMA 范围 snoop 恰好落在 serial 扫描最后一拍（deferred 补装同拍）。 */
  private def runDMADeferredInstall(kind: String): Unit = {
    simulate(create(kind, viptScan = true, snoop = true, DMA = true)) { dut =>
      val io    = dut.io
      val clock = dut.clock
      val d     = new Driver(clock)
      resetDut(io, clock, dut.reset)
      val mem   = new Memory
      val chunks = (1 << IndexBits) / 1

      io.FetchAddress.poke(Line.U(AddressW.W))
      io.FetchVaddr.poke(Line.U(AddressW.W))
      io.FetchPaddrValid.poke(true.B)
      io.FetchPaddrVaddr.poke(Line.U(AddressW.W))
      io.FetchValid.poke(true.B)
      var cycles = 0
      while (!bool(io.FetchReady) && cycles < 100) {
        d.step()
        cycles += 1
      }
      bool(io.FetchReady) should be(true)
      d.step()
      io.FetchValid.poke(false.B)

      cycles = 0
      while (!bool(io.AXI.AR.ARVALID) && cycles < 100) {
        d.step()
        cycles += 1
      }
      bool(io.AXI.AR.ARVALID) should be(true)
      long(io.AXI.AR.ARADDR) should be(Line)
      val beats = long(io.AXI.AR.ARLEN).toInt + 1
      beats should be(4)
      io.AXI.AR.ARREADY.poke(true.B)
      d.step()
      io.AXI.AR.ARREADY.poke(false.B)
      val triggerElapsed = d.elapsed

      for (beat <- 0 until beats) {
        io.AXI.R.RDATA.poke(mem.read(Line + beat.toLong * 4L).U(32.W))
        io.AXI.R.RRESP.poke(0.U(2.W))
        io.AXI.R.RLAST.poke((beat + 1 == beats).B)
        io.AXI.R.RVALID.poke(true.B)
        d.step()
      }
      io.AXI.R.RVALID.poke(false.B)
      io.AXI.R.RLAST.poke(false.B)

      cycles = 0
      while (!bool(io.ResponseValid) && cycles < 100) {
        d.step()
        cycles += 1
      }
      bool(io.ResponseValid) should be(true)
      long(io.ResponseData) should be(OldData)
      io.ResponseReady.poke(true.B)
      d.step()
      io.ResponseReady.poke(false.B)

      var wait = 0
      while (d.elapsed < triggerElapsed + chunks - 1 && wait < 100) {
        d.step()
        wait += 1
      }
      d.elapsed should be(triggerElapsed + chunks - 1)

      mem.write(Line, NewData)
      io.DMASnoopStart.poke(Line.U(AddressW.W))
      io.DMASnoopEnd.poke((Line + 15L).U(AddressW.W))
      io.DMASnoopValid.poke(true.B)
      bool(io.DMASnoopReady) should be(true)
      d.step()
      io.DMASnoopValid.poke(false.B)

      // 补装若未被同拍 DMA 压掉，这里会直接命中旧行而不发 AR。
      io.FetchAddress.poke(Line.U(AddressW.W))
      io.FetchVaddr.poke(Line.U(AddressW.W))
      io.FetchPaddrValid.poke(true.B)
      io.FetchPaddrVaddr.poke(Line.U(AddressW.W))
      io.FetchValid.poke(true.B)
      cycles = 0
      while (!bool(io.FetchReady) && cycles < 100) {
        d.step()
        cycles += 1
      }
      bool(io.FetchReady) should be(true)
      d.step()
      io.FetchValid.poke(false.B)

      cycles = 0
      while (!bool(io.AXI.AR.ARVALID) && !bool(io.ResponseValid) &&
          cycles < 100) {
        d.step()
        cycles += 1
      }
      withClue(s"$kind DMA 与 deferred 补装同拍后仍命中旧行") {
        bool(io.ResponseValid) should be(false)
      }
      bool(io.AXI.AR.ARVALID) should be(true)
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
      waitUntil(clock, bool(io.ResponseValid), s"$kind 命中响应未出现")
      long(io.ResponseData) should be(OldData)
      mem.write(Line, NewData)
      io.SnoopAddress.poke(Line.U(AddressW.W))
      io.SnoopAddressHi.poke((Line + 3L).U(AddressW.W))
      io.SnoopValid.poke(true.B)
      clock.step()
      io.SnoopValid.poke(false.B)

      // snoop 之后要么重新填充，要么直接给出新数据；绝不能交付旧指令。
      var cycles = 0
      while (!bool(io.ResponseValid) && cycles < 100) {
        clock.step()
        cycles += 1
      }
      waitUntil(clock, bool(io.AXI.AR.ARVALID) || bool(io.ResponseValid),
        s"$kind snoop 后既不重取也无响应")
      if (bool(io.AXI.AR.ARVALID)) {
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
      runDMADeferredInstall(kind)
    }
  }
}
