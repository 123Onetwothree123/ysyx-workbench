package ysyx_26030103.cache

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** DCache VIPT 并行路径的定向测试。
  *
  * 验证：
  *   1. PA 未到达时请求已被受理，VA 先选 index，但绝不发 AR/返回响应； 2. PA 带正确 VA 标签到达后才做 tag/PMA/refill，非恒等映射仍按 VA 组命中； 3. 旧 VA 标签的 PA
  *      不能用于新请求； 4. MMU 翻译故障（req_kill）取消等待请求，且不留下响应/总线事务。
  */
class ysyx_26030103_DCacheViptTest extends AnyFlatSpec with Matchers {
  private val AddrW     = 32
  private val BlockLog2 = 4 // 16B/line
  private val IndexBits = 2 // 4 组

  // VA 的组索引 = (VA >> 4) & 3。VaA/VaB 同组(1)，VaC 在组 3；
  // 对应 PA 的组索引却是 0，只有用 VA 选 index 才能得到正确行为。
  private val VaA = 0x00001050L
  private val VaB = 0x000010d0L
  private val VaC = 0x00001030L
  private val PaA = 0x80002000L
  private val PaB = 0x80003000L
  private val PaC = 0x80004000L

  private def bool(value: Bool): Boolean = value.peek().litValue == 1
  private def long(value: UInt): Long    = value.peek().litValue.toLong

  private def wordOf(line: Long, beat: Int): Long =
    ((line + beat.toLong * 4L) ^ 0x3c3c0000L) & 0xffffffffL

  private def waitUntil(clock: Clock, cond: => Boolean, message: String): Unit = {
    var cycles = 0
    while (!cond && cycles < 500) {
      clock.step()
      cycles += 1
    }
    withClue(s"$message（等待 $cycles 拍）") { cond should be(true) }
  }

  private def refillLine(
    io:           ysyx_26030103_DCacheInterface,
    clock:        Clock,
    expectedLine: Long,
    label:        String = ""
  ): Seq[Long] = {
    waitUntil(
      clock,
      bool(io.AXI.AR.ARVALID),
      s"$label DCache 未发出 refill AR（期望行 0x${expectedLine.toHexString}）"
    )
    withClue("refill AR 地址错误") {
      long(io.AXI.AR.ARADDR) should be(expectedLine)
    }
    val beats = long(io.AXI.AR.ARLEN).toInt + 1
    beats should be(4)
    val words = (0 until beats).map(beat => wordOf(expectedLine, beat))
    io.AXI.AR.ARREADY.poke(true.B)
    clock.step()
    io.AXI.AR.ARREADY.poke(false.B)
    for (beat <- 0 until beats) {
      io.AXI.R.RDATA.poke(words(beat).U(32.W))
      io.AXI.R.RRESP.poke(0.U(2.W))
      io.AXI.R.RLAST.poke((beat + 1 == beats).B)
      io.AXI.R.RVALID.poke(true.B)
      clock.step()
    }
    io.AXI.R.RVALID.poke(false.B)
    io.AXI.R.RLAST.poke(false.B)
    words
  }

  private def consumeResponse(
    io:    ysyx_26030103_DCacheInterface,
    clock: Clock
  ): Unit = {
    io.resp.ready.poke(true.B)
    clock.step()
    io.resp.ready.poke(false.B)
  }

  private def expectResponse(
    io:    ysyx_26030103_DCacheInterface,
    clock: Clock,
    data:  Long,
    label: String = ""
  ): Unit = {
    waitUntil(clock, bool(io.resp.valid), s"$label 没有 DCache 响应")
    withClue(s"$label 响应数据错误") { long(io.resp.bits.data) should be(data) }
    bool(io.resp.bits.fault) should be(false)
    consumeResponse(io, clock)
  }

