package ysyx_26030103.cache

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** ICache VIPT 并行路径的定向测试。
  *
  * 重点验证三件事：
  *   1. VA 早于 PA 到达时，ICache 先受理并用 VA 选 index，绝不发错 AR；
  *   2. 最终命中/缺失由 PA tag 决定，而组索引来自 VA（非恒等映射）；
  *   3. 旧 PA/旧请求的响应不会被新的 PC 使用，翻译故障/重定向能取消请求。
  */
class ysyx_26030103_ICacheViptTest extends AnyFlatSpec with Matchers {
  private val AddrW = 32
  private val BlockLog2 = 4 // 16B/line
  private val IndexBits = 2 // 4 组

  // VA 行基址：0x1050 / 0x10d0 的 index 都是 1；两者映射到 PA 时
  // 的组索引却是 0（0x80002000/0x80003000）。只有用 VA 选 index 才能命中。
  private val VaA = 0x00001050L
  private val VaB = 0x000010d0L
  private val VaC = 0x00002050L
  private val PaA = 0x80002000L
  private val PaB = 0x80003000L
  private val PaC = 0x80004000L

  private def bool(value: Bool): Boolean = value.peek().litValue == 1
  private def long(value: UInt): Long = value.peek().litValue.toLong

  private def wordOf(line: Long, beat: Int): Long =
    ((line + beat.toLong * 4L) ^ 0x5a5a0000L) & 0xffffffffL

  private def waitUntil(clock: Clock, cond: => Boolean, message: String): Unit = {
    var cycles = 0
    while (!cond && cycles < 500) {
      clock.step()
      cycles += 1
    }
    withClue(s"$message（等待 $cycles 拍）") { cond should be(true) }
  }

  private def refillLine(
      io: ysyx_26030103_ICacheInterface,
      clock: Clock,
      expectedLine: Long,
      label: String = ""
  ): Seq[Long] = {
    waitUntil(
      clock,
      bool(io.axi.AR.ARVALID),
      s"$label ICache 未发出 refill AR（期望行 0x${expectedLine.toHexString}）"
    )
    withClue("refill AR 地址错误") {
      long(io.axi.AR.ARADDR) should be(expectedLine)
    }
    val beats = long(io.axi.AR.ARLEN).toInt + 1
    val words = (0 until beats).map(beat => wordOf(expectedLine, beat))
    io.axi.AR.ARREADY.poke(true.B)
    clock.step()
    io.axi.AR.ARREADY.poke(false.B)
    for (beat <- 0 until beats) {
      io.axi.R.RDATA.poke(words(beat).U(32.W))
      io.axi.R.RRESP.poke(0.U(2.W))
      io.axi.R.RLAST.poke((beat + 1 == beats).B)
      io.axi.R.RVALID.poke(true.B)
      clock.step()
    }
    io.axi.R.RVALID.poke(false.B)
    io.axi.R.RLAST.poke(false.B)
    words
  }

  private def consumeResponse(
      io: ysyx_26030103_ICacheInterface,
      clock: Clock
  ): Unit = {
    io.resp_ready.poke(true.B)
    clock.step()
    io.resp_ready.poke(false.B)
  }

  private def expectResponse(
      io: ysyx_26030103_ICacheInterface,
      clock: Clock,
      vaddr: Long,
      data: Long
  ): Unit = {
    waitUntil(clock, bool(io.resp_valid), s"VA=0x${vaddr.toHexString} 没有响应")
    withClue("响应 PC 不是受理时的 VA") {
      long(io.resp_addr) should be(vaddr)
    }
    withClue("响应数据错误") { long(io.resp_data) should be(data) }
    bool(io.resp_fault) should be(false)
    consumeResponse(io, clock)
  }

  private def enterFetch(
      io: ysyx_26030103_ICacheInterface,
      clock: Clock,
      vaddr: Long,
      paddr: Long,
      paddrValid: Boolean,
      paddrVaddr: Long
  ): Unit = {
    io.fetch_vaddr.poke(vaddr.U(AddrW.W))
    io.fetch_addr.poke(paddr.U(AddrW.W))
    io.fetch_paddr_vaddr.poke(paddrVaddr.U(AddrW.W))
    io.fetch_paddr_valid.poke(paddrValid.B)
    io.fetch_valid.poke(true.B)
    waitUntil(clock, bool(io.fetch_ready), "ICache 未受理取指")
    clock.step()
    io.fetch_valid.poke(false.B)
  }

