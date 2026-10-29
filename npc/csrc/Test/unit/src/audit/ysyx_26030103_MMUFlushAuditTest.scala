package ysyx_26030103.audit

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.ysyx_26030103.config._
import _root_.ysyx_26030103.mmu._
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
  private def pte(ppn: Long, flags: Long): BigInt =
    (BigInt(ppn) << 10) | BigInt(flags)

  /** 单 outstanding 的页表总线模型，可指定某地址的响应被挂起。 */
  private class PteBus(latency: Int = 1) {
    private val mem = scala.collection.mutable.Map.empty[Long, BigInt]
    private var pending: Option[(Int, BigInt)] = None
    private var pendingAddr = 0L
    private var delay = 0
    /** 命中该地址的读响应保持挂起，直到 release() 被调用。 */
    var holdAddr: Option[Long] = None
    var reads = 0
    var writes = 0

    def write(addr: Long, data: BigInt): Unit =
      mem(addr & 0xffffffffL) = data & 0xffffffffL
    def read(addr: Long): BigInt =
      mem.getOrElse(addr & 0xffffffffL, BigInt(0))
    def hasPending: Boolean = pending.nonEmpty
    def heldPending: Boolean =
      pending.nonEmpty && holdAddr.contains(pendingAddr)
    def release(): Unit = {
      holdAddr = None
      delay = 0
    }

    def tick(bus: ysyx_26030103_PTEBusIO, clock: Clock): Unit = {
      val held = holdAddr.contains(pendingAddr)
      val drive = pending.nonEmpty && delay == 0 && !held
      bus.resp.valid.poke(drive.B)
      bus.resp.bits.id.poke(pending.map(_._1.U(4.W)).getOrElse(0.U(4.W)))
      bus.resp.bits.data.poke(
        pending.map(_._2.U(32.W)).getOrElse(0.U(32.W)))
      bus.resp.bits.fault.poke(false.B)

      val respFire = drive && bool(bus.resp.ready)
      val reqFire = bool(bus.req.valid) && bool(bus.req.ready)
      val reqId = if (reqFire) long(bus.req.bits.id).toInt else 0
      val reqAddr = if (reqFire) long(bus.req.bits.addr) else 0L
      val reqWrite = reqFire && bool(bus.req.bits.write)
      val reqData =
        if (reqFire) bus.req.bits.data.peek().litValue else BigInt(0)

      clock.step()

      if (respFire) pending = None
      else if (pending.nonEmpty && delay > 0) delay -= 1

      if (reqFire) {
        if (reqWrite) {
          writes += 1
          mem(reqAddr & 0xffffffffL) = reqData & 0xffffffffL
          pending = Some((reqId, BigInt(0)))
        } else {
          reads += 1
          pending = Some((
            reqId,
            mem.getOrElse(reqAddr & 0xffffffffL, BigInt(0))
          ))
        }
        pendingAddr = reqAddr
        delay = latency - 1
      }
    }
  }

  private def initPtw(
      dut: ysyx_26030103_PTW,
      bus: PteBus
  ): Unit = {
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
    dut.io.bus.req.ready.poke(true.B)
    dut.io.bus.resp.valid.poke(false.B)
    dut.io.bus.resp.bits.id.poke(0.U(4.W))
    dut.io.bus.resp.bits.data.poke(0.U(32.W))
    dut.io.bus.resp.bits.fault.poke(false.B)
    dut.io.flush.poke(false.B)
    dut.io.resp.ready.poke(true.B)
    dut.io.req.valid.poke(false.B)
    dut.io.req.bits.id.poke(0.U(4.W))
    dut.io.req.bits.vaddr.poke(0.U(32.W))
    dut.io.req.bits.satp.poke(0.U(32.W))
    dut.io.req.bits.privilege.poke(3.U(2.W))
    dut.io.req.bits.access.poke(ysyx_26030103_AccessType.Load)
    dut.io.req.bits.sum.poke(false.B)
    dut.io.req.bits.mxr.poke(false.B)
    bus.tick(dut.io.bus, dut.clock)
  }

  private def issuePtw(
      dut: ysyx_26030103_PTW,
      bus: PteBus,
      vaddr: Long,
      access: UInt = ysyx_26030103_AccessType.Load
  ): Unit = {
    dut.io.req.bits.vaddr.poke(vaddr.U(32.W))
    dut.io.req.bits.satp.poke((0x80000000L | RootPPN).U(32.W))
    dut.io.req.bits.privilege.poke(3.U(2.W))
    dut.io.req.bits.access.poke(access)
    dut.io.req.valid.poke(true.B)
    var guard = 0
    while (!bool(dut.io.req.ready) && guard < 30) {
      bus.tick(dut.io.bus, dut.clock)
      guard += 1
    }
    withClue(s"PTW 未在 $guard 拍内接收请求: ") {
      bool(dut.io.req.ready) should be(true)
    }
    bus.tick(dut.io.bus, dut.clock)
    dut.io.req.valid.poke(false.B)
  }

  private def waitPtwResponse(
      dut: ysyx_26030103_PTW,
      bus: PteBus
  ): (BigInt, Boolean) = {
    var result: Option[(BigInt, Boolean)] = None
    var guard = 0
    while (result.isEmpty && guard < 60) {
      bus.tick(dut.io.bus, dut.clock)
      if (bool(dut.io.resp.valid)) {
        result = Some((
          dut.io.resp.bits.paddr.peek().litValue,
          bool(dut.io.resp.bits.fault)
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
      pteSizeBytes = 4,
      levels = 1,
      tableEntries = 1024,
      pageBits = 12,
      XLEN = 32,
      addressWidth = 32,
      vaBits = 22
    )

  "PTW" should "flush 与页表响应同拍时不得把旧 PTE 回填 cache" in {
    val bus = new PteBus()
    bus.write(Root, pte(LeafPPN, 0xdfL))
    bus.holdAddr = Some(Root)
    simulate(ptwConfig()) { dut =>
      initPtw(dut, bus)
      issuePtw(dut, bus, 0x0L)
      var busGuard = 0
      while (!bus.hasPending && busGuard < 30) {
        bus.tick(dut.io.bus, dut.clock)
        busGuard += 1
      }
      withClue(s"页表读未发出（$busGuard 拍）: ") { bus.hasPending should be(true) }
      withClue("页表读未被挂起: ") { bus.heldPending should be(true) }

      // 软件在 sfence 前后更新同一 PTE，指向另一物理页。
      bus.write(Root, pte(NewLeafPPN, 0xdfL))

      // 响应与 flush 同拍返回。
      bus.release()
      dut.io.flush.poke(true.B)
      bus.tick(dut.io.bus, dut.clock)
      dut.io.flush.poke(false.B)
      withClue("flush 当拍响应未被接收: ") { bus.hasPending should be(false) }

      // 等待旧响应被消费、PTW 回到 idle。
      var guard = 0
      while (!bool(dut.io.req.ready) && guard < 10) {
        bus.tick(dut.io.bus, dut.clock)
        guard += 1
      }

      // 同一 PTE 地址再次 walk，必须看到 flush 后的新 PTE。
      issuePtw(dut, bus, 0x0L)
      val (paddr, fault) = waitPtwResponse(dut, bus)
      fault should be(false)
      withClue("flush 后仍命中被回填的旧 PTE: ") {
        paddr should be(BigInt(NewLeaf))
      }
    }
  }

  it should "flush 发生在 walk 中途时不得把随后返回的旧 PTE 回填 cache" in {
    val bus = new PteBus()
    bus.write(Root, pte(LeafPPN, 0xdfL))
    bus.holdAddr = Some(Root)
    simulate(ptwConfig()) { dut =>
      initPtw(dut, bus)
      issuePtw(dut, bus, 0x0L)
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

      bus.write(Root, pte(NewLeafPPN, 0xdfL))
      bus.release()
      bus.tick(dut.io.bus, dut.clock)
      withClue("旧页表响应未被接收: ") { bus.hasPending should be(false) }

      var guard = 0
      while (!bool(dut.io.req.ready) && guard < 10) {
        bus.tick(dut.io.bus, dut.clock)
        guard += 1
      }

      issuePtw(dut, bus, 0x0L)
      val (paddr, fault) = waitPtwResponse(dut, bus)
      fault should be(false)
      withClue("flush 后仍命中 flush 前 walk 回填的旧 PTE: ") {
        paddr should be(BigInt(NewLeaf))
      }
    }
  }

  it should "MMU flush 后在途 walk 的旧 PTE 不得污染 PTW cache" in {
    val bus = new PteBus()
    // 两级页表：根 PTE 指向第二级表，叶子 PTE 位于 0x80002004。
    bus.write(Root, pte(0x80002L, 0x1L))
    bus.write(0x80002004L, pte(LeafPPN, 0xdfL))
    bus.holdAddr = Some(0x80002004L)
    val profile = ysyx_26030103_MMUProfile(
      enabled = true,
      tlbEnabled = false,
      ptwCache = true,
      ptwCacheEntries = 16,
      ptwCacheWays = 2,
      ptwOutstanding = 1
    )
    simulate(new ysyx_26030103_MMU(profile)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.req.valid.poke(false.B)
      dut.io.req.bits.poke(
        0.U.asTypeOf(new ysyx_26030103_MMURequest(32, 32)))
      dut.io.bus.req.ready.poke(true.B)
      dut.io.bus.resp.valid.poke(false.B)
      dut.io.bus.resp.bits.poke(
        0.U.asTypeOf(new ysyx_26030103_PTEBusResponse))
      dut.io.resp.ready.poke(true.B)
      dut.io.flush.poke(false.B)

      def issue(vaddr: Long): Unit = {
        dut.io.req.bits.id.poke(0.U(4.W))
        dut.io.req.bits.vaddr.poke(vaddr.U(32.W))
        dut.io.req.bits.access.poke(ysyx_26030103_AccessType.Load)
        dut.io.req.bits.privilege.poke(0.U(2.W))
        dut.io.req.bits.sum.poke(false.B)
        dut.io.req.bits.mxr.poke(false.B)
        dut.io.req.bits.satp.poke((0x80000000L | RootPPN).U(32.W))
        dut.io.req.valid.poke(true.B)
        var guard = 0
        while (!bool(dut.io.req.ready) && guard < 40) {
          bus.tick(dut.io.bus, dut.clock)
          guard += 1
        }
        withClue(s"MMU 未在 $guard 拍内接收请求: ") {
          bool(dut.io.req.ready) should be(true)
        }
        bus.tick(dut.io.bus, dut.clock)
        dut.io.req.valid.poke(false.B)
      }

      def waitResponse(): (BigInt, Boolean) = {
        var result: Option[(BigInt, Boolean)] = None
        var guard = 0
        while (result.isEmpty && guard < 80) {
          bus.tick(dut.io.bus, dut.clock)
          if (bool(dut.io.resp.valid)) {
            result = Some((
              dut.io.resp.bits.paddr.peek().litValue,
              bool(dut.io.resp.bits.fault)
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
      bus.write(0x80002004L, pte(NewLeafPPN, 0xdfL))
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

  "MMU" should "TLB hardware A/D 在 store 命中时更新 D 位" in {
    val bus = new PteBus()
    bus.write(Root, pte(0x80002L, 0x1L))
    // V|R|W|X|A，D=0。
    bus.write(0x80002004L, pte(LeafPPN, 0x4fL))
    val profile = ysyx_26030103_MMUProfile(
      enabled = true,
      tlbEnabled = true,
      tlbOrganization = "split",
      adMode = "hardware",
      ptwOutstanding = 1
    )
    simulate(new ysyx_26030103_MMU(profile)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.req.valid.poke(false.B)
      dut.io.req.bits.poke(
        0.U.asTypeOf(new ysyx_26030103_MMURequest(32, 32)))
      dut.io.bus.req.ready.poke(true.B)
      dut.io.bus.resp.valid.poke(false.B)
      dut.io.bus.resp.bits.poke(
        0.U.asTypeOf(new ysyx_26030103_PTEBusResponse))
      dut.io.resp.ready.poke(true.B)
      dut.io.flush.poke(false.B)

      def access(vaddr: Long, accessType: UInt): (BigInt, Boolean) = {
        dut.io.req.bits.id.poke(0.U(4.W))
        dut.io.req.bits.vaddr.poke(vaddr.U(32.W))
        dut.io.req.bits.access.poke(accessType)
        dut.io.req.bits.privilege.poke(1.U(2.W))
        dut.io.req.bits.sum.poke(false.B)
        dut.io.req.bits.mxr.poke(false.B)
        dut.io.req.bits.satp.poke((0x80000000L | RootPPN).U(32.W))
        dut.io.req.valid.poke(true.B)
        var guard = 0
        while (!bool(dut.io.req.ready) && guard < 40) {
          bus.tick(dut.io.bus, dut.clock)
          guard += 1
        }
        withClue(s"MMU 未在 $guard 拍内接收请求: ") {
          bool(dut.io.req.ready) should be(true)
        }
        bus.tick(dut.io.bus, dut.clock)
        dut.io.req.valid.poke(false.B)

        var result: Option[(BigInt, Boolean)] = None
        guard = 0
        while (result.isEmpty && guard < 80) {
          bus.tick(dut.io.bus, dut.clock)
          if (bool(dut.io.resp.valid)) {
            result = Some((
              dut.io.resp.bits.paddr.peek().litValue,
              bool(dut.io.resp.bits.fault)
            ))
          }
          guard += 1
        }
        withClue(s"MMU 未在 $guard 拍内返回响应: ") {
          result.isEmpty should be(false)
        }
        result.get
      }

      // 第一次 load 填 DTLB：PTE 为 A=1,D=0。
      val load = access(0x1234L, ysyx_26030103_AccessType.Load)
      load should be((BigInt(Leaf + 0x234L), false))
      bus.writes should be(0)

      // 同页 store 命中 A=1,D=0 的 DTLB 表项；hardware A/D 要求重填并
      // 把 D 写回页表，而不是直接放行。
      val store = access(0x1234L, ysyx_26030103_AccessType.Store)
      store should be((BigInt(Leaf + 0x234L), false))
      withClue("store 命中 DTLB 后页表 D 仍为 0（hardware A/D 未写回）: ") {
        (bus.read(0x80002004L) & BigInt(0x80)) should be(BigInt(0x80))
      }
      withClue("store 命中 D=0 表项应触发一次页表写: ") {
        bus.writes should be(1)
      }

      // 对照：刷新 TLB 后 store 重新走 PTW；D 已写回，不应重复写。
      dut.io.flush.poke(true.B)
      bus.tick(dut.io.bus, dut.clock)
      dut.io.flush.poke(false.B)
      for (_ <- 0 until 4) { bus.tick(dut.io.bus, dut.clock) }
      val storeAfterFlush = access(0x1234L, ysyx_26030103_AccessType.Store)
      storeAfterFlush should be((BigInt(Leaf + 0x234L), false))
      withClue("PTW 重新 walk 时 D 应保持已写回: ") {
        (bus.read(0x80002004L) & BigInt(0x80)) should be(BigInt(0x80))
      }
      withClue("D 已写回后重新 walk 不应重复写页表: ") {
        bus.writes should be(1)
      }
    }
  }
}