  /** 发出一次 load 请求。paddrValid 为 true 时 PA 与 req 同拍有效。 */
  private def issueLoad(
    io:         ysyx_26030103_DCacheInterface,
    clock:      Clock,
    vaddr:      Long,
    paddr:      Long,
    paddrValid: Boolean,
    paddrVaddr: Long
  ): Unit = {
    io.req.bits.vaddr.poke(vaddr.U(AddrW.W))
    io.req.bits.addr.poke(paddr.U(AddrW.W))
    io.req.bits.WidthSelect.poke(2.U(2.W))
    io.req.bits.signed.poke(false.B)
    io.req_paddr_vaddr.poke(paddrVaddr.U(AddrW.W))
    io.req_paddr_valid.poke(paddrValid.B)
    io.req.valid.poke(true.B)
    waitUntil(clock, bool(io.req.ready), "DCache 未受理 load")
    clock.step()
    io.req.valid.poke(false.B)
  }

  /** 模拟 LSU 在 store 收到成功 BRESP 后发出的写回镜像事件。
    * StoreVAddr 负责选 index，StoreAddr 是翻译后的 PA，只参与 tag/PMA。
    */
  private def issueStore(
    io:    ysyx_26030103_DCacheInterface,
    clock: Clock,
    vaddr: Long,
    paddr: Long,
    data:  Long
  ): Unit = {
    io.StoreVAddr.poke(vaddr.U(AddrW.W))
    io.StoreAddr.poke(paddr.U(AddrW.W))
    io.StoreData.poke(data.U(32.W))
    io.StoreStrb.poke("hf".U(4.W))
    io.StoreResp.poke(0.U(2.W))
    io.StoreValid.poke(true.B)
    clock.step()
    io.StoreValid.poke(false.B)
    io.StoreStrb.poke(0.U(4.W))
  }

