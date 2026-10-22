package ysyx_26030103.cache

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

/** VIPT alias 失效扫描方式的定向测试。
  *
  * 覆盖 8/16/32 set、all_sets/serial 两种扫描，以及 Reg/SRAM 两种实现。
  * serial 模式必须做到：
  *   1. 每周期只推进一个 set，扫描期间拒绝新的 Cache 请求；
  *   2. 扫描完成后旧 alias 副本一定已被失效，后续访问必须重新 miss；
  *   3. DCache 的写直达 store 事件也不能丢，store 后其它 set 的旧副本
  *      必须失效。
  */
class ysyx_26030103_CacheAliasScanTest extends AnyFlatSpec with Matchers {
  private val AddrW     = 32
  private val BlockLog2 = 4 // 16B/line，refill 只有 4 拍，测试更短
  // 两个 VA 的低 4 位相同、组索引不同；PA 是同一物理行。
  // 对 8/16/32 set（index=[6:4]/[7:4]/[8:4]）都落在不同 set。
  private val VaA     = 0x00000040L
  private val VaB     = 0x00000000L
  private val PaLine  = 0x80004000L

  private def bool(value: Bool): Boolean = value.peek().litValue == 1
  private def long(value: UInt): Long    = value.peek().litValue.toLong

  private def defaultWord(address: Long): Long =
    ((address & ~3L) ^ 0x5a5a0000L) & 0xffffffffL

  private class Memory {
    private val words = mutable.Map.empty[Long, Long]
    def read(address: Long): Long =
      words.getOrElse(address & ~3L, defaultWord(address))
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

  private def waitReady(clock: Clock, ready: => Boolean, limit: Int): Int = {
    var cycles = 0
    while (!ready && cycles < 500) {
      clock.step()
      cycles += 1
    }
    withClue(s"ready 在 $cycles 拍后仍未拉高") { ready should be(true) }
    cycles
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
    io.fetch_addr.poke(0.U(AddrW.W))
    io.fetch_vaddr.poke(0.U(AddrW.W))
    io.fetch_paddr_valid.poke(false.B)
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
  }

  private def icacheFetch(
      io:    ysyx_26030103_ICacheInterface,
      clock: Clock,
      va:    Long,
      pa:    Long
  ): Unit = {
    io.fetch_vaddr.poke(va.U(AddrW.W))
    io.fetch_addr.poke(pa.U(AddrW.W))
    io.fetch_paddr_vaddr.poke(va.U(AddrW.W))
    io.fetch_paddr_valid.poke(true.B)
    io.fetch_valid.poke(true.B)
    waitUntil(clock, bool(io.fetch_ready), s"ICache 未受理 VA=0x${va.toHexString}")
    clock.step()
    io.fetch_valid.poke(false.B)
  }

  private def icacheExpect(
      io:    ysyx_26030103_ICacheInterface,
      clock: Clock,
      va:    Long,
      data:  Long
  ): Unit = {
    waitUntil(clock, bool(io.resp_valid), s"ICache VA=0x${va.toHexString} 无响应")
    long(io.resp_addr) should be(va)
    long(io.resp_data) should be(data)
    bool(io.resp_fault) should be(false)
    io.resp_ready.poke(true.B)
    clock.step()
    io.resp_ready.poke(false.B)
  }

  /** 手工完成一次 refill，返回从 AR 握手到关键词响应被消费的拍数。 */
  private def icacheRefill(
      io:    ysyx_26030103_ICacheInterface,
      clock: Clock,
      mem:   Memory
  ): Int = {
    waitUntil(clock, bool(io.axi.AR.ARVALID), "ICache 没有 refill AR")
    long(io.axi.AR.ARADDR) should be(PaLine)
    val beats = long(io.axi.AR.ARLEN).toInt + 1
    io.axi.AR.ARREADY.poke(true.B)
    clock.step()
    io.axi.AR.ARREADY.poke(false.B)
    for (beat <- 0 until beats) {
      io.axi.R.RDATA.poke(mem.read(PaLine + beat * 4L).U(32.W))
      io.axi.R.RRESP.poke(0.U(2.W))
      io.axi.R.RLAST.poke((beat + 1 == beats).B)
      io.axi.R.RVALID.poke(true.B)
      clock.step()
    }
    io.axi.R.RVALID.poke(false.B)
    io.axi.R.RLAST.poke(false.B)
    beats
  }

