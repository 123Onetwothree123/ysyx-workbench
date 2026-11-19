package ysyx_26030103.cache

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

/** PIPT + cache line > page 的定向测试。
  *
  * MMU 页大小在该叶模块之外；这里用 128B line 模拟“页只有 64B”的
  * 配置（NPCConfig 层已单独校验 128B > 64B 被 PIPT 接受、被 VIPT 拒绝）。
  * PIPT 的索引和 refill 必须直接使用完整物理地址：AR 地址按 128B 行
  * 对齐，不按 64B 页截断，也不使用虚拟地址。
  */
class ysyx_26030103_CachePiptLargeLineTest extends AnyFlatSpec with Matchers {
  private val AddressW     = 32
  private val BlockLog2 = 7 // 128B/line
  private val IndexBits = 2 // 4 组，行内偏移跨过 64B “页”
  private val PaLine    = 0x80004000L

  private def bool(value: Bool): Boolean = value.peek().litValue == 1
  private def long(value: UInt): Long    = value.peek().litValue.toLong

  private def wordOf(address: Long): Long =
    ((address & ~3L) ^ 0x3c3c0000L) & 0xffffffffL

  private class Memory {
    private val words = mutable.Map.empty[Long, Long]
    def read(address: Long): Long =
      words.getOrElse(address & ~3L, wordOf(address))
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

  // ---------------- ICache ----------------

  private def icacheReset(
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
  }

  private def icacheFetch(
      io:    ysyx_26030103_ICacheInterface,
      clock: Clock,
      pa:    Long
  ): Unit = {
    // PIPT 下 VA 端口不再参与索引；故意给一个不相关的 VA，确认被忽略。
    io.FetchVaddr.poke(0x00000040L.U(AddressW.W))
    io.FetchAddress.poke(pa.U(AddressW.W))
    io.FetchPaddrVaddr.poke(0x00000040L.U(AddressW.W))
    io.FetchPaddrValid.poke(true.B)
    io.FetchValid.poke(true.B)
    waitUntil(clock, bool(io.FetchReady), s"ICache 未受理 PA=0x${pa.toHexString}")
    clock.step()
    io.FetchValid.poke(false.B)
  }

  private def icacheRefill(
      io:    ysyx_26030103_ICacheInterface,
      clock: Clock,
      mem:   Memory
  ): Unit = {
    waitUntil(clock, bool(io.AXI.AR.ARVALID), "ICache 没有 refill AR")
    long(io.AXI.AR.ARADDR) should be(PaLine)
    val beats = long(io.AXI.AR.ARLEN).toInt + 1
    beats should be(1 << (BlockLog2 - 2)) // 128B = 32 个 word，PIPT 不能截断
    io.AXI.AR.ARREADY.poke(true.B)
    clock.step()
    io.AXI.AR.ARREADY.poke(false.B)
    for (beat <- 0 until beats) {
      io.AXI.R.RDATA.poke(mem.read(PaLine + beat * 4L).U(32.W))
      io.AXI.R.RRESP.poke(0.U(2.W))
      io.AXI.R.RLAST.poke((beat + 1 == beats).B)
      io.AXI.R.RVALID.poke(true.B)
      clock.step()
    }
    io.AXI.R.RVALID.poke(false.B)
    io.AXI.R.RLAST.poke(false.B)
  }

  private def icacheExpect(
      io:    ysyx_26030103_ICacheInterface,
      clock: Clock,
      pa:    Long,
      data:  Long
  ): Unit = {
    waitUntil(clock, bool(io.ResponseValid), s"ICache PA=0x${pa.toHexString} 无响应")
    long(io.ResponseData) should be(data)
    bool(io.ResponseFault) should be(false)
    io.ResponseReady.poke(true.B)
    clock.step()
    io.ResponseReady.poke(false.B)
  }