  private def resetDut(
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

  private def runVipt(
    io:    ysyx_26030103_DCacheInterface,
    clock: Clock,
    reset: Reset
  ): Unit = {
    resetDut(io, clock, reset)

    // --- 1) PA 未到：先受理，VA 选 index；不能发 AR/响应 ---
    issueLoad(io, clock, VaA, 0L, paddrValid = false, paddrVaddr = VaA)
    // 模拟 LSU 已经推进到下一笔请求，端口上出现新的 VA/旧 PA。
    io.req.bits.vaddr.poke(VaC.U(AddrW.W))
    io.req.bits.addr.poke(PaA.U(AddrW.W))
    io.req_paddr_vaddr.poke(VaC.U(AddrW.W))
    io.req_paddr_valid.poke(true.B)
    clock.step(5)
    bool(io.resp.valid) should be(false)
    bool(io.AXI.AR.ARVALID) should be(false)

    // 旧标签的 PA（属于 VA_A 以外）不能解析当前的 VA_A 等待请求。
    io.req.bits.addr.poke(PaB.U(AddrW.W))
    io.req_paddr_vaddr.poke(VaB.U(AddrW.W))
    io.req_paddr_valid.poke(true.B)
    clock.step(5)
    bool(io.resp.valid) should be(false)
    bool(io.AXI.AR.ARVALID) should be(false)

    // 正确的 PA_A + VA_A 标签到达，按 VA index=1、PA tag 回填。
    io.req.bits.addr.poke(PaA.U(AddrW.W))
    io.req_paddr_vaddr.poke(VaA.U(AddrW.W))
    val lineA = refillLine(io, clock, PaA, "lineA")
    expectResponse(io, clock, lineA(0), "lineA")

    // --- 1b) 延迟 PA 命中：lineA 已在阵列中，先受理 VA 等 PA，
    // PA 到达后应直接命中（SRAM 版验证 VA index 早读数据可用），不发 AR。
    issueLoad(io, clock, VaA, 0L, paddrValid = false, paddrVaddr = VaA)
    clock.step(3)
    bool(io.AXI.AR.ARVALID) should be(false)
    io.req.bits.addr.poke(PaA.U(AddrW.W))
    io.req_paddr_vaddr.poke(VaA.U(AddrW.W))
    io.req_paddr_valid.poke(true.B)
    expectResponse(io, clock, lineA(0), "delayed PA hit")
    bool(io.AXI.AR.ARVALID) should be(false)

    // --- 2) 非恒等映射：VA_B 与 VA_A 同组，PA_A 也相同 => 必须命中。
    // 若错误地用 PA 选 index（PA_A 组 0），就会 miss 并发 AR。
    issueLoad(io, clock, VaB, PaA, paddrValid = true, paddrVaddr = VaB)
    bool(io.AXI.AR.ARVALID) should be(false)
    expectResponse(io, clock, lineA(0), "VA_B hit")

    // --- 3) 同一 VA、不同 PA tag => 必须 miss 并回填新行 ---
    issueLoad(io, clock, VaA, PaB, paddrValid = true, paddrVaddr = VaA)
    val lineB = refillLine(io, clock, PaB, "lineB")
    expectResponse(io, clock, lineB(0), "lineB")
    // 同 PA_B 换 VA_B（同组）仍命中。
    issueLoad(io, clock, VaB, PaB, paddrValid = true, paddrVaddr = VaB)
    bool(io.AXI.AR.ARVALID) should be(false)
    expectResponse(io, clock, lineB(0), "VA_B hit lineB")

    // --- 3b) store 镜像：用 VA 选 index、PA 做 tag ---
    // PA_B 的组索引是 0，而 VA_B 的组索引是 1。只有按 VA 更新才能让
    // 随后的 VA_B load 命中新数据。
    issueStore(io, clock, VaB, PaB, 0x12345678L)
    issueLoad(io, clock, VaB, PaB, paddrValid = true, paddrVaddr = VaB)
    bool(io.AXI.AR.ARVALID) should be(false)
    expectResponse(io, clock, 0x12345678L, "store mirror by VA index")

    // 旧/错误 PA 标签不能更新当前 VA 对应的缓存行：StoreVAddr 仍选组 1，
    // 但 PA_A 的 tag 与组内 PA_B 行不匹配，应保持旧数据。
    issueStore(io, clock, VaB, PaA, 0xdeadbeefL)
    issueLoad(io, clock, VaB, PaB, paddrValid = true, paddrVaddr = VaB)
    bool(io.AXI.AR.ARVALID) should be(false)
    expectResponse(io, clock, 0x12345678L, "stale PA rejected")

    // --- 4) kill 取消等待 PA 的请求，不能留下响应/AR ---
    issueLoad(io, clock, VaC, 0L, paddrValid = false, paddrVaddr = VaC)
    clock.step(2)
    io.req_kill.poke(true.B)
    clock.step()
    io.req_kill.poke(false.B)
    clock.step(5)
    bool(io.resp.valid) should be(false)
    bool(io.AXI.AR.ARVALID) should be(false)
    // kill 后新请求仍能正常完成。
    issueLoad(io, clock, VaC, PaC, paddrValid = true, paddrVaddr = VaC)
    val lineC = refillLine(io, clock, PaC, "lineC")
    expectResponse(io, clock, lineC(0), "lineC")
  }

  private def newReg(): ysyx_26030103_DCacheReg =
    new ysyx_26030103_DCacheReg(
      Enable = true,
      BlockSizeLog2 = BlockLog2,
      IndexBits = IndexBits,
      AddressWidth = AddrW,
      Vipt = true
    )

  private def newSram(): ysyx_26030103_DCacheSRAM =
    new ysyx_26030103_DCacheSRAM(
      Enable = true,
      BlockSizeLog2 = BlockLog2,
      IndexBits = IndexBits,
      AddressWidth = AddrW,
      Vipt = true
    )

  private def newSramNoBypass(): ysyx_26030103_DCacheSRAM =
    new ysyx_26030103_DCacheSRAM(
      Enable = true,
      BlockSizeLog2 = BlockLog2,
      IndexBits = IndexBits,
      AddressWidth = AddrW,
      Vipt = true,
      Bypass = false
    )

  "DCacheReg VIPT" should "VA 选 index、PA tag 命中，并在 PA 未到时持有请求" in {
    simulate(newReg()) { dut => runVipt(dut.io, dut.clock, dut.reset) }
  }

  "DCacheSRAM VIPT" should "VA 早读、PA 后比较，并支持 kill 等待请求" in {
    simulate(newSram()) { dut => runVipt(dut.io, dut.clock, dut.reset) }
  }

  "DCacheSRAM VIPT (NoBypass)" should "数据晚一拍时仍保持 VIPT 行为" in {
    simulate(newSramNoBypass()) { dut =>
      runVipt(dut.io, dut.clock, dut.reset)
    }
  }
}
