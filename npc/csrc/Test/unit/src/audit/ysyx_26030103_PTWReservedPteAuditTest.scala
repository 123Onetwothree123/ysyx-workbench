package ysyx_26030103.audit

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.ysyx_26030103.config._
import _root_.ysyx_26030103.mmu._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** PTW 审计：R=0,W=1 的保留 PTE 必须产生 page fault。
  *
  * RISC-V 规定 R=0 且 W=1 是保留编码，任何访问都应 page fault。当前
  * `isLeaf` 只看 R|W|X 是否非零，`leafFaultOf` 对 store 只检查 W，
  * 因此会把 V|W 的 PTE 当作可写叶子放行。本测试直接驱动独立 PTW，
  * 用三级页表走到该叶子。
  */
class ysyx_26030103_PTWReservedPteAuditTest
    extends AnyFlatSpec with Matchers {

  private def bool(v: Bool): Boolean = v.peek().litValue == 1
  private def long(v: UInt): Long = v.peek().litValue.toLong

  /** 单 walker 的 32 位页表总线模型；8B PTE 的低/高字分别读。 */
  private class BusModel {
    private val mem = scala.collection.mutable.Map.empty[Long, BigInt]
    private var pending: Option[BigInt] = None
    var reads = 0

    def write(address: Long, word: BigInt): Unit =
      mem(address & 0xffffffffffffL) = word & 0xffffffffL

    def writePte(address: Long, pte: BigInt): Unit = {
      write(address, pte & 0xffffffffL)
      write(address + 4, (pte >> 32) & 0xffffffffL)
    }

    def tick(dut: ysyx_26030103_PTW, clock: Clock): Unit = {
      // 先把上一拍捕获的读响应送上总线。
      dut.io.bus.resp.valid.poke(pending.nonEmpty.B)
      dut.io.bus.resp.bits.id.poke(0.U(4.W))
      dut.io.bus.resp.bits.data.poke(
        pending.getOrElse(BigInt(0)).U(32.W))
      dut.io.bus.resp.bits.fault.poke(false.B)

      val respFire = pending.nonEmpty &&
        dut.io.bus.resp.ready.peek().litToBoolean
      val reqFire = dut.io.bus.req.valid.peek().litToBoolean &&
        dut.io.bus.req.ready.peek().litToBoolean
      val reqAddr =
        if (reqFire) long(dut.io.bus.req.bits.addr) else 0L
      val reqWrite = reqFire && bool(dut.io.bus.req.bits.write)
      val reqData =
        if (reqFire) dut.io.bus.req.bits.data.peek().litValue else BigInt(0)

      clock.step()

      if (respFire) { pending = None }
      if (reqFire) {
        if (reqWrite) {
          mem(reqAddr & 0xffffffffffffL) = reqData & 0xffffffffL
          pending = Some(BigInt(0))
        } else {
          reads += 1
          pending = Some(mem.getOrElse(reqAddr & 0xffffffffffffL, BigInt(0)))
        }
      }
    }
  }

  private def pte(ppn: Long, flags: Long): BigInt =
    (BigInt(ppn) << 10) | BigInt(flags)

  "PTW" should "对 V|W 的保留叶子 PTE 产生 page fault" in {
    val root = 0x80001000L
    val level1 = 0x80002000L
    val level2 = 0x80003000L
    val leafPpn = 0x80004L
    val bus = new BusModel
    bus.writePte(root, pte(0x80002L, 0x1L))    // 非叶，指向 level1
    bus.writePte(level1, pte(0x80003L, 0x1L))  // 非叶，指向 level2
    bus.writePte(level2, pte(leafPpn, 0x5L))   // V|W，R=0：保留编码

    simulate(new ysyx_26030103_PTW(
      adMode = "ignore",
      pteSizeBytes = 8,
      levels = 3,
      tableEntries = 512,
      pageBits = 12,
      XLEN = 64,
      addressWidth = 64,
      vaBits = 39,
      pteConfig = ysyx_26030103_PteConfig(pteBits = 64, ppnHigh = 53)
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
      dut.io.req.bits.vaddr.poke(0.U(64.W))
      dut.io.req.bits.satp.poke(
        ((BigInt(8) << 60) | BigInt("80001", 16)).U(64.W))
      dut.io.req.bits.privilege.poke(3.U) // M 模式，排除 U/SUM 干扰
      dut.io.req.bits.access.poke(ysyx_26030103_AccessType.Store)
      dut.io.req.bits.sum.poke(false.B)
      dut.io.req.bits.mxr.poke(false.B)

      var fault = false
      var done = false
      var cycles = 0
      while (!done && cycles < 200) {
        // 保持 req.valid：PTW 只在 idle 时拉高 req.ready，握手一次后
        // 后续 valid 会被忽略。
        bus.tick(dut, dut.clock)
        if (bool(dut.io.resp.valid)) {
          done = true
          fault = bool(dut.io.resp.bits.fault)
        }
        cycles += 1
      }
      withClue("PTW did not respond: ") { done should be(true) }
      withClue("R=0,W=1 is a reserved PTE and must page fault: ") {
        fault should be(true)
      }
    }
  }

  it should "在 resp 反压时保持 fillValid，直到响应被接收" in {
    // 1 级 Sv32 页表：vaddr 0 的根 PTE 直接是叶子。
    val root = 0x80001000L
    val bus = new BusModel
    bus.write(root, pte(0x80002L, 0x7L)) // V|R|W|X

    simulate(new ysyx_26030103_PTW(
      adMode = "ignore",
      pteSizeBytes = 4,
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
      // 先不接收响应，检查 fillValid 是否保持到握手。
      dut.io.resp.ready.poke(false.B)
      dut.io.req.valid.poke(true.B)
      dut.io.req.bits.id.poke(0.U)
      dut.io.req.bits.vaddr.poke(0.U(32.W))
      dut.io.req.bits.satp.poke(
        (BigInt(1) << 31 | BigInt("80001", 16)).U(32.W))
      dut.io.req.bits.privilege.poke(3.U)
      dut.io.req.bits.access.poke(ysyx_26030103_AccessType.Load)
      dut.io.req.bits.sum.poke(false.B)
      dut.io.req.bits.mxr.poke(false.B)

      var cycles = 0
      while (!bool(dut.io.resp.valid) && cycles < 100) {
        bus.tick(dut, dut.clock)
        cycles += 1
      }
      withClue("PTW did not respond: ") {
        bool(dut.io.resp.valid) should be(true)
      }
      bool(dut.io.resp.bits.fillValid) should be(true)
      // 保持 ready=0 一拍：Decoupled 响应有效期间 fillValid 必须稳定。
      dut.clock.step()
      bool(dut.io.resp.valid) should be(true)
      withClue("fillValid dropped while resp.ready was low: ") {
        bool(dut.io.resp.bits.fillValid) should be(true)
      }
      dut.io.resp.ready.poke(true.B)
      dut.clock.step()
      dut.io.req.valid.poke(false.B)
      dut.io.resp.ready.poke(false.B)
    }
  }
}