  private def runIcache(
      io:        ysyx_26030103_ICacheInterface,
      clock:     Clock,
      reset:     Reset,
      sets:      Int,
      scanMode:  String
  ): Unit = {
    icacheReset(io, clock, reset)
    val mem = new Memory

    // 第一次回填 A：AR 握手启动 serial 扫描；refill 只有 4 拍，
    // 消费响应时 8/16/32 set 的扫描尚未结束，fetch_ready 必须保持低。
    icacheFetch(io, clock, VaA, PaLine)
    val beats = icacheRefill(io, clock, mem)
    icacheExpect(io, clock, VaA, mem.read(PaLine))
    val scanCycles =
      if (scanMode == ysyx_26030103_ViptAliasScan.Serial) {
        bool(io.fetch_ready) should be(false)
        waitReady(clock, bool(io.fetch_ready), sets + 8)
      } else {
        bool(io.fetch_ready) should be(true)
        0
      }
    if (scanMode == ysyx_26030103_ViptAliasScan.Serial) {
      scanCycles should be > 0
      scanCycles should be <= sets
    }
    withClue("扫描拍数不应短于 refill 拍数") { beats should be(4) }

    // 同一个物理行换到另一个 set：必须先 miss 再回填；这次回填的扫描
    // 会失效 A 的副本。
    icacheFetch(io, clock, VaB, PaLine)
    icacheRefill(io, clock, mem)
    icacheExpect(io, clock, VaB, mem.read(PaLine))

    // A 的旧副本必须已被 B 的回填扫描失效，因此这里必须再次 miss。
    // 若 alias 扫描没有真正执行，icacheRefill 会等不到 AR 而超时。
    icacheFetch(io, clock, VaA, PaLine)
    icacheRefill(io, clock, mem)
    icacheExpect(io, clock, VaA, mem.read(PaLine))

    // flush 后不残留扫描状态，仍能正常取指。
    io.flush.poke(true.B)
    clock.step()
    io.flush.poke(false.B)
    icacheFetch(io, clock, VaB, PaLine)
    icacheRefill(io, clock, mem)
    icacheExpect(io, clock, VaB, mem.read(PaLine))
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
    io.req.valid.poke(false.B)
    io.req.bits.addr.poke(0.U(AddrW.W))
    io.req.bits.vaddr.poke(0.U(AddrW.W))
    io.req.bits.WidthSelect.poke(2.U(2.W))
    io.req.bits.signed.poke(false.B)
    io.req_paddr_valid.poke(false.B)
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
    io.AXI.R.RDATA.poke(0.U(32.W))
    io.AXI.R.RRESP.poke(0.U(2.W))
    io.AXI.R.RLAST.poke(false.B)
    io.AXI.R.RVALID.poke(false.B)
    io.flush.poke(false.B)
  }

  private def dcacheLoad(
      io:    ysyx_26030103_DCacheInterface,
      clock: Clock,
      va:    Long,
      pa:    Long
  ): Unit = {
    io.req.bits.vaddr.poke(va.U(AddrW.W))
    io.req.bits.addr.poke(pa.U(AddrW.W))
    io.req.bits.WidthSelect.poke(2.U(2.W))
    io.req.bits.signed.poke(false.B)
    io.req_paddr_vaddr.poke(va.U(AddrW.W))
    io.req_paddr_valid.poke(true.B)
    io.req.valid.poke(true.B)
    waitUntil(clock, bool(io.req.ready), s"DCache 未受理 VA=0x${va.toHexString}")
    clock.step()
    io.req.valid.poke(false.B)
  }

