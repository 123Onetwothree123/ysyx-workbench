package ysyx_26030103.audit

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.ysyx_26030103.config._
import _root_.ysyx_26030103.mmu._
import _root_.ysyx_26030103.test.ysyx_26030103_TestCommon.rv32Profile
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** MMU/PTW flush 语义与 TLB hardware A/D 的定向审计。
  *
  * 页表内存放在 Scala 侧，通过真实的 PTW 总线逐拍响应；`hold` 可以把某一
  * 笔页表读挂起，从而精确构造“flush 与响应同拍”以及“flush 后旧 walk
  * 才返回”的竞争窗口。
  */
class ysyx_26030103_MMUFlushAuditTest extends AnyFlatSpec with Matchers {
  private val RootPPN = 0x80001L
  private val Root = RootPPN << 12
  private val LeafPPN = 0x80003L
  private val Leaf = LeafPPN << 12
  private val NewLeafPPN = 0x80004L
  private val NewLeaf = NewLeafPPN << 12

  private def bool(v: Bool): Boolean = v.peek().litValue == 1
  private def long(v: UInt): Long = v.peek().litValue.toLong
  private def PTE(PPN: Long, flags: Long): BigInt =
    (BigInt(PPN) << 10) | BigInt(flags)

  /** 单 outstanding 的页表总线模型，可指定某地址的响应被挂起。 */
  private class PTEBus(latency: Int = 1) {
    private val mem = scala.collection.mutable.Map.empty[Long, BigInt]
    private var pending: Option[(Int, BigInt)] = None
    private var pendingAddress = 0L
    private var delay = 0
    /** 命中该地址的读响应保持挂起，直到 release() 被调用。 */
    var holdAddress: Option[Long] = None
    var reads = 0
    var writes = 0

    def write(addr: Long, data: BigInt): Unit =
      mem(addr & 0xffffffffL) = data & 0xffffffffL
    def read(addr: Long): BigInt =
      mem.getOrElse(addr & 0xffffffffL, BigInt(0))
    def hasPending: Boolean = pending.nonEmpty
    def heldPending: Boolean =
      pending.nonEmpty && holdAddress.contains(pendingAddress)
    def release(): Unit = {
      holdAddress = None
      delay = 0
    }

    def tick(bus: ysyx_26030103_PTEBusIO, clock: Clock): Unit = {
      val held = holdAddress.contains(pendingAddress)
      val drive = pending.nonEmpty && delay == 0 && !held
      bus.response.valid.poke(drive.B)
      bus.response.bits.ID.poke(pending.map(_._1.U(4.W)).getOrElse(0.U(4.W)))
      bus.response.bits.data.poke(
        pending.map(_._2.U(32.W)).getOrElse(0.U(32.W)))
      bus.response.bits.fault.poke(false.B)

      val ResponseFire = drive && bool(bus.response.ready)
      val RequestFire = bool(bus.request.valid) && bool(bus.request.ready)
      val RequestID = if (RequestFire) long(bus.request.bits.ID).toInt else 0
      val RequestAddress = if (RequestFire) long(bus.request.bits.address) else 0L
      val RequestWrite = RequestFire && bool(bus.request.bits.write)
      val RequestData =
        if (RequestFire) bus.request.bits.data.peek().litValue else BigInt(0)

      clock.step()

      if (ResponseFire) pending = None
      else if (pending.nonEmpty && delay > 0) delay -= 1

      if (RequestFire) {
        if (RequestWrite) {
          writes += 1
          mem(RequestAddress & 0xffffffffL) = RequestData & 0xffffffffL
          pending = Some((RequestID, BigInt(0)))
        } else {
          reads += 1
          pending = Some((
            RequestID,
            mem.getOrElse(RequestAddress & 0xffffffffL, BigInt(0))
          ))
        }
        pendingAddress = RequestAddress
        delay = latency - 1
      }
    }
  }

  private def initPTW(
      dut: ysyx_26030103_PTW,
      bus: PTEBus
  ): Unit = {
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
    dut.io.bus.request.ready.poke(true.B)
    dut.io.bus.response.valid.poke(false.B)
    dut.io.bus.response.bits.ID.poke(0.U(4.W))
    dut.io.bus.response.bits.data.poke(0.U(32.W))
    dut.io.bus.response.bits.fault.poke(false.B)
    dut.io.flush.poke(false.B)
    dut.io.response.ready.poke(true.B)
    dut.io.request.valid.poke(false.B)
    dut.io.request.bits.ID.poke(0.U(4.W))
    dut.io.request.bits.vaddr.poke(0.U(32.W))
    dut.io.request.bits.satp.poke(0.U(32.W))
    dut.io.request.bits.privilege.poke(3.U(2.W))
    dut.io.request.bits.access.poke(ysyx_26030103_AccessType.Load)
    dut.io.request.bits.sum.poke(false.B)
    dut.io.request.bits.mxr.poke(false.B)
    bus.tick(dut.io.bus, dut.clock)
  }

