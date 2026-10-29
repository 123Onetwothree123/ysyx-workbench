package ysyx_26030103.audit

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.ysyx_26030103.mmu._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** PTW 页表总线故障的 cause 审计。
  *
  * 页表读/写返回总线错误时，架构上应报告对应访问类型的 access fault：
  * 取指=1，load=5，store=7。当前独立 PTW 和流水 PTW 都把总线错误统一
  * 当作 page fault（12/13/15），会误导 trap 处理程序去修页表权限。
  */
class ysyx_26030103_PTWBusFaultAuditTest
    extends AnyFlatSpec with Matchers {

  private def bool(v: Bool): Boolean = v.peek().litValue == 1
  private def long(v: UInt): Long = v.peek().litValue.toLong

  private val RootPPN = 0x80001L
  private val SatpSv32 = (BigInt(1) << 31) | BigInt(RootPPN)

  /** 所有页表读都返回总线错误的单事务模型。 */
  private class FaultBus {
    var pending = false
    var reads = 0

    def tick(dut: ysyx_26030103_PTW, clock: Clock): Unit = {
      dut.io.bus.resp.valid.poke(pending.B)
      dut.io.bus.resp.bits.id.poke(0.U(4.W))
      dut.io.bus.resp.bits.data.poke(0.U(32.W))
      dut.io.bus.resp.bits.fault.poke(true.B)
      val respFire = pending && bool(dut.io.bus.resp.ready)
      val reqFire = bool(dut.io.bus.req.valid) && bool(dut.io.bus.req.ready)
      clock.step()
      if (respFire) pending = false
      if (reqFire) {
        reads += 1
        pending = true
      }
    }
  }

  private def runIndependent(access: UInt, expectedCause: Long): Unit = {
    simulate(new ysyx_26030103_PTW(
      adMode = "ignore",
      levels = 1,
      tableEntries = 1024,
      pageBits = 12,
      XLEN = 32,
      addressWidth = 32,
      vaBits = 22
    )) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.bus.req.ready.poke(true.B)
      dut.io.bus.resp.valid.poke(false.B)
      dut.io.bus.resp.bits.id.poke(0.U)
      dut.io.bus.resp.bits.data.poke(0.U)
      dut.io.bus.resp.bits.fault.poke(false.B)
      dut.io.flush.poke(false.B)
      dut.io.resp.ready.poke(true.B)
      dut.io.req.valid.poke(true.B)
      dut.io.req.bits.id.poke(0.U)
      dut.io.req.bits.vaddr.poke(0.U(32.W))
      dut.io.req.bits.satp.poke(SatpSv32.U(32.W))
      dut.io.req.bits.privilege.poke(3.U)
      dut.io.req.bits.access.poke(access)
      dut.io.req.bits.sum.poke(false.B)
      dut.io.req.bits.mxr.poke(false.B)

      val bus = new FaultBus
      var done = false
      var cycles = 0
      while (!done && cycles < 100) {
        bus.tick(dut, dut.clock)
        if (bool(dut.io.resp.valid)) done = true
        cycles += 1
      }
      withClue("PTW did not respond: ") { done should be(true) }
      withClue("页表总线故障必须报告 fault: ") {
        bool(dut.io.resp.bits.fault) should be(true)
      }
      withClue(
        s"access=${access.litValue} 的页表总线故障 cause 应为 $expectedCause: ") {
        long(dut.io.resp.bits.cause) should be(expectedCause)
      }
    }
  }

  "independent PTW" should "把页表总线故障报告为对应访问的 access fault" in {
    runIndependent(ysyx_26030103_AccessType.Fetch, 1L)
    runIndependent(ysyx_26030103_AccessType.Load, 5L)
    runIndependent(ysyx_26030103_AccessType.Store, 7L)
  }

  private def runPipeline(access: UInt, expectedCause: Long): Unit = {
    simulate(new ysyx_26030103_PTWPipeline(
      outstanding = 1,
      levels = 1,
      tableEntries = 1024,
      pageBits = 12,
      XLEN = 32,
      addressWidth = 32,
      vaBits = 22,
      inOrder = true
    )) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.flush.poke(false.B)
      dut.io.req.valid.poke(true.B)
      dut.io.req.bits.id.poke(0.U)
      dut.io.req.bits.vaddr.poke(0.U(32.W))
      dut.io.req.bits.satp.poke(SatpSv32.U(32.W))
      dut.io.req.bits.privilege.poke(3.U)
      dut.io.req.bits.access.poke(access)
      dut.io.req.bits.sum.poke(false.B)
      dut.io.req.bits.mxr.poke(false.B)
      dut.io.bus.req.ready.poke(true.B)
      // 页表总线永远返回错误响应；PTW 只在等待槽位时接收。
      dut.io.bus.resp.valid.poke(true.B)
      dut.io.bus.resp.bits.id.poke(0.U)
      dut.io.bus.resp.bits.data.poke(0.U)
      dut.io.bus.resp.bits.fault.poke(true.B)
      dut.io.resp(0).ready.poke(true.B)

      var done = false
      var cycles = 0
      while (!done && cycles < 100) {
        dut.clock.step()
        if (bool(dut.io.resp(0).valid)) done = true
        cycles += 1
      }
      withClue("PTWPipeline did not respond: ") { done should be(true) }
      withClue("流水 PTW 页表总线故障必须报告 fault: ") {
        bool(dut.io.resp(0).bits.fault) should be(true)
      }
      withClue(
        s"access=${access.litValue} 的流水 PTW 总线故障 cause 应为 $expectedCause: ") {
        long(dut.io.resp(0).bits.cause) should be(expectedCause)
      }
    }
  }

  "PTWPipeline" should "把页表总线故障报告为对应访问的 access fault" in {
    runPipeline(ysyx_26030103_AccessType.Fetch, 1L)
    runPipeline(ysyx_26030103_AccessType.Load, 5L)
    runPipeline(ysyx_26030103_AccessType.Store, 7L)
  }
}