  private def dcacheStore(
      io:    ysyx_26030103_DCacheInterface,
      clock: Clock,
      va:    Long,
      pa:    Long,
      data:  Long,
      mem:   Memory
  ): Unit = {
    mem.write(pa, data)
    io.StoreVAddr.poke(va.U(AddrW.W))
    io.StoreAddr.poke(pa.U(AddrW.W))
    io.StoreData.poke(data.U(32.W))
    io.StoreStrb.poke("hf".U(4.W))
    io.StoreResp.poke(0.U(2.W))
    io.StoreValid.poke(true.B)
    clock.step()
    io.StoreValid.poke(false.B)
    io.StoreStrb.poke(0.U(4.W))
  }

  private def dcacheRefill(
      io:    ysyx_26030103_DCacheInterface,
      clock: Clock,
      mem:   Memory
  ): Int = {
    waitUntil(clock, bool(io.AXI.AR.ARVALID), "DCache 没有 refill AR")
    long(io.AXI.AR.ARADDR) should be(PaLine)
    val beats = long(io.AXI.AR.ARLEN).toInt + 1
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
    beats
  }

  private def dcacheExpect(
      io:    ysyx_26030103_DCacheInterface,
      clock: Clock,
      data:  Long
  ): Unit = {
    waitUntil(clock, bool(io.resp.valid), "DCache 无响应")
    long(io.resp.bits.data) should be(data)
    bool(io.resp.bits.fault) should be(false)
    io.resp.ready.poke(true.B)
    clock.step()
    io.resp.ready.poke(false.B)
  }

  private def runDcache(
      io:       ysyx_26030103_DCacheInterface,
      clock:    Clock,
      reset:    Reset,
      sets:     Int,
      scanMode: String
  ): Unit = {
    dcacheReset(io, clock, reset)
    val mem = new Memory

    // 第一次回填 A，并在 refill 响应被消费后检查 serial 扫描是否仍在
    // 反压新请求（4 拍 refill < 8/16/32 拍扫描）。
    dcacheLoad(io, clock, VaA, PaLine)
    dcacheRefill(io, clock, mem)
    dcacheExpect(io, clock, mem.read(PaLine))
    if (scanMode == ysyx_26030103_ViptAliasScan.Serial) {
      bool(io.req.ready) should be(false)
      val scanCycles = waitReady(clock, bool(io.req.ready), sets + 8)
      scanCycles should be > 0
      scanCycles should be <= sets
    } else {
      bool(io.req.ready) should be(true)
    }

    // B 回填（安装时扫描并失效 A 的副本）。
    dcacheLoad(io, clock, VaB, PaLine)
    dcacheRefill(io, clock, mem)
    dcacheExpect(io, clock, mem.read(PaLine))
    if (scanMode == ysyx_26030103_ViptAliasScan.Serial) {
      waitReady(clock, bool(io.req.ready), sets + 8)
    }

    // store 走 A：按 VA index 未命中，但物理行还在 B 的 set 里。
    // serial 下该 store 事件进入扫描队列，并反压后续 load。
    dcacheStore(io, clock, VaA, PaLine, 0x11112222L, mem)
    if (scanMode == ysyx_26030103_ViptAliasScan.Serial) {
      bool(io.req.ready) should be(false)
      val scanCycles = waitReady(clock, bool(io.req.ready), sets + 8)
      scanCycles should be > 0
      scanCycles should be <= sets
    } else {
      bool(io.req.ready) should be(true)
    }

    // B 的旧副本必须已被 store 扫描失效，因此这里要重新回填并读到新值。
    dcacheLoad(io, clock, VaB, PaLine)
    dcacheRefill(io, clock, mem)
    dcacheExpect(io, clock, 0x11112222L)
    if (scanMode == ysyx_26030103_ViptAliasScan.Serial) {
      waitReady(clock, bool(io.req.ready), sets + 8)
    }

    // store 命中 B：该行是唯一副本，应直接更新镜像，不产生扫描等待。
    dcacheStore(io, clock, VaB, PaLine, 0x33334444L, mem)
    bool(io.req.ready) should be(true)
    dcacheLoad(io, clock, VaB, PaLine)
    dcacheExpect(io, clock, 0x33334444L)
    bool(io.AXI.AR.ARVALID) should be(false)
  }

