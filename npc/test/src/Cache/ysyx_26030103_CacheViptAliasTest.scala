package ysyx_26030103.cache

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

/** VIPT alias 安全的定向测试。
  *
  * 几何：128B/line、64 组，index bits=[12:7]。4KiB 页内偏移只有 12 位，
  * 因此 index 的最高位来自 VPN[0]：VaA=0x1000 落在 index 32，VaB=0 落在
  * index 0，二者可映射到同一物理行 PaLineA（经典 VIPT alias）。
  * 安全模式必须保证：refill 后同一物理行只留一份；写直达 store 后任何
  * VA 的 alias 副本都不会读到旧值；改变 VA->PA 映射后不会误命中旧 PA。
  */
class ysyx_26030103_CacheViptAliasTest extends AnyFlatSpec with Matchers {
  private val AddrW     = 32
  private val BlockLog2 = 7 // 128B/line
  private val IndexBits = 6 // 64 组；index+block=13 > pageBits(12)

  private val PaLineA = 0x80004000L
  private val PaLineB = 0x80005000L
  private val VaA     = 0x00001000L // index 32
  private val VaB     = 0x00000000L // index 0，与 VaA 不同 set

  private def bool(value: Bool): Boolean = value.peek().litValue == 1
  private def long(value: UInt): Long    = value.peek().litValue.toLong

  private def defaultWord(address: Long): Long =
    ((address & ~3L) ^ 0x5a5a0000L) & 0xffffffffL

  /** 简单字粒度内存模型：store 镜像事件会同步更新它。 */
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

  // ---------------- ICache ----------------

  private def icacheRefill(
      io:    ysyx_26030103_ICacheInterface,
      clock: Clock,
      line:  Long,
      mem:   Memory,
      label: String
  ): Unit = {
    waitUntil(clock, bool(io.axi.AR.ARVALID), s"$label ICache 没有 refill AR")
    withClue(s"$label ICache refill 地址错误") {
      long(io.axi.AR.ARADDR) should be(line)
    }
    val beats = long(io.axi.AR.ARLEN).toInt + 1
    io.axi.AR.ARREADY.poke(true.B)
    clock.step()
    io.axi.AR.ARREADY.poke(false.B)
    for (beat <- 0 until beats) {
      io.axi.R.RDATA.poke(mem.read(line + beat * 4L).U(32.W))
      io.axi.R.RRESP.poke(0.U(2.W))
      io.axi.R.RLAST.poke((beat + 1 == beats).B)
      io.axi.R.RVALID.poke(true.B)
      clock.step()
    }
    io.axi.R.RVALID.poke(false.B)
    io.axi.R.RLAST.poke(false.B)
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
    io.snoop_valid.poke(false.B)
    io.snoop_addr.poke(0.U(AddrW.W))
    io.snoop_addr_hi.poke(0.U(AddrW.W))
  }

  private def runIcache(
      io:    ysyx_26030103_ICacheInterface,
      clock: Clock,
      reset: Reset
  ): Unit = {
    icacheReset(io, clock, reset)
    val mem = new Memory

    // 同一物理行 PaLineA 通过两个不同 VA 取指。第二个 VA 必须 miss 并
    // 在安装时失效第一个 VA 的副本；再次取 VaA 必须重新 refill。
    icacheFetch(io, clock, VaA, PaLineA)
    icacheRefill(io, clock, PaLineA, mem, "ICache VaA")
    icacheExpect(io, clock, VaA, mem.read(PaLineA))

    icacheFetch(io, clock, VaB, PaLineA)
    icacheRefill(io, clock, PaLineA, mem, "ICache VaB alias")
    icacheExpect(io, clock, VaB, mem.read(PaLineA))

    icacheFetch(io, clock, VaA, PaLineA)
    icacheRefill(io, clock, PaLineA, mem, "ICache VaA after alias eviction")
    icacheExpect(io, clock, VaA, mem.read(PaLineA))

    // 上下文切换：同一个 VA 改映射到 PaLineB，不能命中 PaLineA 的旧行。
    icacheFetch(io, clock, VaA, PaLineB)
    icacheRefill(io, clock, PaLineB, mem, "ICache VaA new mapping")
    icacheExpect(io, clock, VaA, mem.read(PaLineB))

    icacheFetch(io, clock, VaA, PaLineA)
    icacheRefill(io, clock, PaLineA, mem, "ICache VaA mapping restored")
    icacheExpect(io, clock, VaA, mem.read(PaLineA))

    // flush(fence.i) 后所有 alias 副本都不可再用。
    io.flush.poke(true.B)
    clock.step()
    io.flush.poke(false.B)
    icacheFetch(io, clock, VaB, PaLineA)
    icacheRefill(io, clock, PaLineA, mem, "ICache VaB after flush")
    icacheExpect(io, clock, VaB, mem.read(PaLineA))
  }