  private def issuePTW(
      dut: ysyx_26030103_PTW,
      bus: PTEBus,
      vaddr: Long,
      access: UInt = ysyx_26030103_AccessType.Load
  ): Unit = {
    dut.io.request.bits.vaddr.poke(vaddr.U(32.W))
    dut.io.request.bits.satp.poke((0x80000000L | RootPPN).U(32.W))
    dut.io.request.bits.privilege.poke(3.U(2.W))
    dut.io.request.bits.access.poke(access)
    dut.io.request.valid.poke(true.B)
    var guard = 0
    while (!bool(dut.io.request.ready) && guard < 30) {
      bus.tick(dut.io.bus, dut.clock)
      guard += 1
    }
    withClue(s"PTW 未在 $guard 拍内接收请求: ") {
      bool(dut.io.request.ready) should be(true)
    }
    bus.tick(dut.io.bus, dut.clock)
    dut.io.request.valid.poke(false.B)
  }

  private def waitPTWResponse(
      dut: ysyx_26030103_PTW,
      bus: PTEBus
  ): (BigInt, Boolean) = {
    var result: Option[(BigInt, Boolean)] = None
    var guard = 0
    while (result.isEmpty && guard < 60) {
      bus.tick(dut.io.bus, dut.clock)
      if (bool(dut.io.response.valid)) {
        result = Some((
          dut.io.response.bits.paddr.peek().litValue,
          bool(dut.io.response.bits.fault)
        ))
      }
      guard += 1
    }
    withClue(s"PTW 未在 $guard 拍内返回响应: ") { result.isEmpty should be(false) }
    result.get
  }

  private def ptwConfig(cache: Boolean = true): ysyx_26030103_PTW =
    new ysyx_26030103_PTW(
      adMode = "fault",
      cacheEnable = cache,
      cacheEntries = 16,
      cacheWays = 2,
      PTESizeBytes = 4,
      // 4B PTE 必须使用 Sv32 字段布局；默认 PTEConfig 来自 Kconfig（RV64）。
      PTEConfig = ysyx_26030103_PTEConfig(PTEBits = 32, PPNHigh = 31),
      levels = 1,
      tableEntries = 1024,
      pageBits = 12,
      XLEN = 32,
      addressWidth = 32,
      vaBits = 22
    )

  "PTW" should "flush 与页表响应同拍时不得把旧 PTE 回填 cache" in {
    val bus = new PTEBus()
    bus.write(Root, PTE(LeafPPN, 0xdfL))
    bus.holdAddress = Some(Root)
    simulate(ptwConfig()) { dut =>
      initPTW(dut, bus)
      issuePTW(dut, bus, 0x0L)
      var busGuard = 0
      while (!bus.hasPending && busGuard < 30) {
        bus.tick(dut.io.bus, dut.clock)
        busGuard += 1
      }
      withClue(s"页表读未发出（$busGuard 拍）: ") { bus.hasPending should be(true) }
      withClue("页表读未被挂起: ") { bus.heldPending should be(true) }

      // 软件在 sfence 前后更新同一 PTE，指向另一物理页。
      bus.write(Root, PTE(NewLeafPPN, 0xdfL))

      // 响应与 flush 同拍返回。
      bus.release()
      dut.io.flush.poke(true.B)
      bus.tick(dut.io.bus, dut.clock)
      dut.io.flush.poke(false.B)
      withClue("flush 当拍响应未被接收: ") { bus.hasPending should be(false) }

      // 等待旧响应被消费、PTW 回到 idle。
      var guard = 0
      while (!bool(dut.io.request.ready) && guard < 10) {
        bus.tick(dut.io.bus, dut.clock)
        guard += 1
      }

      // 同一 PTE 地址再次 walk，必须看到 flush 后的新 PTE。
      issuePTW(dut, bus, 0x0L)
      val (paddr, fault) = waitPTWResponse(dut, bus)
      fault should be(false)
      withClue("flush 后仍命中被回填的旧 PTE: ") {
        paddr should be(BigInt(NewLeaf))
      }
    }
  }

  it should "flush 发生在 walk 中途时不得把随后返回的旧 PTE 回填 cache" in {
    val bus = new PTEBus()
    bus.write(Root, PTE(LeafPPN, 0xdfL))
    bus.holdAddress = Some(Root)
    simulate(ptwConfig()) { dut =>
      initPTW(dut, bus)
      issuePTW(dut, bus, 0x0L)
      var busGuard = 0
      while (!bus.hasPending && busGuard < 30) {
        bus.tick(dut.io.bus, dut.clock)
        busGuard += 1
      }
      withClue(s"页表读未被挂起（$busGuard 拍）: ") {
        bus.heldPending should be(true)
      }

      // flush 时旧读尚未返回；随后旧读返回，不能污染 flush 后的 cache。
      dut.io.flush.poke(true.B)
      bus.tick(dut.io.bus, dut.clock)
      dut.io.flush.poke(false.B)

      bus.write(Root, PTE(NewLeafPPN, 0xdfL))
      bus.release()
      bus.tick(dut.io.bus, dut.clock)
      withClue("旧页表响应未被接收: ") { bus.hasPending should be(false) }

      var guard = 0
      while (!bool(dut.io.request.ready) && guard < 10) {
        bus.tick(dut.io.bus, dut.clock)
        guard += 1
      }

      issuePTW(dut, bus, 0x0L)
      val (paddr, fault) = waitPTWResponse(dut, bus)
      fault should be(false)
      withClue("flush 后仍命中 flush 前 walk 回填的旧 PTE: ") {
        paddr should be(BigInt(NewLeaf))
      }
    }
  }

