package ysyx_26030103.audit

import _root_.ysyx_26030103.cache._

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import _root_.ysyx_26030103.common.ysyx_26030103_PMARegion

import scala.collection.mutable

/** VIPT alias 扫描的 deferred install 与 CPU store snoop 同拍竞争。
  *
  * 扫描模式（serial/parallel_n）下，refill 的 RLAST 早于扫描完成时，新行
  * 先挂在 deferred 状态；扫描最后一拍才真正置 valid。如果 CPU store 的
  * snoop 恰好落在这一拍，store 之后不能再留下旧指令副本：这一拍必须取消
  * 补装（或在同一沿清 valid），下一次取指必须 miss 并重新从内存取到
  * store 后的新指令。
  */
class ysyx_26030103_ICacheDeferredSnoopAuditTest
    extends AnyFlatSpec
    with Matchers {
  private val AddressW     = 32
  private val BlockLog2 = 4 // 16B/line，4 拍 refill
  private val IndexBits = 4 // 16 组，serial 扫描跨越多拍

  private val Line    = 0x80001000L
  private val OldData = 0x11111113L
  private val NewData = 0x22222213L

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

  /** 记录从 AR 握手拍起的时钟沿数，用来精确定位扫描最后一拍。 */
  private class Driver(val clock: Clock) {
    var elapsed: Int = 0
    def step(): Unit = {
      clock.step()
      elapsed += 1
    }
  }

  private def waitUntil(
      d:       Driver,
      cond:    => Boolean,
      message: String
  ): Unit = {
    var cycles = 0
    while (!cond && cycles < 500) {
      d.step()
      cycles += 1
    }
    withClue(s"$message（等待 $cycles 拍）") { cond should be(true) }
  }

  private def create(
      kind:  String,
      mode:  String,
      width: Int
  ): ysyx_26030103_ICacheBase =
    kind match {
      case "reg" =>
        new ysyx_26030103_ICacheReg(
          Enable = true,
          BlockSizeLog2 = BlockLog2,
          IndexBits = IndexBits,
          AddressWidth = AddressW,
          DataWidth = 32,
          VIPT = true,
          VIPTAliasSafe = true,
          VIPTAliasScanMode = mode,
          VIPTAliasScanWidth = width,
          Snoop = true,
          PMARegions = Pma
        )
      case "sram" =>
        new ysyx_26030103_ICacheSRAM(
          Enable = true,
          BlockSizeLog2 = BlockLog2,
          IndexBits = IndexBits,
          AddressWidth = AddressW,
          DataWidth = 32,
          VIPT = true,
          VIPTAliasSafe = true,
          VIPTAliasScanMode = mode,
          VIPTAliasScanWidth = width,
          Snoop = true,
          PMARegions = Pma
        )
      case other => throw new IllegalArgumentException(other)
    }

  private def resetDut(
      io: ysyx_26030103_ICacheInterface,
      d:  Driver,
      reset: Reset
  ): Unit = {
    reset.poke(true.B)
    d.clock.step(2)
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
  }

  private def issueFetch(
      io:   ysyx_26030103_ICacheInterface,
      d:    Driver,
      addr: Long
  ): Unit = {
    io.FetchAddress.poke(addr.U(AddressW.W))
    io.FetchVaddr.poke(addr.U(AddressW.W))
    io.FetchPaddrValid.poke(true.B)
    io.FetchPaddrVaddr.poke(addr.U(AddressW.W))
    io.FetchValid.poke(true.B)
    waitUntil(d, bool(io.FetchReady), s"ICache 未受理取指 0x${addr.toHexString}")
    d.step()
    io.FetchValid.poke(false.B)
  }

  /** 接受 AR，返回 burst 拍数；返回时 elapsed 已计入握手沿。 */
  private def acceptAr(
      io:   ysyx_26030103_ICacheInterface,
      d:    Driver,
      line: Long
  ): Int = {
    waitUntil(d, bool(io.AXI.AR.ARVALID), "ICache 没有 refill AR")
    withClue("refill AR 地址错误") { long(io.AXI.AR.ARADDR) should be(line) }
    val beats = long(io.AXI.AR.ARLEN).toInt + 1
    io.AXI.AR.ARREADY.poke(true.B)
    d.step()
    io.AXI.AR.ARREADY.poke(false.B)
    beats
  }

  private def sendBeats(
      io:    ysyx_26030103_ICacheInterface,
      d:     Driver,
      mem:   Memory,
      line:  Long,
      beats: Int
  ): Unit = {
    for (beat <- 0 until beats) {
      io.AXI.R.RDATA.poke(mem.read(line + beat.toLong * 4L).U(32.W))
      io.AXI.R.RRESP.poke(0.U(2.W))
      io.AXI.R.RLAST.poke((beat + 1 == beats).B)
      io.AXI.R.RVALID.poke(true.B)
      d.step()
    }
    io.AXI.R.RVALID.poke(false.B)
    io.AXI.R.RLAST.poke(false.B)
  }

  private def expectResponse(
      io:    ysyx_26030103_ICacheInterface,
      d:     Driver,
      data:  Long,
      label: String
  ): Unit = {
    waitUntil(d, bool(io.ResponseValid), s"$label 没有取指响应")
    withClue(s"$label 取指数据错误") { long(io.ResponseData) should be(data) }
    bool(io.ResponseFault) should be(false)
    io.ResponseReady.poke(true.B)
    d.step()
    io.ResponseReady.poke(false.B)
  }

  private def runDeferredInstallSnoop(
      kind:  String,
      mode:  String,
      width: Int
  ): Unit = {
    simulate(create(kind, mode, width)) { dut =>
      val io    = dut.io
      val d     = new Driver(dut.clock)
      resetDut(io, d, dut.reset)
      val mem = new Memory

      // 第一次 refill：RLAST 一定早于 16 组 serial/parallel 扫描结束，
      // 新行只能进入 deferred，等待扫描最后一拍补装。
      issueFetch(io, d, Line)
      val beats = acceptAr(io, d, Line)
      val triggerElapsed = d.elapsed
      withClue("16B line 的 burst 拍数错误") { beats should be(4) }
      sendBeats(io, d, mem, Line, beats)
      expectResponse(io, d, OldData, s"$kind/$mode deferred 前关键词响应")

      // AR 握手沿记为 triggerElapsed（握手后 scanChunk=0）。扫描最后一拍
      // 是 triggerElapsed + chunks - 1，该拍结束的时钟沿才执行 deferred 补装。
      val chunks = (1 << IndexBits) / width
      while (d.elapsed < triggerElapsed + chunks - 1) {
        d.step()
      }
      d.elapsed should be(triggerElapsed + chunks - 1)

      // store 数据已落盘并发出同一物理行的 snoop，正好与补装同拍。
      mem.write(Line, NewData)
      io.SnoopAddress.poke(Line.U(AddressW.W))
      io.SnoopAddressHi.poke((Line + 3L).U(AddressW.W))
      io.SnoopValid.poke(true.B)
      d.step()
      io.SnoopValid.poke(false.B)

      // snoop 之后不能再命中旧行。若坏，会立刻从阵列返回 OldData 且不发 AR。
      issueFetch(io, d, Line)
      var cycles = 0
      while (!bool(io.ResponseValid) && !bool(io.AXI.AR.ARVALID) && cycles < 100) {
        d.step()
        cycles += 1
      }
      withClue(
        s"$kind/$mode store snoop 与 deferred install 同拍后仍命中旧行（response=${bool(io.ResponseValid)}, ar=${bool(io.AXI.AR.ARVALID)}）"
      ) {
        bool(io.ResponseValid) should be(false)
      }
      bool(io.AXI.AR.ARVALID) should be(true)

      // 正常 miss 路径必须读到 store 后的新指令。
      acceptAr(io, d, Line)
      sendBeats(io, d, mem, Line, beats)
      expectResponse(io, d, NewData, s"$kind/$mode snoop 后重取")
    }
  }

  /** store snoop 恰好落在 RLAST 拍：旧事务被丢弃，但同拍仍会把新行
    * 挂进 deferred；如果不在同一沿取消这个 deferred 项，扫描结束后仍会
    * 把 store 之前的数据安装成有效行（关键词响应可能已被 early restart
    * 消费，之后不会自动重取）。
    */
  private def runRlastSnoop(kind: String, mode: String, width: Int): Unit = {
    simulate(create(kind, mode, width)) { dut =>
      val io    = dut.io
      val d     = new Driver(dut.clock)
      resetDut(io, d, dut.reset)
      val mem = new Memory

      issueFetch(io, d, Line)
      val beats = acceptAr(io, d, Line)
      val triggerElapsed = d.elapsed
      withClue("16B line 的 burst 拍数错误") { beats should be(4) }

      // 先收完关键词响应（early restart），再在最后一个 beat 上发 snoop，
      // 模拟 store B 与 refill RLAST 同拍完成。
      for (beat <- 0 until beats - 1) {
        io.AXI.R.RDATA.poke(mem.read(Line + beat.toLong * 4L).U(32.W))
        io.AXI.R.RRESP.poke(0.U(2.W))
        io.AXI.R.RLAST.poke(false.B)
        io.AXI.R.RVALID.poke(true.B)
        d.step()
      }
      io.AXI.R.RVALID.poke(false.B)
      expectResponse(io, d, OldData, s"$kind/$mode RLAST snoop 前响应")

      mem.write(Line, NewData)
      io.SnoopAddress.poke(Line.U(AddressW.W))
      io.SnoopAddressHi.poke((Line + 3L).U(AddressW.W))
      io.SnoopValid.poke(true.B)
      io.AXI.R.RDATA.poke(mem.read(Line + (beats - 1).toLong * 4L).U(32.W))
      io.AXI.R.RRESP.poke(0.U(2.W))
      io.AXI.R.RLAST.poke(true.B)
      io.AXI.R.RVALID.poke(true.B)
      d.step()
      io.AXI.R.RVALID.poke(false.B)
      io.AXI.R.RLAST.poke(false.B)
      io.SnoopValid.poke(false.B)

      // 等扫描彻底结束（含 deferred 补装窗口）。
      val chunks = (1 << IndexBits) / width
      while (d.elapsed < triggerElapsed + chunks + 2) {
        d.step()
      }

      issueFetch(io, d, Line)
      var cycles = 0
      while (!bool(io.ResponseValid) && !bool(io.AXI.AR.ARVALID) && cycles < 100) {
        d.step()
        cycles += 1
      }
      withClue(
        s"$kind/$mode store snoop 落在 RLAST 拍后仍命中旧行（response=${bool(io.ResponseValid)}, ar=${bool(io.AXI.AR.ARVALID)}）"
      ) {
        bool(io.ResponseValid) should be(false)
      }
      bool(io.AXI.AR.ARVALID) should be(true)

      acceptAr(io, d, Line)
      sendBeats(io, d, mem, Line, beats)
      expectResponse(io, d, NewData, s"$kind/$mode RLAST snoop 后重取")
    }
  }

  private val Cases = Seq(
    ("serial", 1),
    ("parallel_n", 2)
  )

  for (kind <- Seq("reg", "sram"); (mode, width) <- Cases) {
    s"ICache($kind,$mode)" should "store snoop 落在 deferred install 同拍时不得安装旧指令行" in {
      runDeferredInstallSnoop(kind, mode, width)
    }
    it should "store snoop 落在 RLAST 拍时不得留下 deferred 旧行" in {
      runRlastSnoop(kind, mode, width)
    }
  }
}
