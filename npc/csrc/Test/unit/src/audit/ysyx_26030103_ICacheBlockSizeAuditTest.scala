package ysyx_26030103.cache

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import _root_.ysyx_26030103.common.ysyx_26030103_PMARegion

import scala.collection.mutable

/** ICache 最小合法配置：BlockSizeLog2=2（每行一个 32 位字）。
  *
  * NPCConfig 明确接受 BlockSizeLog2>=2，因此该几何必须能正常 elaboration
  * 并服务单字取指：一次 ARLEN=0 的 refill、同地址命中、相邻字/不同组 miss。
  * 如果实现实际不支持，应在这里以明确异常拒绝，而不是仿真挂死。
  */
class ysyx_26030103_ICacheBlockSizeAuditTest
    extends AnyFlatSpec
    with Matchers {
  private val AddrW = 32
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
    def read(address: Long): Long = words.getOrElse(
      address & ~3L,
      ((address & ~3L) ^ 0x13570000L) & 0xffffffffL
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

  private def create(
      kind:       String,
      indexBits:  Int,
      vipt:       Boolean,
      aliasMode:  String
  ): ysyx_26030103_ICacheBase =
    kind match {
      case "reg" =>
        new ysyx_26030103_ICacheReg(
          Enable = true,
          BlockSizeLog2 = 2,
          IndexBits = indexBits,
          AddressWidth = AddrW,
          Vipt = vipt,
          ViptAliasSafe = vipt,
          ViptAliasScanMode = aliasMode,
          ViptAliasScanWidth = 2,
          PMARegions = Pma
        )
      case "sram" =>
        new ysyx_26030103_ICacheSRAM(
          Enable = true,
          BlockSizeLog2 = 2,
          IndexBits = indexBits,
          AddressWidth = AddrW,
          Vipt = vipt,
          ViptAliasSafe = vipt,
          ViptAliasScanMode = aliasMode,
          ViptAliasScanWidth = 2,
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

  private def fetchMiss(
      io:    ysyx_26030103_ICacheInterface,
      clock: Clock,
      addr:  Long,
      mem:   Memory,
      label: String
  ): Unit = {
    issueFetch(io, clock, addr)
    waitUntil(clock, bool(io.axi.AR.ARVALID), s"$label 没有 refill AR")
    withClue(s"$label AR 地址错误") { long(io.axi.AR.ARADDR) should be(addr) }
    withClue(s"$label 4B line 必须是单拍 ARLEN=0") {
      long(io.axi.AR.ARLEN) should be(0)
    }
    io.axi.AR.ARREADY.poke(true.B)
    clock.step()
    io.axi.AR.ARREADY.poke(false.B)
    io.axi.R.RDATA.poke(mem.read(addr).U(32.W))
    io.axi.R.RRESP.poke(0.U(2.W))
    io.axi.R.RLAST.poke(true.B)
    io.axi.R.RVALID.poke(true.B)
    clock.step()
    io.axi.R.RVALID.poke(false.B)
    io.axi.R.RLAST.poke(false.B)
    expectResponse(io, clock, mem.read(addr), label)
  }

  private def fetchHit(
      io:    ysyx_26030103_ICacheInterface,
      clock: Clock,
      addr:  Long,
      mem:   Memory,
      label: String
  ): Unit = {
    issueFetch(io, clock, addr)
    for (_ <- 0 until 3) {
      bool(io.axi.AR.ARVALID) should be(false)
      clock.step()
    }
    expectResponse(io, clock, mem.read(addr), label)
  }

  private def run(kind: String, indexBits: Int, vipt: Boolean): Unit = {
    simulate(
      create(
        kind,
        indexBits,
        vipt,
        if (vipt) ysyx_26030103_ViptAliasScan.Serial
        else ysyx_26030103_ViptAliasScan.AllSets
      )
    ) { dut =>
      val io    = dut.io
      val clock = dut.clock
      resetDut(io, clock, dut.reset)
      val mem = new Memory

      // 4B line 的首次 refill、命中、相邻字 miss、不同组 miss。
      fetchMiss(io, clock, 0x80000000L, mem, s"$kind/${indexBits}set/$vipt 首次")
      fetchHit(io, clock, 0x80000000L, mem, s"$kind/${indexBits}set/$vipt 同地址命中")
      fetchMiss(io, clock, 0x80000004L, mem, s"$kind/${indexBits}set/$vipt 相邻字")
      fetchMiss(io, clock, 0x80000010L, mem, s"$kind/${indexBits}set/$vipt 不同组")
      fetchHit(io, clock, 0x80000004L, mem, s"$kind/${indexBits}set/$vipt 相邻字命中")

      // fence.i 对所有行有效，之后必须重新取指。
      io.flush.poke(true.B)
      clock.step()
      io.flush.poke(false.B)
      fetchMiss(io, clock, 0x80000004L, mem, s"$kind/${indexBits}set/$vipt fence.i 后")
    }
  }

  for (kind <- Seq("reg", "sram")) {
    for (indexBits <- Seq(1, 4)) {
      s"ICache($kind,4B line,${1 << indexBits}sets)" should "正常 elaboration 并服务单字取指" in {
        run(kind, indexBits, vipt = false)
      }
    }
  }

  for (kind <- Seq("reg", "sram")) {
    s"ICache($kind,4B line,VIPT)" should "在 alias 扫描模式下服务单字取指" in {
      run(kind, indexBits = 4, vipt = true)
    }
  }
}