  it should "MMU flush 后在途 walk 的旧 PTE 不得污染 PTW cache" in {
    val bus = new PTEBus()
    // 两级页表：根 PTE 指向第二级表，叶子 PTE 位于 0x80002004。
    bus.write(Root, PTE(0x80002L, 0x1L))
    bus.write(0x80002004L, PTE(LeafPPN, 0xdfL))
    bus.holdAddress = Some(0x80002004L)
    val profile = rv32Profile.copy(
      enabled = true,
      TLBEnabled = false,
      PTWCache = true,
      PTWCacheEntries = 16,
      PTWCacheWays = 2,
      PTWOutstanding = 1
    )
    simulate(new ysyx_26030103_MMU(profile)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.request.valid.poke(false.B)
      dut.io.request.bits.poke(
        0.U.asTypeOf(new ysyx_26030103_MMURequest(32, 32)))
      dut.io.bus.request.ready.poke(true.B)
      dut.io.bus.response.valid.poke(false.B)
      dut.io.bus.response.bits.poke(
        0.U.asTypeOf(new ysyx_26030103_PTEBusResponse))
      dut.io.response.ready.poke(true.B)
      dut.io.flush.poke(false.B)

      def issue(vaddr: Long): Unit = {
        dut.io.request.bits.ID.poke(0.U(4.W))
        dut.io.request.bits.vaddr.poke(vaddr.U(32.W))
        dut.io.request.bits.access.poke(ysyx_26030103_AccessType.Load)
        dut.io.request.bits.privilege.poke(0.U(2.W))
        dut.io.request.bits.sum.poke(false.B)
        dut.io.request.bits.mxr.poke(false.B)
        dut.io.request.bits.satp.poke((0x80000000L | RootPPN).U(32.W))
        dut.io.request.valid.poke(true.B)
        var guard = 0
        while (!bool(dut.io.request.ready) && guard < 40) {
          bus.tick(dut.io.bus, dut.clock)
          guard += 1
        }
        withClue(s"MMU 未在 $guard 拍内接收请求: ") {
          bool(dut.io.request.ready) should be(true)
        }
        bus.tick(dut.io.bus, dut.clock)
        dut.io.request.valid.poke(false.B)
      }

      def waitResponse(): (BigInt, Boolean) = {
        var result: Option[(BigInt, Boolean)] = None
        var guard = 0
        while (result.isEmpty && guard < 80) {
          bus.tick(dut.io.bus, dut.clock)
          if (bool(dut.io.response.valid)) {
            result = Some((
              dut.io.response.bits.paddr.peek().litValue,
              bool(dut.io.response.bits.fault)
            ))
          }
          guard += 1
        }
        withClue(s"MMU 未在 $guard 拍内返回响应: ") {
          result.isEmpty should be(false)
        }
        result.get
      }

      // 先完成第一级读，旧 walk 停在叶子 PTE 读上。
      issue(0x1234L)
      var guard = 0
      while (!bus.heldPending && guard < 40) {
        bus.tick(dut.io.bus, dut.clock)
        guard += 1
      }
      withClue("叶子 PTE 读未被挂起: ") { bus.heldPending should be(true) }

      // sfence 后软件改页表；旧 walk 的叶子读在 flush 之后才返回。
      dut.io.flush.poke(true.B)
      bus.tick(dut.io.bus, dut.clock)
      dut.io.flush.poke(false.B)
      bus.write(0x80002004L, PTE(NewLeafPPN, 0xdfL))
      bus.release()
      bus.tick(dut.io.bus, dut.clock)
      withClue("MMU 未接收旧页表响应: ") { bus.hasPending should be(false) }

      // 让旧 walk 结束并被 MMU 丢弃。
      for (_ <- 0 until 6) { bus.tick(dut.io.bus, dut.clock) }

      // 同一虚拟地址重新翻译，必须使用新 PTE。
      issue(0x1234L)
      val (paddr, fault) = waitResponse()
      fault should be(false)
      withClue("MMU flush 后仍返回旧 PTE 的翻译: ") {
        paddr should be(BigInt(NewLeaf + 0x234L))
      }
    }
  }
}
