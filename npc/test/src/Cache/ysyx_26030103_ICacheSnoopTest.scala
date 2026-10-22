package ysyx_26030103.cache

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import _root_.ysyx_26030103.common.ysyx_26030103_PMARegion

import scala.collection.mutable

/** 自修改代码一致性的 ICache 定向测试。
  *
  * 覆盖三种路径：
  *   1. FENCE_I：store 后不执行 fence.i，ICache 继续命中旧行；
  *      fence.i 之后重新 refill 才能看到新指令（标准语义）。
  *   2. HARDWARE_SNOOP：store B 完成事件只失效对应物理行，其他行仍命中；
  *      不需要 fence.i 就能在后续取指看到新指令。
  *   3. HARDWARE_SNOOP 在 refill 在途时到达：丢弃旧事务并自动重取，
  *      不能把 store 之前读到的数据安装进阵列或交付给流水线。
  *
  * Reg 与 SRAM 两种阵列都跑同一套激励；SRAM 的响应晚一拍由实现自行处理。
  */
class ysyx_26030103_ICacheSnoopTest extends AnyFlatSpec with Matchers {
  private val AddrW     = 32
  private val BlockLog2 = 4 // 16B/line
  private val IndexBits = 2 // 4 组

  // 两个不同 set 的可执行可缓存行（index bits=[5:4]：A=0，B=1）。
  private val LineA = 0x80000100L
  private val LineB = 0x80000110L

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

  /** 行内初始数据；测试可在 store 后覆盖具体 word。 */
  private class Memory {
    private val words = mutable.Map.empty[Long, Long]
    def read(address: Long): Long =
      words.getOrElse(address & ~3L, ((address & ~3L) ^ 0x24680000L) & 0xffffffffL)
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

