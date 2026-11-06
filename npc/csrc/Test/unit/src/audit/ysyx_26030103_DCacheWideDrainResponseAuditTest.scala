package ysyx_26030103.audit

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.ysyx_26030103.cache._
import _root_.ysyx_26030103.common.ysyx_26030103_PMARegion
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** DCache 非缓存 8 字节读的响应完整性，以及 SDrain 超时的 Decoupled
  * payload 稳定性。
  *
  * 回归点：
  *   1. 第二拍缺少 RLAST 时，demand 必须在进入 SDrain 前得到唯一的
  *      fault 响应；迟到的 RLAST 只负责吸拍，不能留下零响应或第二个响应；
  *   2. SDrain 超时时若已经存在可见响应（尤其是成功数据），下游反压期间
  *      data/fault/FaultResp 必须保持不变，超时只能填充空响应槽。
  *
  * Reg 与 SRAM 两种实现共享同一套接口和激励。
  */
class ysyx_26030103_DCacheWideDrainResponseAuditTest
    extends AnyFlatSpec with Matchers {
  private val AddrW = 32
  private val Region = ysyx_26030103_PMARegion(
    Base = 0x80000000L,
    Size = 0x10000000L,
    Cacheable = true,
    Executable = true
  )

  private def bool(value: Bool): Boolean = value.peek().litValue == 1
  private def long(value: UInt): Long = value.peek().litValue.toLong

  private def newDut(kind: String): ysyx_26030103_DCacheBase =
    kind match {
      case "Reg" =>
        new ysyx_26030103_DCacheReg(
          Enable = true,
          BlockSizeLog2 = 4,
          IndexBits = 4,
          AddressWidth = AddrW,
          DataWidth = 64,
          PMARegions = Seq(Region)
        )
      case "SRAM" =>
        new ysyx_26030103_DCacheSRAM(
          Enable = true,
          BlockSizeLog2 = 4,
          IndexBits = 4,
          AddressWidth = AddrW,
          DataWidth = 64,
          PMARegions = Seq(Region)
        )
    }

  private def waitUntil(
      clock: Clock,
      cond: => Boolean,
      message: String,
      limit: Int = 200
  ): Unit = {
    var cycles = 0
    while (!cond && cycles < limit) {
      clock.step()
      cycles += 1
    }
    withClue(s"$message（等待 $cycles 拍）") { cond should be(true) }
  }

  private def resetDut(
      io: ysyx_26030103_DCacheInterface,
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
    io.req_paddr_valid.poke(true.B)
    io.req_paddr_vaddr.poke(0.U(AddrW.W))
    io.req_kill.poke(false.B)
    io.resp.ready.poke(false.B)
    io.StoreValid.poke(false.B)
    io.StoreResp.poke(0.U(2.W))
    io.StoreAddr.poke(0.U(AddrW.W))
    io.StoreVAddr.poke(0.U(AddrW.W))
    io.StoreData.poke(0.U(64.W))
    io.StoreStrb.poke(0.U(8.W))
    io.flush.poke(false.B)
    io.dma_snoop_valid.poke(false.B)
    io.dma_snoop_start.poke(0.U(AddrW.W))
    io.dma_snoop_end.poke(0.U(AddrW.W))
    io.AXI.AR.ARREADY.poke(false.B)
    io.AXI.R.RVALID.poke(false.B)
    io.AXI.R.RID.poke(0.U(4.W))
    io.AXI.R.RDATA.poke(0.U(32.W))
    io.AXI.R.RRESP.poke(0.U(2.W))
    io.AXI.R.RLAST.poke(false.B)
  }

  private def issueLoad(
      io: ysyx_26030103_DCacheInterface,
      clock: Clock,
      address: Long,
      width: Int
  ): Unit = {
    io.req.bits.addr.poke(address.U(AddrW.W))
    io.req.bits.vaddr.poke(address.U(AddrW.W))
    io.req.bits.WidthSelect.poke(width.U(2.W))
    io.req.bits.signed.poke(false.B)
    io.req_paddr_valid.poke(true.B)
    io.req_paddr_vaddr.poke(address.U(AddrW.W))
    io.req.valid.poke(true.B)
    waitUntil(clock, bool(io.req.ready), "DCache 未受理 load")
    clock.step()
    io.req.valid.poke(false.B)
  }

  private def acceptAr(
      io: ysyx_26030103_DCacheInterface,
      clock: Clock,
      address: Long,
      length: Long
  ): Unit = {
    waitUntil(clock, bool(io.AXI.AR.ARVALID), "DCache 未发出 AR")
    long(io.AXI.AR.ARADDR) should be(address)
    long(io.AXI.AR.ARLEN) should be(length)
    io.AXI.AR.ARREADY.poke(true.B)
    clock.step()
    io.AXI.AR.ARREADY.poke(false.B)
  }

  private def sendBeat(
      io: ysyx_26030103_DCacheInterface,
      clock: Clock,
      data: Long,
      last: Boolean,
      resp: Long = 0L
  ): Unit = {
    io.AXI.R.RVALID.poke(true.B)
    io.AXI.R.RDATA.poke(data.U(32.W))
    io.AXI.R.RRESP.poke(resp.U(2.W))
    io.AXI.R.RLAST.poke(last.B)
    clock.step()
    io.AXI.R.RVALID.poke(false.B)
    io.AXI.R.RRESP.poke(0.U(2.W))
    io.AXI.R.RLAST.poke(false.B)
  }

  private def consumeResponse(
      io: ysyx_26030103_DCacheInterface,
      clock: Clock
  ): Unit = {
    withClue("响应在消费前不应消失: ") { bool(io.resp.valid) should be(true) }
    io.resp.ready.poke(true.B)
    clock.step()
    io.resp.ready.poke(false.B)
  }

  private def runWideMissingLast(dut: ysyx_26030103_DCacheBase): Unit = {
    val io = dut.io
    val clock = dut.clock
    resetDut(io, clock, dut.reset)

    // 0x0000_0000 不在可缓存 PMA 窗口内：8 字节读拆成两拍窄传输。
    issueLoad(io, clock, 0x00000000L, width = 3)
    acceptAr(io, clock, address = 0x00000000L, length = 1L)

    // 两拍都已返回，但第二拍漏掉 RLAST。
    sendBeat(io, clock, 0x11223344L, last = false)
    sendBeat(io, clock, 0x55667788L, last = false)

    // 必须已经形成唯一的 fault 响应，而不是等迟到的 RLAST。
    waitUntil(clock, bool(io.resp.valid), "第二拍缺少 RLAST 时没有 fault 响应")
    bool(io.resp.bits.fault) should be(true)
    long(io.resp.bits.FaultResp) should be(2L)
    consumeResponse(io, clock)

    // 迟到的 RLAST 只用于同步总线；不得再产生第二个响应。
    sendBeat(io, clock, 0x99aabbccL, last = true)
    waitUntil(clock, bool(io.req.ready), "迟到 RLAST 后 SDrain 未恢复")
    for (_ <- 0 until 8) {
      bool(io.resp.valid) should be(false)
      clock.step()
    }
  }

  private def runDrainTimeoutPayloadStable(
      dut: ysyx_26030103_DCacheBase
  ): Unit = {
    val io = dut.io
    val clock = dut.clock
    resetDut(io, clock, dut.reset)

    // 可缓存 miss：关键词 beat 成功后，最后一拍故意不给 RLAST，使 DCache
    // 带着一个已经可见的成功响应进入 SDrain。
    issueLoad(io, clock, 0x80000000L, width = 2)
    acceptAr(io, clock, address = 0x80000000L, length = 3L)
    val keyword = 0x89abcdefL
    sendBeat(io, clock, keyword, last = false)
    sendBeat(io, clock, 0x11111111L, last = false)
    sendBeat(io, clock, 0x22222222L, last = false)
    sendBeat(io, clock, 0x33333333L, last = false)

    waitUntil(clock, bool(io.resp.valid), "关键词响应没有出现")
    bool(io.resp.bits.fault) should be(false)
    long(io.resp.bits.FaultResp) should be(0L)
    long(io.resp.bits.data) should be(keyword)

    // 下游一直不 ready，跨过 SDrain 超时窗口。响应 payload 必须保持不变，
    // 超时只能影响状态机/缓存行，不能把成功响应改写成 fault。
    for (_ <- 0 until 300) {
      bool(io.resp.valid) should be(true)
      bool(io.resp.bits.fault) should be(false)
      long(io.resp.bits.FaultResp) should be(0L)
      long(io.resp.bits.data) should be(keyword)
      clock.step()
    }

    // 超时恢复后消费这个成功响应，且只能消费到一次。
    consumeResponse(io, clock)
    clock.step()
    bool(io.resp.valid) should be(false)
  }

  "DCache" should "在非缓存 8 字节读第二拍缺少 RLAST 时返回唯一 fault" in {
    for (kind <- Seq("Reg", "SRAM")) {
      withClue(s"$kind: ") {
        simulate(newDut(kind)) { dut =>
          runWideMissingLast(dut)
        }
      }
    }
  }

  it should "在 SDrain 超时时保持已可见成功响应的 payload 稳定" in {
    for (kind <- Seq("Reg", "SRAM")) {
      withClue(s"$kind: ") {
        simulate(newDut(kind)) { dut =>
          runDrainTimeoutPayloadStable(dut)
        }
      }
    }
  }
}