  // ---------------- 模块构造 ----------------

  private def icacheReg(setsLog2: Int, mode: String): ysyx_26030103_ICacheBase =
    new ysyx_26030103_ICacheReg(
      Enable = true,
      BlockSizeLog2 = BlockLog2,
      IndexBits = setsLog2,
      AddressWidth = AddrW,
      Vipt = true,
      ViptAliasSafe = true,
      ViptAliasScanMode = mode
    )

  private def icacheSram(setsLog2: Int, mode: String): ysyx_26030103_ICacheBase =
    new ysyx_26030103_ICacheSRAM(
      Enable = true,
      BlockSizeLog2 = BlockLog2,
      IndexBits = setsLog2,
      AddressWidth = AddrW,
      Vipt = true,
      ViptAliasSafe = true,
      ViptAliasScanMode = mode
    )

  private def dcacheReg(setsLog2: Int, mode: String): ysyx_26030103_DCacheBase =
    new ysyx_26030103_DCacheReg(
      Enable = true,
      BlockSizeLog2 = BlockLog2,
      IndexBits = setsLog2,
      AddressWidth = AddrW,
      Vipt = true,
      ViptAliasSafe = true,
      ViptAliasScanMode = mode
    )

  private def dcacheSram(setsLog2: Int, mode: String): ysyx_26030103_DCacheBase =
    new ysyx_26030103_DCacheSRAM(
      Enable = true,
      BlockSizeLog2 = BlockLog2,
      IndexBits = setsLog2,
      AddressWidth = AddrW,
      Vipt = true,
      ViptAliasSafe = true,
      ViptAliasScanMode = mode
    )

  private def forEachGeometry(body: (Int, String) => Unit): Unit = {
    for {
      setsLog2 <- Seq(3, 4, 5)
      mode <- Seq(
        ysyx_26030103_ViptAliasScan.AllSets,
        ysyx_26030103_ViptAliasScan.Serial
      )
    } body(setsLog2, mode)
  }

  "ICacheReg alias scan" should "在 8/16/32 set 下 ALL_SETS 与 SERIAL 都正确失效" in {
    forEachGeometry { (setsLog2, mode) =>
      val label = s"ICacheReg sets=${1 << setsLog2} mode=$mode"
      withClue(label) {
        simulate(icacheReg(setsLog2, mode)) { dut =>
          runIcache(dut.io, dut.clock, dut.reset, 1 << setsLog2, mode)
        }
      }
    }
  }

  "DCacheReg alias scan" should "在 8/16/32 set 下 ALL_SETS 与 SERIAL 都正确失效" in {
    forEachGeometry { (setsLog2, mode) =>
      val label = s"DCacheReg sets=${1 << setsLog2} mode=$mode"
      withClue(label) {
        simulate(dcacheReg(setsLog2, mode)) { dut =>
          runDcache(dut.io, dut.clock, dut.reset, 1 << setsLog2, mode)
        }
      }
    }
  }

  "ICacheSRAM/DCacheSRAM alias scan" should "与 Reg 实现保持相同语义" in {
    for (mode <- Seq(
        ysyx_26030103_ViptAliasScan.AllSets,
        ysyx_26030103_ViptAliasScan.Serial
      )) {
      withClue(s"ICacheSRAM mode=$mode") {
        simulate(icacheSram(3, mode)) { dut =>
          runIcache(dut.io, dut.clock, dut.reset, 8, mode)
        }
      }
      withClue(s"DCacheSRAM mode=$mode") {
        simulate(dcacheSram(3, mode)) { dut =>
          runDcache(dut.io, dut.clock, dut.reset, 8, mode)
        }
      }
    }
  }
}