  // ---------------- DCache ----------------

  private def dcacheRefill(
      io:    ysyx_26030103_DCacheInterface,
      clock: Clock,
      line:  Long,
      mem:   Memory,
      label: String
  ): Unit = {
    waitUntil(clock, bool(io.AXI.AR.ARVALID), s"$label DCache 没有 refill AR")
    withClue(s"$label DCache refill 地址错误") {
      long(io.AXI.AR.ARADDR) should be(line)
    }
    val beats = long(io.AXI.AR.ARLEN).toInt + 1
    io.AXI.AR.ARREADY.poke(true.B)
    clock.step()
    io.AXI.AR.ARREADY.poke(false.B)
    for (beat <- 0 until beats) {
      io.AXI.R.RDATA.poke(mem.read(line + beat * 4L).U(32.W))
      io.AXI.R.RRESP.poke(0.U(2.W))
      io.AXI.R.RLAST.poke((beat + 1 == beats).B)
      io.AXI.R.RVALID.poke(true.B)
      clock.step()
    }
    io.AXI.R.RVALID.poke(false.B)
    io.AXI.R.RLAST.poke(false.B)
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

  private def dcacheExpect(
      io:    ysyx_26030103_DCacheInterface,
      clock: Clock,
      data:  Long,
      label: String
  ): Unit = {
    waitUntil(clock, bool(io.resp.valid), s"$label DCache 无响应")
    withClue(s"$label DCache 数据错误") {
      long(io.resp.bits.data) should be(data)
    }
    bool(io.resp.bits.fault) should be(false)
    io.resp.ready.poke(true.B)
    clock.step()
    io.resp.ready.poke(false.B)
  }

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

  private def runDcache(
      io:    ysyx_26030103_DCacheInterface,
      clock: Clock,
      reset: Reset
  ): Unit = {
    dcacheReset(io, clock, reset)
    val mem = new Memory

    // 1) 两个 VA alias 同一物理行，读到的数据必须一致。
    dcacheLoad(io, clock, VaA, PaLineA)
    dcacheRefill(io, clock, PaLineA, mem, "DCache VaA")
    dcacheExpect(io, clock, mem.read(PaLineA), "DCache VaA")

    dcacheLoad(io, clock, VaB, PaLineA)
    dcacheRefill(io, clock, PaLineA, mem, "DCache VaB alias")
    dcacheExpect(io, clock, mem.read(PaLineA), "DCache VaB alias")

    // 2) store 用 VaA 发出：它按 VA index 没有命中（副本在 VaB 的 set），
    // 但写直达完成后必须失效 VaB 的旧副本，后续 VaB load 要重新取内存。
    dcacheStore(io, clock, VaA, PaLineA, 0x11112222L, mem)
    dcacheLoad(io, clock, VaB, PaLineA)
    dcacheRefill(io, clock, PaLineA, mem, "DCache VaB after alias store")
    dcacheExpect(io, clock, 0x11112222L, "DCache VaB after alias store")

    // 3) store 命中 VaB 的副本，镜像与内存同时更新；VaB 应立即命中，
    // VaA 的旧副本则必须 miss 后取到新值。
    dcacheStore(io, clock, VaB, PaLineA, 0x33334444L, mem)
    dcacheLoad(io, clock, VaB, PaLineA)
    dcacheExpect(io, clock, 0x33334444L, "DCache VaB store hit")
    dcacheLoad(io, clock, VaA, PaLineA)
    dcacheRefill(io, clock, PaLineA, mem, "DCache VaA after store hit")
    dcacheExpect(io, clock, 0x33334444L, "DCache VaA after store hit")

    // 4) 上下文切换：同一个 VA 改映射到 PaLineB，再切回 PaLineA，均不得
    // 误用旧 PA 的 tag。
    dcacheLoad(io, clock, VaA, PaLineB)
    dcacheRefill(io, clock, PaLineB, mem, "DCache VaA new mapping")
    dcacheExpect(io, clock, mem.read(PaLineB), "DCache VaA new mapping")
    dcacheLoad(io, clock, VaA, PaLineA)
    dcacheRefill(io, clock, PaLineA, mem, "DCache VaA mapping restored")
    dcacheExpect(io, clock, 0x33334444L, "DCache VaA mapping restored")

    // 5) refill 安装也会失效另一个 set 的同一物理行副本：VaB 回填后，
    // VaA 必须再次 miss/refill，而不是继续命中旧副本。
    dcacheLoad(io, clock, VaB, PaLineA)
    dcacheRefill(io, clock, PaLineA, mem, "DCache VaB refill evicts VaA")
    dcacheExpect(io, clock, 0x33334444L, "DCache VaB refill evicts VaA")
    dcacheLoad(io, clock, VaA, PaLineA)
    dcacheRefill(io, clock, PaLineA, mem, "DCache VaA after VaB refill")
    dcacheExpect(io, clock, 0x33334444L, "DCache VaA after VaB refill")
  }

  private def newIcacheReg(): ysyx_26030103_ICacheReg =
    new ysyx_26030103_ICacheReg(
      Enable = true,
      BlockSizeLog2 = BlockLog2,
      IndexBits = IndexBits,
      AddressWidth = AddrW,
      Vipt = true,
      ViptAliasSafe = true
    )

  private def newIcacheSram(): ysyx_26030103_ICacheSRAM =
    new ysyx_26030103_ICacheSRAM(
      Enable = true,
      BlockSizeLog2 = BlockLog2,
      IndexBits = IndexBits,
      AddressWidth = AddrW,
      Vipt = true,
      ViptAliasSafe = true
    )

  private def newDcacheReg(): ysyx_26030103_DCacheReg =
    new ysyx_26030103_DCacheReg(
      Enable = true,
      BlockSizeLog2 = BlockLog2,
      IndexBits = IndexBits,
      AddressWidth = AddrW,
      Vipt = true,
      ViptAliasSafe = true
    )

  private def newDcacheSram(): ysyx_26030103_DCacheSRAM =
    new ysyx_26030103_DCacheSRAM(
      Enable = true,
      BlockSizeLog2 = BlockLog2,
      IndexBits = IndexBits,
      AddressWidth = AddrW,
      Vipt = true,
      ViptAliasSafe = true
    )

  "ICacheReg VIPT alias-safe" should "按物理行失效其他 set 的别名副本" in {
    simulate(newIcacheReg()) { dut => runIcache(dut.io, dut.clock, dut.reset) }
  }

  "ICacheSRAM VIPT alias-safe" should "按物理行失效其他 set 的别名副本" in {
    simulate(newIcacheSram()) { dut => runIcache(dut.io, dut.clock, dut.reset) }
  }

  "DCacheReg VIPT alias-safe" should "store 后所有 VA alias 读取一致" in {
    simulate(newDcacheReg()) { dut => runDcache(dut.io, dut.clock, dut.reset) }
  }

  "DCacheSRAM VIPT alias-safe" should "store 后所有 VA alias 读取一致" in {
    simulate(newDcacheSram()) { dut => runDcache(dut.io, dut.clock, dut.reset) }
  }
}