  private def runICache(
      io:    ysyx_26030103_ICacheInterface,
      clock: Clock,
      reset: Reset
  ): Unit = {
    icacheReset(io, clock, reset)
    val mem = new Memory
    // 行内第 17 个 word，偏移 68 > 64B，跨过模拟的小页。
    val fetchPa1 = PaLine + 68L
    icacheFetch(io, clock, fetchPa1)
    icacheRefill(io, clock, mem)
    icacheExpect(io, clock, fetchPa1, mem.read(fetchPa1))

    // 同一 128B 行内另一个 word 必须命中，且不能再发 AR（PIPT 索引正确）。
    val fetchPa2 = PaLine + 4L
    icacheFetch(io, clock, fetchPa2)
    bool(io.AXI.AR.ARVALID) should be(false)
    icacheExpect(io, clock, fetchPa2, mem.read(fetchPa2))

    // 下一条 128B 行必须 miss 并按完整物理行地址回填。
    val fetchPa3 = PaLine + 128L
    icacheFetch(io, clock, fetchPa3)
    waitUntil(clock, bool(io.AXI.AR.ARVALID), "大行 PIPT ICache 第二行无 AR")
    long(io.AXI.AR.ARADDR) should be(fetchPa3 & ~127L)
    io.AXI.AR.ARREADY.poke(true.B)
    clock.step()
    io.AXI.AR.ARREADY.poke(false.B)
    for (beat <- 0 until (1 << (BlockLog2 - 2))) {
      io.AXI.R.RDATA.poke(mem.read((fetchPa3 & ~127L) + beat * 4L).U(32.W))
      io.AXI.R.RRESP.poke(0.U(2.W))
      io.AXI.R.RLAST.poke((beat + 1 == (1 << (BlockLog2 - 2))).B)
      io.AXI.R.RVALID.poke(true.B)
      clock.step()
    }
    io.AXI.R.RVALID.poke(false.B)
    io.AXI.R.RLAST.poke(false.B)
    icacheExpect(io, clock, fetchPa3, mem.read(fetchPa3))
  }

  // ---------------- DCache ----------------

  private def dcacheReset(
      io:    ysyx_26030103_DCacheInterface,
      clock: Clock,
      reset: Reset
  ): Unit = {
    reset.poke(true.B)
    clock.step(2)
    reset.poke(false.B)
    io.request.valid.poke(false.B)
    io.request.bits.address.poke(0.U(AddressW.W))
    io.request.bits.vaddr.poke(0.U(AddressW.W))
    io.request.bits.WidthSelect.poke(2.U(2.W))
    io.request.bits.signed.poke(false.B)
    io.RequestPaddrValid.poke(true.B)
    io.RequestPaddrVaddr.poke(0.U(AddressW.W))
    io.RequestKill.poke(false.B)
    io.response.ready.poke(false.B)
    io.StoreValid.poke(false.B)
    io.StoreResponse.poke(0.U(2.W))
    io.StoreAddress.poke(0.U(AddressW.W))
    io.StoreVAddress.poke(0.U(AddressW.W))
    io.StoreData.poke(0.U(32.W))
    io.StoreStrobe.poke(0.U(4.W))
    io.AXI.AR.ARREADY.poke(false.B)
    io.AXI.R.RDATA.poke(0.U(32.W))
    io.AXI.R.RRESP.poke(0.U(2.W))
    io.AXI.R.RLAST.poke(false.B)
    io.AXI.R.RVALID.poke(false.B)
    io.flush.poke(false.B)
  }

  private def dcacheLoad(
      io:    ysyx_26030103_DCacheInterface,
      clock: Clock,
      pa:    Long
  ): Unit = {
    // PIPT 忽略 vaddr；这里故意给常量 VA。
    io.request.bits.vaddr.poke(0x00000040L.U(AddressW.W))
    io.request.bits.address.poke(pa.U(AddressW.W))
    io.request.bits.WidthSelect.poke(2.U(2.W))
    io.request.bits.signed.poke(false.B)
    io.RequestPaddrVaddr.poke(0x00000040L.U(AddressW.W))
    io.RequestPaddrValid.poke(true.B)
    io.request.valid.poke(true.B)
    waitUntil(clock, bool(io.request.ready), s"DCache 未受理 PA=0x${pa.toHexString}")
    clock.step()
    io.request.valid.poke(false.B)
  }

  private def dcacheStore(
      io:    ysyx_26030103_DCacheInterface,
      clock: Clock,
      pa:    Long,
      data:  Long,
      mem:   Memory
  ): Unit = {
    mem.write(pa, data)
    io.StoreVAddress.poke(0x00000040L.U(AddressW.W))
    io.StoreAddress.poke(pa.U(AddressW.W))
    io.StoreData.poke(data.U(32.W))
    io.StoreStrobe.poke("hf".U(4.W))
    io.StoreResponse.poke(0.U(2.W))
    io.StoreValid.poke(true.B)
    clock.step()
    io.StoreValid.poke(false.B)
    io.StoreStrobe.poke(0.U(4.W))
  }

  private def dcacheExpect(
      io:    ysyx_26030103_DCacheInterface,
      clock: Clock,
      data:  Long
  ): Unit = {
    waitUntil(clock, bool(io.response.valid), "DCache 无响应")
    long(io.response.bits.data) should be(data)
    bool(io.response.bits.fault) should be(false)
    io.response.ready.poke(true.B)
    clock.step()
    io.response.ready.poke(false.B)
  }

