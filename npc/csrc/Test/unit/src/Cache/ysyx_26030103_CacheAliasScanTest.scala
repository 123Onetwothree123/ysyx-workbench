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

  /** 一轮 alias 维护需要的扫描拍数；0 表示单拍/目录模式无扫描等待。 */
  private def scanChunks(sets: Int, mode: String, width: Int): Int =
    mode match {
      case m if m == ysyx_26030103_ViptAliasScan.Serial => sets
      case m if m == ysyx_26030103_ViptAliasScan.ParallelN =>
        (sets + width - 1) / width
      case _ => 0
    }

  private def scanWait(
      io:    ysyx_26030103_ICacheInterface,
      clock: Clock,
      sets:  Int,
      mode:  String,
      width: Int,
      label: String
  ): Unit = {
    val chunks = scanChunks(sets, mode, width)
    if (chunks == 0) {
      bool(io.fetch_ready) should be(true)
    } else {
      val cycles = waitReady(clock, bool(io.fetch_ready), chunks + 12)
      withClue(s"$label 扫描等待 $cycles 拍超过预期 $chunks") {
        cycles should be <= (chunks + 12)
      }
    }
  }

  private def scanWait(
      io:    ysyx_26030103_DCacheInterface,
      clock: Clock,
      sets:  Int,
      mode:  String,
      width: Int,
      label: String
  ): Unit = {
    val chunks = scanChunks(sets, mode, width)
    if (chunks == 0) {
      bool(io.req.ready) should be(true)
    } else {
      val cycles = waitReady(clock, bool(io.req.ready), chunks + 12)
      withClue(s"$label 扫描等待 $cycles 拍超过预期 $chunks") {
        cycles should be <= (chunks + 12)
      }
    }
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
      mem:   Memory,
      line:  Long = PaLine
  ): Int = {
    waitUntil(clock, bool(io.axi.AR.ARVALID), "ICache 没有 refill AR")
    long(io.axi.AR.ARADDR) should be(line)
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
    beats
  }

  private def runIcache(
      io:        ysyx_26030103_ICacheInterface,
      clock:     Clock,
      reset:     Reset,
      sets:      Int,
      scanMode:  String,
      scanWidth: Int
  ): Unit = {
    icacheReset(io, clock, reset)
    val mem = new Memory

    // 第一次回填 A：AR 握手启动扫描；refill 只有 4 拍，扫描尚未完成时
    // fetch_ready 必须保持低。directory 在 AR 拍完成失效，不需要扫描等待。
    icacheFetch(io, clock, VaA, PaLine)
    val beats = icacheRefill(io, clock, mem)
    icacheExpect(io, clock, VaA, mem.read(PaLine))
    scanWait(io, clock, sets, scanMode, scanWidth, "ICache refill A")
    withClue("refill 拍数不应异常") { beats should be(4) }

    // 同一个物理行换到另一个 set：必须先 miss 再回填；这次回填的扫描
    // 会失效 A 的副本。
    icacheFetch(io, clock, VaB, PaLine)
    icacheRefill(io, clock, mem)
    icacheExpect(io, clock, VaB, mem.read(PaLine))
    scanWait(io, clock, sets, scanMode, scanWidth, "ICache refill B")

    // A 的旧副本必须已被 B 的回填扫描失效，因此这里必须再次 miss。
    // 若 alias 扫描没有真正执行，icacheRefill 会等不到 AR 而超时。
    icacheFetch(io, clock, VaA, PaLine)
    icacheRefill(io, clock, mem)
    icacheExpect(io, clock, VaA, mem.read(PaLine))
    scanWait(io, clock, sets, scanMode, scanWidth, "ICache refill A2")

    // flush 后不残留扫描/目录状态，仍能正常取指。
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
      mem:   Memory,
      line:  Long = PaLine
  ): Int = {
    waitUntil(clock, bool(io.AXI.AR.ARVALID), "DCache 没有 refill AR")
    long(io.AXI.AR.ARADDR) should be(line)
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
      io:        ysyx_26030103_DCacheInterface,
      clock:     Clock,
      reset:     Reset,
      sets:      Int,
      scanMode:  String,
      scanWidth: Int
  ): Unit = {
    dcacheReset(io, clock, reset)
    val mem = new Memory

    // 第一次回填 A，并在 refill 响应被消费后检查扫描是否仍在反压新请求。
    dcacheLoad(io, clock, VaA, PaLine)
    dcacheRefill(io, clock, mem)
    dcacheExpect(io, clock, mem.read(PaLine))
    scanWait(io, clock, sets, scanMode, scanWidth, "DCache refill A")

    // B 回填（安装时扫描并失效 A 的副本）。
    dcacheLoad(io, clock, VaB, PaLine)
    dcacheRefill(io, clock, mem)
    dcacheExpect(io, clock, mem.read(PaLine))
    scanWait(io, clock, sets, scanMode, scanWidth, "DCache refill B")

    // store 走 A：按 VA index 未命中，但物理行还在 B 的 set 里。
    // 扫描模式把该 store 事件放入队列并反压后续 load。
    dcacheStore(io, clock, VaA, PaLine, 0x11112222L, mem)
    scanWait(io, clock, sets, scanMode, scanWidth, "DCache store A")

    // B 的旧副本必须已被 store 扫描/目录失效，因此这里要重新回填并读到新值。
    dcacheLoad(io, clock, VaB, PaLine)
    dcacheRefill(io, clock, mem)
    dcacheExpect(io, clock, 0x11112222L)
    scanWait(io, clock, sets, scanMode, scanWidth, "DCache refill B2")

    // store 命中 B：该行是唯一副本，应直接更新镜像，不产生扫描等待。
    dcacheStore(io, clock, VaB, PaLine, 0x33334444L, mem)
    bool(io.req.ready) should be(true)
    dcacheLoad(io, clock, VaB, PaLine)
    dcacheExpect(io, clock, 0x33334444L)
    bool(io.AXI.AR.ARVALID) should be(false)

    // flush/DMA 失效后不能命中旧行；目录/扫描状态也必须一起清空。
    io.flush.poke(true.B)
    clock.step()
    io.flush.poke(false.B)
    dcacheLoad(io, clock, VaB, PaLine)
    dcacheRefill(io, clock, mem)
    dcacheExpect(io, clock, 0x33334444L)
  }

  // ---------------- 模块构造 ----------------

  private case class AliasCase(mode: String, width: Int) {
    def label: String =
      if (mode == ysyx_26030103_ViptAliasScan.ParallelN)
        s"$mode/$width"
      else mode
  }

  private val AliasCases = Seq(
    AliasCase(ysyx_26030103_ViptAliasScan.AllSets, 0),
    AliasCase(ysyx_26030103_ViptAliasScan.Serial, 0),
    AliasCase(ysyx_26030103_ViptAliasScan.ParallelN, 2),
    AliasCase(ysyx_26030103_ViptAliasScan.ParallelN, 4),
    AliasCase(ysyx_26030103_ViptAliasScan.ParallelN, 8),
    AliasCase(ysyx_26030103_ViptAliasScan.Directory, 0)
  )

  private def icacheReg(
      setsLog2: Int,
      c: AliasCase,
      dirEntries: Int = 0
  ): ysyx_26030103_ICacheBase =
    new ysyx_26030103_ICacheReg(
      Enable = true,
      BlockSizeLog2 = BlockLog2,
      IndexBits = setsLog2,
      AddressWidth = AddrW,
      Vipt = true,
      ViptAliasSafe = true,
      ViptAliasScanMode = c.mode,
      ViptAliasScanWidth = c.width,
      ViptAliasDirectoryEntries = dirEntries
    )

  private def icacheSram(
      setsLog2: Int,
      c: AliasCase,
      dirEntries: Int = 0
  ): ysyx_26030103_ICacheBase =
    new ysyx_26030103_ICacheSRAM(
      Enable = true,
      BlockSizeLog2 = BlockLog2,
      IndexBits = setsLog2,
      AddressWidth = AddrW,
      Vipt = true,
      ViptAliasSafe = true,
      ViptAliasScanMode = c.mode,
      ViptAliasScanWidth = c.width,
      ViptAliasDirectoryEntries = dirEntries
    )

  private def dcacheReg(
      setsLog2: Int,
      c: AliasCase,
      dirEntries: Int = 0
  ): ysyx_26030103_DCacheBase =
    new ysyx_26030103_DCacheReg(
      Enable = true,
      BlockSizeLog2 = BlockLog2,
      IndexBits = setsLog2,
      AddressWidth = AddrW,
      Vipt = true,
      ViptAliasSafe = true,
      ViptAliasScanMode = c.mode,
      ViptAliasScanWidth = c.width,
      ViptAliasDirectoryEntries = dirEntries
    )

  private def dcacheSram(
      setsLog2: Int,
      c: AliasCase,
      dirEntries: Int = 0
  ): ysyx_26030103_DCacheBase =
    new ysyx_26030103_DCacheSRAM(
      Enable = true,
      BlockSizeLog2 = BlockLog2,
      IndexBits = setsLog2,
      AddressWidth = AddrW,
      Vipt = true,
      ViptAliasSafe = true,
      ViptAliasScanMode = c.mode,
      ViptAliasScanWidth = c.width,
      ViptAliasDirectoryEntries = dirEntries
    )

  private def forEachGeometry(body: (Int, AliasCase) => Unit): Unit = {
    for {
      setsLog2 <- Seq(3, 4, 5)
      c <- AliasCases
    } body(setsLog2, c)
  }

  /** 目录容量小于 set 数时的冲突测试。
    *
    * 8 set、4 项目录：set 0 与 set 4 共享 0 号桶。依次安装 line0..line4
    * 后，line4 的安装必须把 line0 挤出（同时失效 set0）；重新安装 line0
    * 又会挤出 line4。若目录冲突没有真正失效 Cache 行，后续访问会命中
    * 旧副本而不发 AR，测试中的 refill 等待会超时。
    */
  private def runIcacheDirectoryCapacity(
      io:    ysyx_26030103_ICacheInterface,
      clock: Clock,
      reset: Reset
  ): Unit = {
    icacheReset(io, clock, reset)
    val mem = new Memory
    val base = 0x80000000L
    def line(i: Int): Long = base + i.toLong * 0x10L
    def va(i: Int): Long = i.toLong << 4

    for (i <- 0 until 5) {
      icacheFetch(io, clock, va(i), line(i))
      icacheRefill(io, clock, mem, line(i))
      icacheExpect(io, clock, va(i), mem.read(line(i)))
    }
    // line0 已被 line4 的目录桶冲突挤出。
    icacheFetch(io, clock, va(0), line(0))
    icacheRefill(io, clock, mem, line(0))
    icacheExpect(io, clock, va(0), mem.read(line(0)))
    // line0 重新安装后，line4 也被挤出。
    icacheFetch(io, clock, va(4), line(4))
    icacheRefill(io, clock, mem, line(4))
    icacheExpect(io, clock, va(4), mem.read(line(4)))
  }

  private def runDcacheDirectoryCapacity(
      io:    ysyx_26030103_DCacheInterface,
      clock: Clock,
      reset: Reset
  ): Unit = {
    dcacheReset(io, clock, reset)
    val mem = new Memory
    val base = 0x80000000L
    def line(i: Int): Long = base + i.toLong * 0x10L
    def va(i: Int): Long = i.toLong << 4

    for (i <- 0 until 5) {
      dcacheLoad(io, clock, va(i), line(i))
      dcacheRefill(io, clock, mem, line(i))
      dcacheExpect(io, clock, mem.read(line(i)))
    }
    dcacheLoad(io, clock, va(0), line(0))
    dcacheRefill(io, clock, mem, line(0))
    dcacheExpect(io, clock, mem.read(line(0)))
    dcacheLoad(io, clock, va(4), line(4))
    dcacheRefill(io, clock, mem, line(4))
    dcacheExpect(io, clock, mem.read(line(4)))
  }

  "ICacheReg alias scan" should "在 8/16/32 set 下所有扫描/目录模式都正确失效" in {
    forEachGeometry { (setsLog2, c) =>
      val label = s"ICacheReg sets=${1 << setsLog2} mode=${c.label}"
      withClue(label) {
        simulate(icacheReg(setsLog2, c)) { dut =>
          runIcache(
            dut.io,
            dut.clock,
            dut.reset,
            1 << setsLog2,
            c.mode,
            c.width
          )
        }
      }
    }
  }

  "DCacheReg alias scan" should "在 8/16/32 set 下所有扫描/目录模式都正确失效" in {
    forEachGeometry { (setsLog2, c) =>
      val label = s"DCacheReg sets=${1 << setsLog2} mode=${c.label}"
      withClue(label) {
        simulate(dcacheReg(setsLog2, c)) { dut =>
          runDcache(
            dut.io,
            dut.clock,
            dut.reset,
            1 << setsLog2,
            c.mode,
            c.width
          )
        }
      }
    }
  }

  "ICacheSRAM/DCacheSRAM alias scan" should "与 Reg 实现保持相同语义" in {
    for (c <- AliasCases) {
      withClue(s"ICacheSRAM mode=${c.label}") {
        simulate(icacheSram(3, c)) { dut =>
          runIcache(dut.io, dut.clock, dut.reset, 8, c.mode, c.width)
        }
      }
      withClue(s"DCacheSRAM mode=${c.label}") {
        simulate(dcacheSram(3, c)) { dut =>
          runDcache(dut.io, dut.clock, dut.reset, 8, c.mode, c.width)
        }
      }
    }
  }

  "DIRECTORY 容量冲突" should "显式失效被挤出目录的 Cache 行" in {
    val dirCase = AliasCase(ysyx_26030103_ViptAliasScan.Directory, 0)
    withClue("ICacheReg directory entries=4") {
      simulate(icacheReg(3, dirCase, dirEntries = 4)) { dut =>
        runIcacheDirectoryCapacity(dut.io, dut.clock, dut.reset)
      }
    }
    withClue("ICacheSRAM directory entries=4") {
      simulate(icacheSram(3, dirCase, dirEntries = 4)) { dut =>
        runIcacheDirectoryCapacity(dut.io, dut.clock, dut.reset)
      }
    }
    withClue("DCacheReg directory entries=4") {
      simulate(dcacheReg(3, dirCase, dirEntries = 4)) { dut =>
        runDcacheDirectoryCapacity(dut.io, dut.clock, dut.reset)
      }
    }
    withClue("DCacheSRAM directory entries=4") {
      simulate(dcacheSram(3, dirCase, dirEntries = 4)) { dut =>
        runDcacheDirectoryCapacity(dut.io, dut.clock, dut.reset)
      }
    }
    // 最小目录（1 项）也必须保证“目录命中缺失 = 没有有效副本”：所有
    // set 共用一个桶，每次安装都失效前一行，仍不能出现陈旧 alias。
    withClue("ICacheReg directory entries=1") {
      simulate(icacheReg(3, dirCase, dirEntries = 1)) { dut =>
        runIcacheDirectoryCapacity(dut.io, dut.clock, dut.reset)
      }
    }
    withClue("DCacheReg directory entries=1") {
      simulate(dcacheReg(3, dirCase, dirEntries = 1)) { dut =>
        runDcacheDirectoryCapacity(dut.io, dut.clock, dut.reset)
      }
    }
  }
}