  private def create(kind: String, snoop: Boolean): ysyx_26030103_ICacheBase =
    kind match {
      case "reg" =>
        new ysyx_26030103_ICacheReg(
          Enable = true,
          BlockSizeLog2 = BlockLog2,
          IndexBits = IndexBits,
          AddressWidth = AddrW,
          Snoop = snoop,
          PMARegions = Pma
        )
      case "sram" =>
        new ysyx_26030103_ICacheSRAM(
          Enable = true,
          BlockSizeLog2 = BlockLog2,
          IndexBits = IndexBits,
          AddressWidth = AddrW,
          Snoop = snoop,
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

  private def refill(
      io:    ysyx_26030103_ICacheInterface,
      clock: Clock,
      line:  Long,
      mem:   Memory,
      label: String
  ): Unit = {
    waitUntil(clock, bool(io.axi.AR.ARVALID), s"$label 没有 refill AR")
    withClue(s"$label refill 地址错误") { long(io.axi.AR.ARADDR) should be(line) }
    val beats = long(io.axi.AR.ARLEN).toInt + 1
    io.axi.AR.ARREADY.poke(true.B)
    clock.step()
    io.axi.AR.ARREADY.poke(false.B)
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
    // 命中路径不应产生 refill AR；多等几拍同时覆盖 SRAM 晚一拍响应。
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
      mem:   Memory,
      label: String
  ): Unit = {
    issueFetch(io, clock, addr)
    refill(io, clock, addr & ~((1L << BlockLog2) - 1L), mem, label)
    expectResponse(io, clock, mem.read(addr), label)
  }

  private def pulseSnoopRange(
      io:    ysyx_26030103_ICacheInterface,
      clock: Clock,
      lo:    Long,
      hi:    Long
  ): Unit = {
    io.snoop_addr.poke(lo.U(AddrW.W))
    io.snoop_addr_hi.poke(hi.U(AddrW.W))
    io.snoop_valid.poke(true.B)
    clock.step()
    io.snoop_valid.poke(false.B)
  }

  private def pulseSnoop(
      io:    ysyx_26030103_ICacheInterface,
      clock: Clock,
      addr:  Long
  ): Unit = pulseSnoopRange(io, clock, addr, addr + 3L)

  private def runFenceIPath(kind: String): Unit = {
    simulate(create(kind, snoop = false)) { dut =>
      val io = dut.io
      val clock = dut.clock
      resetDut(io, clock, dut.reset)
      val mem = new Memory

      fetchExpectMiss(io, clock, LineA, mem, s"$kind FENCE_I 初次取指")
      mem.write(LineA, 0x22222213L)
      pulseSnoop(io, clock, LineA) // FENCE_I 模式下 snoop 硬件不存在，必须无影响

      // 没有 fence.i：旧指令仍应命中，这是标准语义而非缺陷。
      fetchExpectHit(io, clock, LineA, ((LineA ^ 0x24680000L) & 0xffffffffL),
        s"$kind FENCE_I store 后未 fence.i")

      io.flush.poke(true.B)
      clock.step()
      io.flush.poke(false.B)

      fetchExpectMiss(io, clock, LineA, mem, s"$kind FENCE_I fence.i 后")
    }
  }

  private def runSnoopPath(kind: String): Unit = {
    simulate(create(kind, snoop = true)) { dut =>
      val io = dut.io
      val clock = dut.clock
      resetDut(io, clock, dut.reset)
      val mem = new Memory

      fetchExpectMiss(io, clock, LineA, mem, s"$kind SNOOP 填充 A")
      fetchExpectMiss(io, clock, LineB, mem, s"$kind SNOOP 填充 B")

      mem.write(LineA, 0x33333313L)
      pulseSnoop(io, clock, LineA)

      // 只失效 A；B 不在 store 行上，必须继续命中。
      fetchExpectHit(io, clock, LineB, mem.read(LineB), s"$kind SNOOP B 不应失效")
      // A 必须 miss 并重新读取到新指令；无需 fence.i。
      fetchExpectMiss(io, clock, LineA, mem, s"$kind SNOOP A 失效后")
    }
  }

  private def runCrossLineSnoopPath(kind: String): Unit = {
    simulate(create(kind, snoop = true)) { dut =>
      val io = dut.io
      val clock = dut.clock
      resetDut(io, clock, dut.reset)
      val mem = new Memory

      fetchExpectMiss(io, clock, LineA, mem, s"$kind cross-line 填充 A")
      fetchExpectMiss(io, clock, LineB, mem, s"$kind cross-line 填充 B")

      // 模拟一次跨越两行的 store：snoop 的 [addr, addr_hi] 必须同时失效
      // 两个物理行，不能只处理首字节所在行。
      pulseSnoopRange(io, clock, LineA, LineB)
      fetchExpectMiss(io, clock, LineA, mem, s"$kind cross-line A 失效")
      fetchExpectMiss(io, clock, LineB, mem, s"$kind cross-line B 失效")
    }
  }

  private def runInflightSnoopPath(kind: String): Unit = {
    simulate(create(kind, snoop = true)) { dut =>
      val io = dut.io
      val clock = dut.clock
      resetDut(io, clock, dut.reset)
      val mem = new Memory

      fetchExpectMiss(io, clock, LineA, mem, s"$kind in-flight initial-fill")
      val oldWord = mem.read(LineA)
      // 先失效 A，让下一次取指真正发起 refill。refill 读到的仍是 store
      // 之前的旧数据；在 burst 中途再模拟 store 落盘并发出 snoop。
      pulseSnoop(io, clock, LineA)

      issueFetch(io, clock, LineA)
      waitUntil(clock, bool(io.axi.AR.ARVALID), s"$kind in-flight 没有 AR")
      val beats = long(io.axi.AR.ARLEN).toInt + 1
      io.axi.AR.ARREADY.poke(true.B)
      clock.step()
      io.axi.AR.ARREADY.poke(false.B)

      // 先回一个旧 word，让 early restart 可能已经产生旧响应；此时 snoop
      // 必须在消费前撤回它，并丢弃整笔 refill。
      io.axi.R.RDATA.poke(oldWord.U(32.W))
      io.axi.R.RRESP.poke(0.U(2.W))
      io.axi.R.RLAST.poke((beats == 1).B)
      io.axi.R.RVALID.poke(true.B)
      clock.step()
      io.axi.R.RVALID.poke(false.B)
      // store 数据已落盘、B 响应完成；此时 refill 已读到第一个旧 word。
      mem.write(LineA, 0x44444413L)
      // 保证 snoop 拍没有残留 R beat，否则 RREADY 恒高会被误收成下一拍。
      pulseSnoop(io, clock, LineA)

      // 排空旧 burst 的剩余拍；这些数据必须被 discard。
      for (beat <- 1 until beats) {
        io.axi.R.RDATA.poke(0xdeadbeefL.U(32.W))
        io.axi.R.RRESP.poke(0.U(2.W))
        io.axi.R.RLAST.poke((beat + 1 == beats).B)
        io.axi.R.RVALID.poke(true.B)
        clock.step()
      }
      io.axi.R.RVALID.poke(false.B)
      io.axi.R.RLAST.poke(false.B)

      // 自动重取必须再次发出同一行 AR，并返回 store 后的新指令。
      refill(io, clock, LineA, mem, s"$kind in-flight replay")
      expectResponse(io, clock, 0x44444413L, s"$kind in-flight snoop 后")
    }
  }

  for (kind <- Seq("reg", "sram")) {
    s"ICache($kind)" should "FENCE_I 模式下 store 后必须显式 fence.i" in {
      runFenceIPath(kind)
    }
    it should "HARDWARE_SNOOP 只失效 store 对应行且无需 fence.i" in {
      runSnoopPath(kind)
    }
    it should "HARDWARE_SNOOP 覆盖跨行 store 的最后一个字节" in {
      runCrossLineSnoopPath(kind)
    }
    it should "HARDWARE_SNOOP 在 refill 在途时丢弃旧事务并重取" in {
      runInflightSnoopPath(kind)
    }
  }
}
