package ysyx_26030103.audit

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.ysyx_26030103.config._
import _root_.ysyx_26030103.mmu._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** PTW cache 与 sfence/flush 的逐拍竞争审计。
  *
  * `ysyx_26030103_MMUFlushAuditTest` 已覆盖 waitState；这里补齐 8B PTE 的
  * waitHigh、hardware A/D 写回的 waitWrite，以及 MMU reqQueue/flush 和
  * PTW pipeline 的同拍组合。每个失败都先确认测试自身模型正确：旧响应携带
  * 的确实是 flush 前的 PTE，flush 后的页表内存确实已经改成新 PTE。
  */
class ysyx_26030103_MMUFlushRaceAuditTest
    extends AnyFlatSpec with Matchers {
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
  private val Satp = (0x80000000L | RootPPN).U(32.W)

  /** 单事务页表总线，可分别挂起指定地址的读响应或任意写响应。 */
  private class PteBus(var latency: Int = 1) {
    private case class Pending(id: Int, addr: Long, data: BigInt, write: Boolean)
    private val mem = scala.collection.mutable.Map.empty[Long, BigInt]
    private var pending: Option[Pending] = None
    private var delay = 0
    var holdReadAddr: Option[Long] = None
    var holdWrite: Boolean = false
    var reads = 0
    var writes = 0

    def write(addr: Long, data: BigInt): Unit =
      mem(addr & 0xffffffffL) = data & 0xffffffffL
    def read(addr: Long): BigInt =
      mem.getOrElse(addr & 0xffffffffL, BigInt(0))
    def hasPending: Boolean = pending.nonEmpty
    private def held(p: Pending): Boolean =
      if (p.write) holdWrite
      else holdReadAddr.contains(p.addr)
    def heldPending: Boolean = pending.exists(held)
    def releaseRead(): Unit = { holdReadAddr = None; delay = 0 }
    def releaseWrite(): Unit = { holdWrite = false; delay = 0 }

    def tick(bus: ysyx_26030103_PTEBusIO, clock: Clock): Unit = {
      val drive = pending.nonEmpty && delay == 0 && !pending.exists(held)
      bus.resp.valid.poke(drive.B)
      bus.resp.bits.id.poke(pending.map(_.id.U(4.W)).getOrElse(0.U(4.W)))
      bus.resp.bits.data.poke(
        pending.map(_.data.U(32.W)).getOrElse(0.U(32.W)))
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
          pending = Some(Pending(reqId, reqAddr, BigInt(0), write = true))
        } else {
          reads += 1
          pending = Some(Pending(
            reqId, reqAddr, mem.getOrElse(reqAddr & 0xffffffffL, BigInt(0)),
            write = false))
        }
        delay = latency - 1
      }
    }
  }

  /** 多 outstanding 页表总线，可只挂起“第一次”访问指定地址的读响应。
    *
    * 用于构造 pipelined PTW 的迟到响应：旧 walk 的叶子读一直挂起，flush
    * 后同一 PTE 地址的新 walk 仍能拿到新数据并完成。
    */
  private class MultiBus(var latency: Int = 1) {
    private class Pending(
        val id: Int,
        val addr: Long,
        val data: BigInt,
        val write: Boolean,
        var remain: Int,
        var held: Boolean)
    private val mem = scala.collection.mutable.Map.empty[Long, BigInt]
    private val pending = scala.collection.mutable.ArrayBuffer.empty[Pending]
    var holdOnceAddr: Option[Long] = None
    private var holdUsed = false
    var reads = 0
    var writes = 0

    def write(addr: Long, data: BigInt): Unit =
      mem(addr & 0xffffffffL) = data & 0xffffffffL
    def read(addr: Long): BigInt =
      mem.getOrElse(addr & 0xffffffffL, BigInt(0))
    def heldPending: Boolean = pending.exists(_.held)
    def release(): Unit = pending.foreach(_.held = false)

    def tick(bus: ysyx_26030103_PTEBusIO, clock: Clock): Unit = {
      val readyIdx = pending.indexWhere(p => p.remain == 0 && !p.held)
      val respValid = readyIdx >= 0
      bus.resp.valid.poke(respValid.B)
      bus.resp.bits.id.poke(
        (if (respValid) pending(readyIdx).id else 0).U(4.W))
      bus.resp.bits.data.poke(
        (if (respValid) pending(readyIdx).data else BigInt(0)).U(32.W))
      bus.resp.bits.fault.poke(false.B)

      val respFire = respValid && bool(bus.resp.ready)
      val reqFire = bool(bus.req.valid) && bool(bus.req.ready)
      val reqId = if (reqFire) long(bus.req.bits.id).toInt else 0
      val reqAddr = if (reqFire) long(bus.req.bits.addr) else 0L
      val reqWrite = reqFire && bool(bus.req.bits.write)
      val reqData =
        if (reqFire) bus.req.bits.data.peek().litValue else BigInt(0)

      clock.step()

      if (respFire) pending.remove(readyIdx)
      else pending.foreach(p => if (p.remain > 0) p.remain -= 1)

      if (reqFire) {
        val isHeld = !reqWrite && !holdUsed &&
          holdOnceAddr.contains(reqAddr & 0xffffffffL)
        if (isHeld) holdUsed = true
        if (reqWrite) {
          writes += 1
          mem(reqAddr & 0xffffffffL) = reqData & 0xffffffffL
          pending += new Pending(reqId, reqAddr, BigInt(0), true,
            latency - 1, false)
        } else {
          reads += 1
          pending += new Pending(
            reqId, reqAddr, mem.getOrElse(reqAddr & 0xffffffffL, BigInt(0)),
            false, latency - 1, isHeld)
        }
      }
    }
  }

  private def initPtw(dut: ysyx_26030103_PTW, bus: PteBus): Unit = {
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
    dut.io.req.bits.satp.poke(Satp)
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
    dut.io.req.bits.satp.poke(Satp)
    dut.io.req.bits.privilege.poke(3.U(2.W))
    dut.io.req.bits.access.poke(access)
    dut.io.req.valid.poke(true.B)
    var guard = 0
    while (!bool(dut.io.req.ready) && guard < 40) {
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
    withClue(s"PTW 未在 $guard 拍内返回响应: ") { result.isEmpty should be(false) }
    result.get
  }

  private def waitPtwIdle(dut: ysyx_26030103_PTW, bus: PteBus): Unit = {
    var guard = 0
    while (!bool(dut.io.req.ready) && guard < 20) {
      bus.tick(dut.io.bus, dut.clock)
      guard += 1
    }
    withClue(s"PTW 未在 $guard 拍内回到 idle: ") {
      bool(dut.io.req.ready) should be(true)
    }
  }

  private def ptw8(adMode: String = "fault", cache: Boolean = true) =
    new ysyx_26030103_PTW(
      adMode = adMode,
      cacheEnable = cache,
      cacheEntries = 16,
      cacheWays = 2,
      pteSizeBytes = 8,
      levels = 1,
      tableEntries = 1024,
      pageBits = 12,
      XLEN = 32,
      addressWidth = 32,
      vaBits = 22
    )

  private def ptw4(adMode: String = "fault", cache: Boolean = true) =
    new ysyx_26030103_PTW(
      adMode = adMode,
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

  "PTW waitHigh" should
    "flush 与 8B PTE 高字响应同拍时不得把旧 PTE 回填 cache" in {
    val bus = new PteBus(latency = 1)
    // vaddr=0x1234 的索引为 1；8B PTE 的地址是 Root+8，占 Root+8/12。
    val pteAddr = Root + 0x8L
    bus.write(pteAddr, pte(LeafPPN, 0xdfL))
    bus.write(pteAddr + 4L, 0L)
    bus.holdReadAddr = Some(pteAddr + 4L)
    simulate(ptw8()) { dut =>
      initPtw(dut, bus)
      issuePtw(dut, bus, 0x1234L)
      var guard = 0
      while (!bus.heldPending && guard < 40) {
        bus.tick(dut.io.bus, dut.clock)
        guard += 1
      }
      withClue(s"8B PTE 高字读未被挂起（$guard 拍）: ") {
        bus.heldPending should be(true)
      }

      // sfence 后软件把同一 PTE 改到另一物理页。
      bus.write(pteAddr, pte(NewLeafPPN, 0xdfL))
      bus.write(pteAddr + 4L, 0L)

      // 高字响应与 flush 同拍返回。
      bus.releaseRead()
      dut.io.flush.poke(true.B)
      bus.tick(dut.io.bus, dut.clock)
      dut.io.flush.poke(false.B)
      withClue("flush 当拍高字响应未被接收: ") { bus.hasPending should be(false) }

      waitPtwIdle(dut, bus)

      issuePtw(dut, bus, 0x1234L)
      val (paddr, fault) = waitPtwResponse(dut, bus)
      fault should be(false)
      withClue("flush 后仍命中 8B PTE 高字路径回填的旧 PTE: ") {
        paddr should be(BigInt(NewLeaf + 0x234L))
      }
    }
  }

  "PTW waitWrite" should
    "flush 与 hardware A/D 写响应同拍时不得把旧 PTE 回填 cache" in {
    val bus = new PteBus(latency = 1)
    val pteAddr = Root + 0x4L
    // V|R|W|X，A=0：硬件 A/D 模式先写回 A 位。
    bus.write(pteAddr, pte(LeafPPN, 0x0fL))
    bus.holdWrite = true
    simulate(ptw4(adMode = "hardware")) { dut =>
      initPtw(dut, bus)
      issuePtw(dut, bus, 0x1234L)
      var guard = 0
      while (!bus.heldPending && guard < 50) {
        bus.tick(dut.io.bus, dut.clock)
        guard += 1
      }
      withClue(s"A/D 写响应未被挂起（$guard 拍, writes=${bus.writes}）: ") {
        bus.heldPending should be(true)
      }
      withClue("A/D 写回应已产生一笔页表写: ") { bus.writes should be(1) }

      // sfence 后软件把同一 PTE 改到另一物理页（A/D 已置位）。
      bus.write(pteAddr, pte(NewLeafPPN, 0xdfL))

      // 写响应与 flush 同拍返回。
      bus.releaseWrite()
      dut.io.flush.poke(true.B)
      bus.tick(dut.io.bus, dut.clock)
      dut.io.flush.poke(false.B)
      withClue("flush 当拍写响应未被接收: ") { bus.hasPending should be(false) }

      waitPtwIdle(dut, bus)

      issuePtw(dut, bus, 0x1234L)
      val (paddr, fault) = waitPtwResponse(dut, bus)
      fault should be(false)
      withClue("flush 后仍命中 A/D 写回路径缓存的旧 PTE: ") {
        paddr should be(BigInt(NewLeaf + 0x234L))
      }
    }
  }

  "PTW hardware A/D cache" should
    "store 命中 PTW cache 时仍必须写回 D 位" in {
    val bus = new PteBus(latency = 1)
    val pteAddr = Root + 0x4L
    // V|R|W|X，A=0,D=0；第一次 load 只写回 A。
    bus.write(pteAddr, pte(LeafPPN, 0x0fL))
    simulate(ptw4(adMode = "hardware")) { dut =>
      initPtw(dut, bus)
      issuePtw(dut, bus, 0x1234L, ysyx_26030103_AccessType.Load)
      val (loadPaddr, loadFault) = waitPtwResponse(dut, bus)
      loadFault should be(false)
      loadPaddr should be(BigInt(Leaf + 0x234L))
      withClue("load 应写回 A 位: ") {
        (bus.read(pteAddr) & BigInt(0x40)) should be(BigInt(0x40))
      }
      withClue("load 不应写回 D 位: ") {
        (bus.read(pteAddr) & BigInt(0x80)) should be(BigInt(0))
      }
      withClue("load 应产生一次 A/D 写: ") { bus.writes should be(1) }
      waitPtwIdle(dut, bus)

      // 同页 store 命中 PTW cache：硬件 A/D 仍必须把 D 写回页表。
      issuePtw(dut, bus, 0x1234L, ysyx_26030103_AccessType.Store)
      val (storePaddr, storeFault) = waitPtwResponse(dut, bus)
      storeFault should be(false)
      storePaddr should be(BigInt(Leaf + 0x234L))
      withClue("store 命中 PTW cache 后页表 D 仍为 0: ") {
        (bus.read(pteAddr) & BigInt(0x80)) should be(BigInt(0x80))
      }
      withClue("store 命中 PTW cache 应再产生一次 D 写回: ") {
        bus.writes should be(2)
      }
    }
  }

  "PTWPipeline hardware A/D cache" should
    "store 命中 PTW cache 时仍必须写回 D 位" in {
    val bus = new PteBus(latency = 1)
    val pteAddr = Root + 0x4L
    bus.write(pteAddr, pte(LeafPPN, 0x0fL))
    simulate(new ysyx_26030103_PTWPipeline(
      outstanding = 1,
      adMode = "hardware",
      cacheEnable = true,
      cacheEntries = 16,
      cacheWays = 2,
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
      dut.io.req.valid.poke(false.B)
      dut.io.req.bits.poke(
        0.U.asTypeOf(new ysyx_26030103_PTWRequest(32, 32)))
      dut.io.bus.req.ready.poke(true.B)
      dut.io.bus.resp.valid.poke(false.B)
      dut.io.bus.resp.bits.poke(
        0.U.asTypeOf(new ysyx_26030103_PTEBusResponse))
      dut.io.resp(0).ready.poke(true.B)
      dut.io.flush.poke(false.B)

      def issue(access: UInt): Unit = {
        dut.io.req.bits.id.poke(0.U(4.W))
        dut.io.req.bits.vaddr.poke(0x1234.U(32.W))
        dut.io.req.bits.satp.poke(Satp)
        dut.io.req.bits.privilege.poke(3.U(2.W))
        dut.io.req.bits.access.poke(access)
        dut.io.req.bits.sum.poke(false.B)
        dut.io.req.bits.mxr.poke(false.B)
        dut.io.req.valid.poke(true.B)
        var guard = 0
        while (!bool(dut.io.req.ready) && guard < 40) {
          bus.tick(dut.io.bus, dut.clock)
          guard += 1
        }
        withClue(s"PTWPipeline 未在 $guard 拍内接收请求: ") {
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
          if (bool(dut.io.resp(0).valid)) {
            result = Some((
              dut.io.resp(0).bits.paddr.peek().litValue,
              bool(dut.io.resp(0).bits.fault)
            ))
          }
          guard += 1
        }
        withClue(s"PTWPipeline 未在 $guard 拍内返回响应: ") {
          result.isEmpty should be(false)
        }
        result.get
      }

      issue(ysyx_26030103_AccessType.Load)
      val (loadPaddr, loadFault) = waitResponse()
      loadFault should be(false)
      loadPaddr should be(BigInt(Leaf + 0x234L))
      withClue("load 应写回 A 位: ") {
        (bus.read(pteAddr) & BigInt(0x40)) should be(BigInt(0x40))
      }
      withClue("load 应产生一次 A 写: ") { bus.writes should be(1) }

      issue(ysyx_26030103_AccessType.Store)
      val (storePaddr, storeFault) = waitResponse()
      storeFault should be(false)
      storePaddr should be(BigInt(Leaf + 0x234L))
      withClue("store 命中 PTW cache 后页表 D 仍为 0: ") {
        (bus.read(pteAddr) & BigInt(0x80)) should be(BigInt(0x80))
      }
      withClue("store 命中 PTW cache 应再产生一次 D 写回: ") {
        bus.writes should be(2)
      }
    }
  }

  "PTWPipeline" should
    "flush 与 cache fill 同拍时不得留下旧 PTE" in {
    val bus = new PteBus(latency = 1)
    val pteAddr = Root + 0x4L
    bus.write(pteAddr, pte(LeafPPN, 0xdfL))
    bus.holdReadAddr = Some(pteAddr)
    simulate(new ysyx_26030103_PTWPipeline(
      outstanding = 1,
      adMode = "fault",
      cacheEnable = true,
      cacheEntries = 16,
      cacheWays = 2,
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
      dut.io.req.valid.poke(false.B)
      dut.io.req.bits.poke(
        0.U.asTypeOf(new ysyx_26030103_PTWRequest(32, 32)))
      dut.io.bus.req.ready.poke(true.B)
      dut.io.bus.resp.valid.poke(false.B)
      dut.io.bus.resp.bits.poke(
        0.U.asTypeOf(new ysyx_26030103_PTEBusResponse))
      dut.io.resp(0).ready.poke(true.B)
      dut.io.flush.poke(false.B)

      def issue(vaddr: Long): Unit = {
        dut.io.req.bits.id.poke(0.U(4.W))
        dut.io.req.bits.vaddr.poke(vaddr.U(32.W))
        dut.io.req.bits.satp.poke(Satp)
        dut.io.req.bits.privilege.poke(3.U(2.W))
        dut.io.req.bits.access.poke(ysyx_26030103_AccessType.Load)
        dut.io.req.bits.sum.poke(false.B)
        dut.io.req.bits.mxr.poke(false.B)
        dut.io.req.valid.poke(true.B)
        var guard = 0
        while (!bool(dut.io.req.ready) && guard < 40) {
          bus.tick(dut.io.bus, dut.clock)
          guard += 1
        }
        withClue(s"PTWPipeline 未在 $guard 拍内接收请求: ") {
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
          if (bool(dut.io.resp(0).valid)) {
            result = Some((
              dut.io.resp(0).bits.paddr.peek().litValue,
              bool(dut.io.resp(0).bits.fault)
            ))
          }
          guard += 1
        }
        withClue(s"PTWPipeline 未在 $guard 拍内返回响应: ") {
          result.isEmpty should be(false)
        }
        result.get
      }

      issue(0x1234L)
      var guard = 0
      while (!bus.heldPending && guard < 40) {
        bus.tick(dut.io.bus, dut.clock)
        guard += 1
      }
      withClue("PTWPipeline 页表读未被挂起: ") { bus.heldPending should be(true) }

      bus.write(pteAddr, pte(NewLeafPPN, 0xdfL))
      bus.releaseRead()
      dut.io.flush.poke(true.B)
      bus.tick(dut.io.bus, dut.clock)
      dut.io.flush.poke(false.B)

      // 等待旧响应被接收、上下文释放。
      for (_ <- 0 until 8) { bus.tick(dut.io.bus, dut.clock) }

      issue(0x1234L)
      val (paddr, fault) = waitResponse()
      fault should be(false)
      withClue("PTWPipeline flush 后仍命中旧 PTE: ") {
        paddr should be(BigInt(NewLeaf + 0x234L))
      }
    }
  }

  private def mmuProfile(
      ptwArch: String = "independent",
      tlbEnabled: Boolean = false
  ): ysyx_26030103_MMUProfile =
    ysyx_26030103_MMUProfile(
      enabled = true,
      tlbEnabled = tlbEnabled,
      ptwArch = ptwArch,
      ptwOutstanding = 1,
      ptwCache = true,
      ptwCacheEntries = 16,
      ptwCacheWays = 2
    )

  private def initMmu(dut: ysyx_26030103_MMU, bus: PteBus): Unit = {
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
  }

  private def issueMmu(
      dut: ysyx_26030103_MMU,
      bus: PteBus,
      vaddr: Long,
      satp: UInt = Satp,
      access: UInt = ysyx_26030103_AccessType.Load
  ): Unit = {
    dut.io.req.bits.id.poke(0.U(4.W))
    dut.io.req.bits.vaddr.poke(vaddr.U(32.W))
    dut.io.req.bits.access.poke(access)
    dut.io.req.bits.privilege.poke(0.U(2.W))
    dut.io.req.bits.sum.poke(false.B)
    dut.io.req.bits.mxr.poke(false.B)
    dut.io.req.bits.satp.poke(satp)
    dut.io.req.valid.poke(true.B)
    var guard = 0
    while (!bool(dut.io.req.ready) && guard < 60) {
      bus.tick(dut.io.bus, dut.clock)
      guard += 1
    }
    withClue(s"MMU 未在 $guard 拍内接收请求: ") {
      bool(dut.io.req.ready) should be(true)
    }
    bus.tick(dut.io.bus, dut.clock)
    dut.io.req.valid.poke(false.B)
  }

  private def waitMmuResponse(
      dut: ysyx_26030103_MMU,
      bus: PteBus
  ): (BigInt, Boolean) = {
    var result: Option[(BigInt, Boolean)] = None
    var guard = 0
    while (result.isEmpty && guard < 100) {
      bus.tick(dut.io.bus, dut.clock)
      if (bool(dut.io.resp.valid)) {
        result = Some((
          dut.io.resp.bits.paddr.peek().litValue,
          bool(dut.io.resp.bits.fault)
        ))
      }
      guard += 1
    }
    withClue(s"MMU 未在 $guard 拍内返回响应: ") { result.isEmpty should be(false) }
    result.get
  }

  private def waitMmuIdle(dut: ysyx_26030103_MMU, bus: PteBus): Unit = {
    var guard = 0
    while (!bool(dut.io.req.ready) && guard < 30) {
      bus.tick(dut.io.bus, dut.clock)
      guard += 1
    }
    withClue(s"MMU 未在 $guard 拍内恢复接收请求: ") {
      bool(dut.io.req.ready) should be(true)
    }
  }

  private def seedTwoLevel(bus: PteBus, leaf: BigInt): Unit = {
    bus.write(Root, pte(0x80002L, 0x1L))
    bus.write(0x80002004L, leaf)
  }

  private def seedTwoLevel(bus: MultiBus, leaf: BigInt): Unit = {
    bus.write(Root, pte(0x80002L, 0x1L))
    bus.write(0x80002004L, leaf)
  }

  "MMU flush" should
    "与 PTW 叶子响应同拍时不得让旧 PTE 留在 PTW cache" in {
    val bus = new PteBus(latency = 1)
    seedTwoLevel(bus, pte(LeafPPN, 0xdfL))
    bus.holdReadAddr = Some(0x80002004L)
    simulate(new ysyx_26030103_MMU(mmuProfile())) { dut =>
      initMmu(dut, bus)
      issueMmu(dut, bus, 0x1234L)
      var guard = 0
      while (!bus.heldPending && guard < 50) {
        bus.tick(dut.io.bus, dut.clock)
        guard += 1
      }
      withClue("MMU 叶子 PTE 读未被挂起: ") { bus.heldPending should be(true) }

      bus.write(0x80002004L, pte(NewLeafPPN, 0xdfL))
      bus.releaseRead()
      dut.io.flush.poke(true.B)
      bus.tick(dut.io.bus, dut.clock)
      dut.io.flush.poke(false.B)
      withClue("MMU flush 当拍叶子响应未被接收: ") {
        bus.hasPending should be(false)
      }

      waitMmuIdle(dut, bus)
      // flush 必须丢弃旧请求的响应；不能把 flush 前的翻译结果返回。
      var stray = 0
      for (_ <- 0 until 6) {
        bus.tick(dut.io.bus, dut.clock)
        if (bool(dut.io.resp.valid)) stray += 1
      }
      withClue("MMU flush 后仍返回了旧请求的响应: ") { stray should be(0) }

      issueMmu(dut, bus, 0x1234L)
      val (paddr, fault) = waitMmuResponse(dut, bus)
      fault should be(false)
      withClue("MMU flush 同拍响应后仍命中旧 PTW cache 表项: ") {
        paddr should be(BigInt(NewLeaf + 0x234L))
      }
    }
  }

  it should "排空 reqQueue 中尚未分配上下文的旧 satp 请求" in {
    val bus = new PteBus(latency = 1)
    seedTwoLevel(bus, pte(LeafPPN, 0xdfL))
    // 第二个请求使用旧 satp，但会在 flush 前留在 reqQueue。
    bus.holdReadAddr = Some(0x80002004L)
    simulate(new ysyx_26030103_MMU(mmuProfile())) { dut =>
      initMmu(dut, bus)
      issueMmu(dut, bus, 0x1234L)
      var guard = 0
      while (!bus.heldPending && guard < 50) {
        bus.tick(dut.io.bus, dut.clock)
        guard += 1
      }
      withClue("首笔请求未停在叶子 PTE 读: ") { bus.heldPending should be(true) }

      // 第二笔请求进入 reqQueue 后 flush。它属于旧 satp，绝不能在新
      // 地址空间下继续翻译。
      issueMmu(dut, bus, 0x2345L)

      dut.io.flush.poke(true.B)
      bus.tick(dut.io.bus, dut.clock)
      dut.io.flush.poke(false.B)

      // 旧 walk 的响应在 flush 后返回，MMU 只用于排空 PTW。
      bus.releaseRead()
      bus.tick(dut.io.bus, dut.clock)
      waitMmuIdle(dut, bus)

      // 排空期间不得返回任何响应；新页表使用不同根表。
      var stray = 0
      for (_ <- 0 until 10) {
        bus.tick(dut.io.bus, dut.clock)
        if (bool(dut.io.resp.valid)) stray += 1
      }
      withClue("被 flush 的 reqQueue 请求仍产生了翻译响应: ") { stray should be(0) }

      // 新地址空间：新根表和新叶子。
      val NewRootPPN = 0x80005L
      val NewRoot = NewRootPPN << 12
      bus.write(NewRoot, pte(0x80006L, 0x1L))
      bus.write(0x80006004L, pte(0x80007L, 0xdfL))
      val newSatp = (0x80000000L | NewRootPPN).U(32.W)
      issueMmu(dut, bus, 0x1234L, newSatp)
      val (paddr, fault) = waitMmuResponse(dut, bus)
      fault should be(false)
      withClue("flush 后的新请求未按新 satp 翻译: ") {
        paddr should be(BigInt((0x80007L << 12) + 0x234L))
      }
    }
  }

  "TLB hardware A/D" should
    "A=0 或 store 且 D=0 的命中必须降级为 miss" in {
    simulate(new ysyx_26030103_TLB(
      entries = 4,
      ways = 1,
      asidBits = 9,
      globalEnable = false,
      adMode = "hardware"
    )) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.flush.poke(true.B)
      dut.io.fillValid.poke(false.B)
      dut.io.fillVaddr.poke(0.U)
      dut.io.fillAsid.poke(0.U)
      dut.io.fillPte.poke(0.U)
      dut.io.fillLevel.poke(0.U)
      dut.io.lookup(0).vaddr.poke(0.U)
      dut.io.lookup(0).asid.poke(0.U)
      dut.io.lookup(0).privilege.poke(0.U)
      dut.io.lookup(0).access.poke(ysyx_26030103_AccessType.Load)
      dut.io.lookup(0).sum.poke(false.B)
      dut.io.lookup(0).mxr.poke(false.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)

      def fill(flags: Long): Unit = {
        dut.io.fillValid.poke(true.B)
        dut.io.fillVaddr.poke(0x1234.U)
        dut.io.fillAsid.poke(0.U)
        dut.io.fillPte.poke(pte(LeafPPN, flags).U(32.W))
        dut.io.fillLevel.poke(0.U)
        dut.clock.step()
        dut.io.fillValid.poke(false.B)
        dut.io.lookup(0).vaddr.poke(0x1234.U)
        dut.io.lookup(0).asid.poke(0.U)
        dut.io.lookup(0).privilege.poke(0.U)
      }

      // A=0：load 也不能直接命中，必须让 PTW 写回 A。
      fill(0x1fL)
      dut.io.lookup(0).access.poke(ysyx_26030103_AccessType.Load)
      withClue("A=0 的 load 命中未降级为 miss: ") {
        bool(dut.io.result(0).hit) should be(false)
      }

      // A=1,D=0：load 命中，store 必须降级为 miss 以写回 D。
      fill(0x5fL)
      dut.io.lookup(0).access.poke(ysyx_26030103_AccessType.Load)
      withClue("A=1,D=0 的 load 不应降级: ") {
        bool(dut.io.result(0).hit) should be(true)
      }
      bool(dut.io.result(0).fault) should be(false)
      dut.io.lookup(0).access.poke(ysyx_26030103_AccessType.Store)
      withClue("store 命中 D=0 表项未降级为 miss: ") {
        bool(dut.io.result(0).hit) should be(false)
      }

      // A=1,D=1：store 正常命中。
      fill(0xdfL)
      dut.io.lookup(0).access.poke(ysyx_26030103_AccessType.Store)
      withClue("A=1,D=1 的 store 不应降级: ") {
        bool(dut.io.result(0).hit) should be(true)
      }
      bool(dut.io.result(0).fault) should be(false)
    }
  }

  "MMU TLB hardware A/D" should
    "store 命中 D=0 表项时重填并写回 D，之后命中不再写" in {
    val bus = new PteBus(latency = 1)
    // 根 PTE + 叶子 PTE：V|R|W|X|U|A，D=0。
    seedTwoLevel(bus, pte(LeafPPN, 0x5fL))
    val profile = ysyx_26030103_MMUProfile(
      enabled = true,
      tlbEnabled = true,
      tlbOrganization = "split",
      adMode = "hardware",
      ptwOutstanding = 1,
      ptwCache = true,
      ptwCacheEntries = 16,
      ptwCacheWays = 2
    )
    simulate(new ysyx_26030103_MMU(profile)) { dut =>
      initMmu(dut, bus)

      // load 填 DTLB：A=1 无需写回，D=0 原样进入 TLB。
      issueMmu(dut, bus, 0x1234L)
      val (loadPaddr, loadFault) = waitMmuResponse(dut, bus)
      loadFault should be(false)
      loadPaddr should be(BigInt(Leaf + 0x234L))
      withClue("A=1 的 load 不应产生页表写: ") { bus.writes should be(0) }

      // 同页 store 命中 A=1,D=0 的 DTLB 表项：必须重新 walk 写回 D。
      issueMmu(dut, bus, 0x1234L, access = ysyx_26030103_AccessType.Store)
      val (storePaddr, storeFault) = waitMmuResponse(dut, bus)
      storeFault should be(false)
      storePaddr should be(BigInt(Leaf + 0x234L))
      withClue("store 命中 D=0 表项后页表 D 仍未写回: ") {
        (bus.read(0x80002004L) & BigInt(0x80)) should be(BigInt(0x80))
      }
      withClue("store 命中 D=0 表项应产生一次 D 写回: ") {
        bus.writes should be(1)
      }
      waitMmuIdle(dut, bus)

      // D=1 已回填 DTLB；再次 store/load 必须直接命中，不再访问页表。
      val readsAfterWrite = bus.reads
      val writesAfterWrite = bus.writes
      issueMmu(dut, bus, 0x1234L, access = ysyx_26030103_AccessType.Store)
      val (store2Paddr, store2Fault) = waitMmuResponse(dut, bus)
      store2Fault should be(false)
      store2Paddr should be(BigInt(Leaf + 0x234L))
      withClue("D 写回后再次 store 不应重新访问页表: ") {
        bus.reads should be(readsAfterWrite)
        bus.writes should be(writesAfterWrite)
      }

      issueMmu(dut, bus, 0x1234L)
      val (load2Paddr, load2Fault) = waitMmuResponse(dut, bus)
      load2Fault should be(false)
      load2Paddr should be(BigInt(Leaf + 0x234L))
      withClue("D 写回后同页 load 不应重新访问页表: ") {
        bus.reads should be(readsAfterWrite)
        bus.writes should be(writesAfterWrite)
      }
    }
  }

  "MMU pipelined PTW" should
    "flush 后同一 PTE 地址的迟到旧响应不得满足或污染新 walk" in {
    val bus = new MultiBus(latency = 1)
    val leafAddr = 0x80002004L
    seedTwoLevel(bus, pte(LeafPPN, 0xdfL))
    bus.holdOnceAddr = Some(leafAddr)
    val profile = ysyx_26030103_MMUProfile(
      enabled = true,
      tlbEnabled = false,
      ptwArch = "pipelined",
      ptwOutstanding = 2,
      ptwBusOutstanding = 2,
      ptwCache = true,
      ptwCacheEntries = 16,
      ptwCacheWays = 2
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
        dut.io.req.bits.satp.poke(Satp)
        dut.io.req.valid.poke(true.B)
        var guard = 0
        while (!bool(dut.io.req.ready) && guard < 80) {
          bus.tick(dut.io.bus, dut.clock)
          guard += 1
        }
        withClue(s"pipelined MMU 未在 $guard 拍内接收请求: ") {
          bool(dut.io.req.ready) should be(true)
        }
        bus.tick(dut.io.bus, dut.clock)
        dut.io.req.valid.poke(false.B)
      }

      def waitResponse(): (BigInt, Boolean) = {
        var result: Option[(BigInt, Boolean)] = None
        var guard = 0
        while (result.isEmpty && guard < 120) {
          bus.tick(dut.io.bus, dut.clock)
          if (bool(dut.io.resp.valid)) {
            result = Some((
              dut.io.resp.bits.paddr.peek().litValue,
              bool(dut.io.resp.bits.fault)
            ))
          }
          guard += 1
        }
        withClue(s"pipelined MMU 未在 $guard 拍内返回响应: ") {
          result.isEmpty should be(false)
        }
        result.get
      }

      // 旧 walk 停在叶子 PTE 读上。
      issue(0x1234L)
      var guard = 0
      while (!bus.heldPending && guard < 60) {
        bus.tick(dut.io.bus, dut.clock)
        guard += 1
      }
      withClue("pipelined MMU 叶子读未被挂起: ") { bus.heldPending should be(true) }

      // sfence 后同一 PTE 地址改成新叶子，旧响应仍然挂起。
      dut.io.flush.poke(true.B)
      bus.tick(dut.io.bus, dut.clock)
      dut.io.flush.poke(false.B)
      bus.write(leafAddr, pte(NewLeafPPN, 0xdfL))

      // 新 walk 必须能拿到同一地址的新 PTE，并在旧响应尚未返回时就完成。
      issue(0x1234L)
      val (paddr, fault) = waitResponse()
      fault should be(false)
      withClue("flush 后的新 walk 被迟到旧响应污染: ") {
        paddr should be(BigInt(NewLeaf + 0x234L))
      }
      withClue("新 walk 不应等待旧响应先返回: ") {
        bus.heldPending should be(true)
      }

      // 释放旧响应：只应被 epoch 丢弃，不能产生第二次翻译或污染 cache。
      bus.release()
      for (_ <- 0 until 8) {
        bus.tick(dut.io.bus, dut.clock)
        withClue("释放迟到旧响应后又返回了一次旧翻译: ") {
          bool(dut.io.resp.valid) should be(false)
        }
      }

      // 再翻译同一地址应从 PTW cache 命中新 PTE，且不再读旧 PTE 地址。
      val readsBefore = bus.reads
      issue(0x1234L)
      val (paddr2, fault2) = waitResponse()
      fault2 should be(false)
      withClue("迟到旧响应污染了 PTW cache: ") {
        paddr2 should be(BigInt(NewLeaf + 0x234L))
      }
      withClue("第三次翻译不应再次访问页表总线: ") {
        bus.reads should be(readsBefore)
      }
    }
  }

  "MMU flush" should "与 TLB 回填同拍时不得把旧 PTE 装入 TLB" in {
    val bus = new PteBus(latency = 1)
    seedTwoLevel(bus, pte(LeafPPN, 0xdfL))
    bus.holdReadAddr = Some(0x80002004L)
    simulate(new ysyx_26030103_MMU(mmuProfile(tlbEnabled = true))) { dut =>
      initMmu(dut, bus)
      issueMmu(dut, bus, 0x1234L)
      var guard = 0
      while (!bus.heldPending && guard < 50) {
        bus.tick(dut.io.bus, dut.clock)
        guard += 1
      }
      withClue("DTLB miss 的叶子读未被挂起: ") { bus.heldPending should be(true) }

      bus.write(0x80002004L, pte(NewLeafPPN, 0xdfL))
      bus.releaseRead()
      dut.io.flush.poke(true.B)
      bus.tick(dut.io.bus, dut.clock)
      dut.io.flush.poke(false.B)
      waitMmuIdle(dut, bus)

      issueMmu(dut, bus, 0x1234L)
      val (paddr, fault) = waitMmuResponse(dut, bus)
      fault should be(false)
      withClue("flush 后 TLB 仍命中 flush 前回填的旧表项: ") {
        paddr should be(BigInt(NewLeaf + 0x234L))
      }
    }
  }
}