  private def dcacheRefill(
      io:    ysyx_26030103_DCacheInterface,
      clock: Clock,
      mem:   Memory
  ): Unit = {
    waitUntil(clock, bool(io.AXI.AR.ARVALID), "DCache 没有 refill AR")
    long(io.AXI.AR.ARADDR) should be(PaLine)
    val beats = long(io.AXI.AR.ARLEN).toInt + 1
    beats should be(1 << (BlockLog2 - 2))
    io.AXI.AR.ARREADY.poke(true.B)
    clock.step()
    io.AXI.AR.ARREADY.poke(false.B)
    for (beat <- 0 until beats) {
      io.AXI.R.RDATA.poke(mem.read(PaLine + beat * 4L).U(32.W))
      io.AXI.R.RRESP.poke(0.U(2.W))
      io.AXI.R.RLAST.poke((beat + 1 == beats).B)
      io.AXI.R.RVALID.poke(true.B)
      clock.step()
    }
    io.AXI.R.RVALID.poke(false.B)
    io.AXI.R.RLAST.poke(false.B)
  }

  private def runDCache(
      io:    ysyx_26030103_DCacheInterface,
      clock: Clock,
      reset: Reset
  ): Unit = {
    dcacheReset(io, clock, reset)
    val mem = new Memory
    val loadPa1 = PaLine + 68L
    dcacheLoad(io, clock, loadPa1)
    dcacheRefill(io, clock, mem)
    dcacheExpect(io, clock, mem.read(loadPa1))

    // 同行另一 word 命中，无 AR。
    val loadPa2 = PaLine + 4L
    dcacheLoad(io, clock, loadPa2)
    dcacheExpect(io, clock, mem.read(loadPa2))
    bool(io.AXI.AR.ARVALID) should be(false)

    // 命中 store 更新镜像；随后同一行读取看到新值。
    dcacheStore(io, clock, PaLine + 68L, 0x55667788L, mem)
    dcacheLoad(io, clock, PaLine + 68L)
    dcacheExpect(io, clock, 0x55667788L)
    bool(io.AXI.AR.ARVALID) should be(false)

    // 下一条 128B 行 miss，refill 用完整物理行地址。
    val loadPa3 = PaLine + 256L
    dcacheLoad(io, clock, loadPa3)
    waitUntil(clock, bool(io.AXI.AR.ARVALID), "大行 PIPT DCache 第二行无 AR")
    long(io.AXI.AR.ARADDR) should be(loadPa3 & ~127L)
    io.AXI.AR.ARREADY.poke(true.B)
    clock.step()
    io.AXI.AR.ARREADY.poke(false.B)
    val line3 = loadPa3 & ~127L
    for (beat <- 0 until (1 << (BlockLog2 - 2))) {
      io.AXI.R.RDATA.poke(mem.read(line3 + beat * 4L).U(32.W))
      io.AXI.R.RRESP.poke(0.U(2.W))
      io.AXI.R.RLAST.poke((beat + 1 == (1 << (BlockLog2 - 2))).B)
      io.AXI.R.RVALID.poke(true.B)
      clock.step()
    }
    io.AXI.R.RVALID.poke(false.B)
    io.AXI.R.RLAST.poke(false.B)
    dcacheExpect(io, clock, mem.read(loadPa3))
  }

  private def icacheReg(): ysyx_26030103_ICacheBase =
    new ysyx_26030103_ICacheReg(
      Enable = true,
      BlockSizeLog2 = BlockLog2,
      IndexBits = IndexBits,
      AddressWidth = AddressW,
      DataWidth = 32,
      VIPT = false
    )

  private def icacheSram(): ysyx_26030103_ICacheBase =
    new ysyx_26030103_ICacheSRAM(
      Enable = true,
      BlockSizeLog2 = BlockLog2,
      IndexBits = IndexBits,
      AddressWidth = AddressW,
      DataWidth = 32,
      VIPT = false
    )

  private def dcacheReg(): ysyx_26030103_DCacheBase =
    new ysyx_26030103_DCacheReg(
      Enable = true,
      BlockSizeLog2 = BlockLog2,
      IndexBits = IndexBits,
      AddressWidth = AddressW,
      BusDataWidth = 32,
      VIPT = false
    )

  private def dcacheSram(): ysyx_26030103_DCacheBase =
    new ysyx_26030103_DCacheSRAM(
      Enable = true,
      BlockSizeLog2 = BlockLog2,
      IndexBits = IndexBits,
      AddressWidth = AddressW,
      BusDataWidth = 32,
      VIPT = false
    )

  "PIPT ICache" should "用物理地址回填大于页的 cache line" in {
    simulate(icacheReg()) { dut =>
      runICache(dut.io, dut.clock, dut.reset)
    }
    simulate(icacheSram()) { dut =>
      runICache(dut.io, dut.clock, dut.reset)
    }
  }

  "PIPT DCache" should "用物理地址索引大于页的 cache line" in {
    simulate(dcacheReg()) { dut =>
      runDCache(dut.io, dut.clock, dut.reset)
    }
    simulate(dcacheSram()) { dut =>
      runDCache(dut.io, dut.clock, dut.reset)
    }
  }
}