  private def resetDut(
      io: ysyx_26030103_ICacheInterface,
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

  private def runVipt(
      io: ysyx_26030103_ICacheInterface,
      clock: Clock,
      reset: Reset
  ): Unit = {
    resetDut(io, clock, reset)

    // --- 1) PA 未到：先受理 VA，仍不能用 VA 发 AR ---
    enterFetch(io, clock, VaA, 0L, paddrValid = false, paddrVaddr = VaA)
    // 模拟 IFU 已经推进到下一 PC；ICache 应继续持有受理时的 VA_A。
    io.fetch_vaddr.poke(VaB.U(AddrW.W))
    io.fetch_valid.poke(true.B)
    clock.step(3)
    bool(io.fetch_xlate_pending) should be(true)
    long(io.fetch_xlate_vaddr) should be(VaA)
    bool(io.axi.AR.ARVALID) should be(false)
    // 本测试随后显式发下一笔请求，先撤销 IFU 的持续 FetchValid，避免
    // 响应消费同拍自动受理下一笔。
    io.fetch_valid.poke(false.B)

    // PA_A 带正确 VA 标签到达，按 VA index=1、PA tag 安装。
    io.fetch_addr.poke(PaA.U(AddrW.W))
    io.fetch_paddr_vaddr.poke(VaA.U(AddrW.W))
    io.fetch_paddr_valid.poke(true.B)
    val lineA = refillLine(io, clock, PaA, "lineA")
    expectResponse(io, clock, VaA, lineA(0))

    // --- 2) 非恒等映射：VA_B 的 index 与 VA_A 相同，PA 相同 => 命中。
    // 若错误地用 PA 选 index，会访问组 0 而 miss。
    io.fetch_vaddr.poke(VaB.U(AddrW.W))
    io.fetch_addr.poke(PaA.U(AddrW.W))
    io.fetch_paddr_vaddr.poke(VaB.U(AddrW.W))
    io.fetch_paddr_valid.poke(true.B)
    io.fetch_valid.poke(true.B)
    waitUntil(clock, bool(io.fetch_ready), "VA_B 未被受理")
    clock.step()
    io.fetch_valid.poke(false.B)
    bool(io.axi.AR.ARVALID) should be(false)
    expectResponse(io, clock, VaB, lineA(0))

    // --- 3) VA 不变、PA tag 改变 => 必须 miss 并 refill 到新 PA ---
    enterFetch(io, clock, VaA, PaB, paddrValid = true, paddrVaddr = VaA)
    val lineB = refillLine(io, clock, PaB, "lineB")
    expectResponse(io, clock, VaA, lineB(0))

    // 同一 PA_B 换一个 VA_B（同 index）仍应命中。
    enterFetch(io, clock, VaB, PaB, paddrValid = true, paddrVaddr = VaB)
    bool(io.axi.AR.ARVALID) should be(false)
    expectResponse(io, clock, VaB, lineB(0))

    // --- 4) 旧 PA 标签不能用于新 VA ---
    // 先受理 VA_C 且不提供 PA，随后把仍挂在 PA_A 上的旧标签摆到端口上。
    enterFetch(io, clock, VaC, 0L, paddrValid = false, paddrVaddr = VaC)
    io.fetch_addr.poke(PaA.U(AddrW.W))
    io.fetch_paddr_vaddr.poke(VaA.U(AddrW.W)) // 旧标签
    io.fetch_paddr_valid.poke(true.B)
    clock.step(3)
    bool(io.fetch_xlate_pending) should be(true)
    long(io.fetch_xlate_vaddr) should be(VaC)
    bool(io.axi.AR.ARVALID) should be(false)

    // 正确标签到达后才允许 refill；响应仍属于 VA_C。
    io.fetch_addr.poke(PaC.U(AddrW.W))
    io.fetch_paddr_vaddr.poke(VaC.U(AddrW.W))
    val lineC = refillLine(io, clock, PaC, "lineC")
    expectResponse(io, clock, VaC, lineC(0))

    // --- 5) kill 取消等待翻译的请求，不能留下响应/总线事务 ---
    enterFetch(io, clock, VaA, 0L, paddrValid = false, paddrVaddr = VaA)
    io.kill.poke(true.B)
    clock.step()
    io.kill.poke(false.B)
    clock.step(3)
    bool(io.resp_valid) should be(false)
    bool(io.axi.AR.ARVALID) should be(false)
    // kill 后新请求仍能正常命中/响应。
    enterFetch(io, clock, VaB, PaC, paddrValid = true, paddrVaddr = VaB)
    expectResponse(io, clock, VaB, lineC(0))
  }

  private def newReg(): ysyx_26030103_ICacheReg =
    new ysyx_26030103_ICacheReg(
      Enable = true,
      BlockSizeLog2 = BlockLog2,
      IndexBits = IndexBits,
      AddressWidth = AddrW,
      Vipt = true
    )

  private def newSram(): ysyx_26030103_ICacheSRAM =
    new ysyx_26030103_ICacheSRAM(
      Enable = true,
      BlockSizeLog2 = BlockLog2,
      IndexBits = IndexBits,
      AddressWidth = AddrW,
      Vipt = true
    )

  private def newSramNoBypass(): ysyx_26030103_ICacheSRAM =
    new ysyx_26030103_ICacheSRAM(
      Enable = true,
      BlockSizeLog2 = BlockLog2,
      IndexBits = IndexBits,
      AddressWidth = AddrW,
      Vipt = true,
      Bypass = false
    )

  "ICacheReg VIPT" should "用 VA 选 index、PA 做 tag，并丢弃旧 VA 的响应" in {
    simulate(newReg()) { dut => runVipt(dut.io, dut.clock, dut.reset) }
  }

  "ICacheSRAM VIPT" should "用 VA 选 index、PA 做 tag，并丢弃旧 VA 的响应" in {
    simulate(newSram()) { dut => runVipt(dut.io, dut.clock, dut.reset) }
  }

  "ICacheSRAM VIPT (NoBypass)" should "在数据晚一拍时仍保持 VIPT 行为" in {
    simulate(newSramNoBypass()) { dut =>
      runVipt(dut.io, dut.clock, dut.reset)
    }
  }
}
