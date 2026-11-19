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
  private def PTE(PPN: Long, flags: Long): BigInt =
    (BigInt(PPN) << 10) | BigInt(flags)
  private val Satp = (0x80000000L | RootPPN).U(32.W)

  /** 单事务页表总线，可分别挂起指定地址的读响应或任意写响应。 */
  private class PTEBus(var latency: Int = 1) {
    private case class Pending(ID: Int, addr: Long, data: BigInt, write: Boolean)
    private val mem = scala.collection.mutable.Map.empty[Long, BigInt]
    private var pending: Option[Pending] = None
    private var delay = 0
    var holdReadAddress: Option[Long] = None
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
      else holdReadAddress.contains(p.addr)
    def heldPending: Boolean = pending.exists(held)
    def releaseRead(): Unit = { holdReadAddress = None; delay = 0 }
    def releaseWrite(): Unit = { holdWrite = false; delay = 0 }

    def tick(bus: ysyx_26030103_PTEBusIO, clock: Clock): Unit = {
      val drive = pending.nonEmpty && delay == 0 && !pending.exists(held)
      bus.response.valid.poke(drive.B)
      bus.response.bits.ID.poke(pending.map(_.ID.U(4.W)).getOrElse(0.U(4.W)))
      bus.response.bits.data.poke(
        pending.map(_.data.U(32.W)).getOrElse(0.U(32.W)))
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
          pending = Some(Pending(RequestID, RequestAddress, BigInt(0), write = true))
        } else {
          reads += 1
          pending = Some(Pending(
            RequestID, RequestAddress, mem.getOrElse(RequestAddress & 0xffffffffL, BigInt(0)),
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
        val ID: Int,
        val addr: Long,
        val data: BigInt,
        val write: Boolean,
        var remain: Int,
        var held: Boolean)
    private val mem = scala.collection.mutable.Map.empty[Long, BigInt]
    private val pending = scala.collection.mutable.ArrayBuffer.empty[Pending]
    var holdOnceAddress: Option[Long] = None
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
      val readyIndex = pending.indexWhere(p => p.remain == 0 && !p.held)
      val ResponseValid = readyIndex >= 0
      bus.response.valid.poke(ResponseValid.B)
      bus.response.bits.ID.poke(
        (if (ResponseValid) pending(readyIndex).ID else 0).U(4.W))
      bus.response.bits.data.poke(
        (if (ResponseValid) pending(readyIndex).data else BigInt(0)).U(32.W))
      bus.response.bits.fault.poke(false.B)

      val ResponseFire = ResponseValid && bool(bus.response.ready)
      val RequestFire = bool(bus.request.valid) && bool(bus.request.ready)
      val RequestID = if (RequestFire) long(bus.request.bits.ID).toInt else 0
      val RequestAddress = if (RequestFire) long(bus.request.bits.address) else 0L
      val RequestWrite = RequestFire && bool(bus.request.bits.write)
      val RequestData =
        if (RequestFire) bus.request.bits.data.peek().litValue else BigInt(0)

      clock.step()

      if (ResponseFire) pending.remove(readyIndex)
      else pending.foreach(p => if (p.remain > 0) p.remain -= 1)

      if (RequestFire) {
        val isHeld = !RequestWrite && !holdUsed &&
          holdOnceAddress.contains(RequestAddress & 0xffffffffL)
        if (isHeld) holdUsed = true
        if (RequestWrite) {
          writes += 1
          mem(RequestAddress & 0xffffffffL) = RequestData & 0xffffffffL
          pending += new Pending(RequestID, RequestAddress, BigInt(0), true,
            latency - 1, false)
        } else {
          reads += 1
          pending += new Pending(
            RequestID, RequestAddress, mem.getOrElse(RequestAddress & 0xffffffffL, BigInt(0)),
            false, latency - 1, isHeld)
        }
      }
    }
  }

  private def initPTW(dut: ysyx_26030103_PTW, bus: PTEBus): Unit = {
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
    dut.io.request.bits.satp.poke(Satp)
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
    dut.io.request.bits.satp.poke(Satp)
    dut.io.request.bits.privilege.poke(3.U(2.W))
    dut.io.request.bits.access.poke(access)
    dut.io.request.valid.poke(true.B)
    var guard = 0
    while (!bool(dut.io.request.ready) && guard < 40) {
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
    withClue(s"PTW 未在 $guard 拍内返回响应: ") { result.isEmpty should be(false) }
    result.get
  }

  private def waitPTWIdle(dut: ysyx_26030103_PTW, bus: PTEBus): Unit = {
    var guard = 0
    while (!bool(dut.io.request.ready) && guard < 20) {
      bus.tick(dut.io.bus, dut.clock)
      guard += 1
    }
    withClue(s"PTW 未在 $guard 拍内回到 idle: ") {
      bool(dut.io.request.ready) should be(true)
    }
  }

  private def ptw8(adMode: String = "fault", cache: Boolean = true) =
    new ysyx_26030103_PTW(
      adMode = adMode,
      cacheEnable = cache,
      cacheEntries = 16,
      cacheWays = 2,
      PTESizeBytes = 8,
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
      PTESizeBytes = 4,
      PTEConfig = ysyx_26030103_PTEConfig(PTEBits = 32, PPNHigh = 31),
      levels = 1,
      tableEntries = 1024,
      pageBits = 12,
      XLEN = 32,
      addressWidth = 32,
      vaBits = 22
    )

  "PTW waitHigh" should
    "flush 与 8B PTE 高字响应同拍时不得把旧 PTE 回填 cache" in {
    val bus = new PTEBus(latency = 1)
    // vaddr=0x1234 的索引为 1；8B PTE 的地址是 Root+8，占 Root+8/12。
    val PTEAddress = Root + 0x8L
    bus.write(PTEAddress, PTE(LeafPPN, 0xdfL))
    bus.write(PTEAddress + 4L, 0L)
    bus.holdReadAddress = Some(PTEAddress + 4L)
    simulate(ptw8()) { dut =>
      initPTW(dut, bus)
      issuePTW(dut, bus, 0x1234L)
      var guard = 0
      while (!bus.heldPending && guard < 40) {
        bus.tick(dut.io.bus, dut.clock)
        guard += 1
      }
      withClue(s"8B PTE 高字读未被挂起（$guard 拍）: ") {
        bus.heldPending should be(true)
      }

      // sfence 后软件把同一 PTE 改到另一物理页。
      bus.write(PTEAddress, PTE(NewLeafPPN, 0xdfL))
      bus.write(PTEAddress + 4L, 0L)

      // 高字响应与 flush 同拍返回。
      bus.releaseRead()
      dut.io.flush.poke(true.B)
      bus.tick(dut.io.bus, dut.clock)
      dut.io.flush.poke(false.B)
      withClue("flush 当拍高字响应未被接收: ") { bus.hasPending should be(false) }

      waitPTWIdle(dut, bus)

      issuePTW(dut, bus, 0x1234L)
      val (paddr, fault) = waitPTWResponse(dut, bus)
      fault should be(false)
      withClue("flush 后仍命中 8B PTE 高字路径回填的旧 PTE: ") {
        paddr should be(BigInt(NewLeaf + 0x234L))
      }
    }
  }

  "PTW waitWrite" should
    "flush 与 hardware A/D 写响应同拍时不得把旧 PTE 回填 cache" in {
    val bus = new PTEBus(latency = 1)
    val PTEAddress = Root + 0x4L
    // V|R|W|X，A=0：硬件 A/D 模式先写回 A 位。
    bus.write(PTEAddress, PTE(LeafPPN, 0x0fL))
    bus.holdWrite = true
    simulate(ptw4(adMode = "hardware")) { dut =>
      initPTW(dut, bus)
      issuePTW(dut, bus, 0x1234L)
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
      bus.write(PTEAddress, PTE(NewLeafPPN, 0xdfL))

      // 写响应与 flush 同拍返回。
      bus.releaseWrite()
      dut.io.flush.poke(true.B)
      bus.tick(dut.io.bus, dut.clock)
      dut.io.flush.poke(false.B)
      withClue("flush 当拍写响应未被接收: ") { bus.hasPending should be(false) }

      waitPTWIdle(dut, bus)

      issuePTW(dut, bus, 0x1234L)
      val (paddr, fault) = waitPTWResponse(dut, bus)
      fault should be(false)
      withClue("flush 后仍命中 A/D 写回路径缓存的旧 PTE: ") {
        paddr should be(BigInt(NewLeaf + 0x234L))
      }
    }
  }

  "PTW hardware A/D cache" should
    "store 命中 PTW cache 时仍必须写回 D 位" in {
    val bus = new PTEBus(latency = 1)
    val PTEAddress = Root + 0x4L
    // V|R|W|X，A=0,D=0；第一次 load 只写回 A。
    bus.write(PTEAddress, PTE(LeafPPN, 0x0fL))
    simulate(ptw4(adMode = "hardware")) { dut =>
      initPTW(dut, bus)
      issuePTW(dut, bus, 0x1234L, ysyx_26030103_AccessType.Load)
      val (loadPhysicalAddress, loadFault) = waitPTWResponse(dut, bus)
      loadFault should be(false)
      loadPhysicalAddress should be(BigInt(Leaf + 0x234L))
      withClue("load 应写回 A 位: ") {
        (bus.read(PTEAddress) & BigInt(0x40)) should be(BigInt(0x40))
      }
      withClue("load 不应写回 D 位: ") {
        (bus.read(PTEAddress) & BigInt(0x80)) should be(BigInt(0))
      }
      withClue("load 应产生一次 A/D 写: ") { bus.writes should be(1) }
      waitPTWIdle(dut, bus)

      // 同页 store 命中 PTW cache：硬件 A/D 仍必须把 D 写回页表。
      issuePTW(dut, bus, 0x1234L, ysyx_26030103_AccessType.Store)
      val (storePhysicalAddress, storeFault) = waitPTWResponse(dut, bus)
      storeFault should be(false)
      storePhysicalAddress should be(BigInt(Leaf + 0x234L))
      withClue("store 命中 PTW cache 后页表 D 仍为 0: ") {
        (bus.read(PTEAddress) & BigInt(0x80)) should be(BigInt(0x80))
      }
      withClue("store 命中 PTW cache 应再产生一次 D 写回: ") {
        bus.writes should be(2)
      }
    }
  }

  "PTWPipeline hardware A/D cache" should
    "store 命中 PTW cache 时仍必须写回 D 位" in {
    val bus = new PTEBus(latency = 1)
    val PTEAddress = Root + 0x4L
    bus.write(PTEAddress, PTE(LeafPPN, 0x0fL))
    simulate(new ysyx_26030103_PTWPipeline(
      outstanding = 1,
      adMode = "hardware",
      cacheEnable = true,
      cacheEntries = 16,
      cacheWays = 2,
      PTESizeBytes = 4,
      PTEConfig = ysyx_26030103_PTEConfig(PTEBits = 32, PPNHigh = 31),
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
      dut.io.request.valid.poke(false.B)
      dut.io.request.bits.poke(
        0.U.asTypeOf(new ysyx_26030103_PTWRequest(32, 32)))
      dut.io.bus.request.ready.poke(true.B)
      dut.io.bus.response.valid.poke(false.B)
      dut.io.bus.response.bits.poke(
        0.U.asTypeOf(new ysyx_26030103_PTEBusResponse))
      dut.io.response(0).ready.poke(true.B)
      dut.io.flush.poke(false.B)

      def issue(access: UInt): Unit = {
        dut.io.request.bits.ID.poke(0.U(4.W))
        dut.io.request.bits.vaddr.poke(0x1234.U(32.W))
        dut.io.request.bits.satp.poke(Satp)
        dut.io.request.bits.privilege.poke(3.U(2.W))
        dut.io.request.bits.access.poke(access)
        dut.io.request.bits.sum.poke(false.B)
        dut.io.request.bits.mxr.poke(false.B)
        dut.io.request.valid.poke(true.B)
        var guard = 0
        while (!bool(dut.io.request.ready) && guard < 40) {
          bus.tick(dut.io.bus, dut.clock)
          guard += 1
        }
        withClue(s"PTWPipeline 未在 $guard 拍内接收请求: ") {
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
          if (bool(dut.io.response(0).valid)) {
            result = Some((
              dut.io.response(0).bits.paddr.peek().litValue,
              bool(dut.io.response(0).bits.fault)
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
      val (loadPhysicalAddress, loadFault) = waitResponse()
      loadFault should be(false)
      loadPhysicalAddress should be(BigInt(Leaf + 0x234L))
      withClue("load 应写回 A 位: ") {
        (bus.read(PTEAddress) & BigInt(0x40)) should be(BigInt(0x40))
      }
      withClue("load 应产生一次 A 写: ") { bus.writes should be(1) }

      issue(ysyx_26030103_AccessType.Store)
      val (storePhysicalAddress, storeFault) = waitResponse()
      storeFault should be(false)
      storePhysicalAddress should be(BigInt(Leaf + 0x234L))
      withClue("store 命中 PTW cache 后页表 D 仍为 0: ") {
        (bus.read(PTEAddress) & BigInt(0x80)) should be(BigInt(0x80))
      }
      withClue("store 命中 PTW cache 应再产生一次 D 写回: ") {
        bus.writes should be(2)
      }
    }
  }

  "PTWPipeline" should
    "flush 与 cache fill 同拍时不得留下旧 PTE" in {
    val bus = new PTEBus(latency = 1)
    val PTEAddress = Root + 0x4L
    bus.write(PTEAddress, PTE(LeafPPN, 0xdfL))
    bus.holdReadAddress = Some(PTEAddress)
    simulate(new ysyx_26030103_PTWPipeline(
      outstanding = 1,
      adMode = "fault",
      cacheEnable = true,
      cacheEntries = 16,
      cacheWays = 2,
      PTESizeBytes = 4,
      PTEConfig = ysyx_26030103_PTEConfig(PTEBits = 32, PPNHigh = 31),
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
      dut.io.request.valid.poke(false.B)
      dut.io.request.bits.poke(
        0.U.asTypeOf(new ysyx_26030103_PTWRequest(32, 32)))
      dut.io.bus.request.ready.poke(true.B)
      dut.io.bus.response.valid.poke(false.B)
      dut.io.bus.response.bits.poke(
        0.U.asTypeOf(new ysyx_26030103_PTEBusResponse))
      dut.io.response(0).ready.poke(true.B)
      dut.io.flush.poke(false.B)

      def issue(vaddr: Long): Unit = {
        dut.io.request.bits.ID.poke(0.U(4.W))
        dut.io.request.bits.vaddr.poke(vaddr.U(32.W))
        dut.io.request.bits.satp.poke(Satp)
        dut.io.request.bits.privilege.poke(3.U(2.W))
        dut.io.request.bits.access.poke(ysyx_26030103_AccessType.Load)
        dut.io.request.bits.sum.poke(false.B)
        dut.io.request.bits.mxr.poke(false.B)
        dut.io.request.valid.poke(true.B)
        var guard = 0
        while (!bool(dut.io.request.ready) && guard < 40) {
          bus.tick(dut.io.bus, dut.clock)
          guard += 1
        }
        withClue(s"PTWPipeline 未在 $guard 拍内接收请求: ") {
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
          if (bool(dut.io.response(0).valid)) {
            result = Some((
              dut.io.response(0).bits.paddr.peek().litValue,
              bool(dut.io.response(0).bits.fault)
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

      bus.write(PTEAddress, PTE(NewLeafPPN, 0xdfL))
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

  private def MMUProfile(
      PTWArch: String = "independent",
      TLBEnabled: Boolean = false
  ): ysyx_26030103_MMUProfile =
    ysyx_26030103_MMUProfile.fromEnv(_ => None, xlen = 32).copy(
      enabled = true,
      TLBEnabled = TLBEnabled,
      PTWArch = PTWArch,
      PTWOutstanding = 1,
      PTWCache = true,
      PTWCacheEntries = 16,
      PTWCacheWays = 2
    )

  private def initMMU(dut: ysyx_26030103_MMU, bus: PTEBus): Unit = {
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
  }

  private def issueMMU(
      dut: ysyx_26030103_MMU,
      bus: PTEBus,
      vaddr: Long,
      satp: UInt = Satp,
      access: UInt = ysyx_26030103_AccessType.Load
  ): Unit = {
    dut.io.request.bits.ID.poke(0.U(4.W))
    dut.io.request.bits.vaddr.poke(vaddr.U(32.W))
    dut.io.request.bits.access.poke(access)
    dut.io.request.bits.privilege.poke(0.U(2.W))
    dut.io.request.bits.sum.poke(false.B)
    dut.io.request.bits.mxr.poke(false.B)
    dut.io.request.bits.satp.poke(satp)
    dut.io.request.valid.poke(true.B)
    var guard = 0
    while (!bool(dut.io.request.ready) && guard < 60) {
      bus.tick(dut.io.bus, dut.clock)
      guard += 1
    }
    withClue(s"MMU 未在 $guard 拍内接收请求: ") {
      bool(dut.io.request.ready) should be(true)
    }
    bus.tick(dut.io.bus, dut.clock)
    dut.io.request.valid.poke(false.B)
  }

  private def waitMMUResponse(
      dut: ysyx_26030103_MMU,
      bus: PTEBus
  ): (BigInt, Boolean) = {
    var result: Option[(BigInt, Boolean)] = None
    var guard = 0
    while (result.isEmpty && guard < 100) {
      bus.tick(dut.io.bus, dut.clock)
      if (bool(dut.io.response.valid)) {
        result = Some((
          dut.io.response.bits.paddr.peek().litValue,
          bool(dut.io.response.bits.fault)
        ))
      }
      guard += 1
    }
    withClue(s"MMU 未在 $guard 拍内返回响应: ") { result.isEmpty should be(false) }
    result.get
  }

  private def waitMMUIdle(dut: ysyx_26030103_MMU, bus: PTEBus): Unit = {
    var guard = 0
    while (!bool(dut.io.request.ready) && guard < 30) {
      bus.tick(dut.io.bus, dut.clock)
      guard += 1
    }
    withClue(s"MMU 未在 $guard 拍内恢复接收请求: ") {
      bool(dut.io.request.ready) should be(true)
    }
  }

  private def seedTwoLevel(bus: PTEBus, leaf: BigInt): Unit = {
    bus.write(Root, PTE(0x80002L, 0x1L))
    bus.write(0x80002004L, leaf)
  }

  private def seedTwoLevel(bus: MultiBus, leaf: BigInt): Unit = {
    bus.write(Root, PTE(0x80002L, 0x1L))
    bus.write(0x80002004L, leaf)
  }

  "MMU flush" should
    "与 PTW 叶子响应同拍时不得让旧 PTE 留在 PTW cache" in {
    val bus = new PTEBus(latency = 1)
    seedTwoLevel(bus, PTE(LeafPPN, 0xdfL))
    bus.holdReadAddress = Some(0x80002004L)
    simulate(new ysyx_26030103_MMU(MMUProfile())) { dut =>
      initMMU(dut, bus)
      issueMMU(dut, bus, 0x1234L)
      var guard = 0
      while (!bus.heldPending && guard < 50) {
        bus.tick(dut.io.bus, dut.clock)
        guard += 1
      }
      withClue("MMU 叶子 PTE 读未被挂起: ") { bus.heldPending should be(true) }

      bus.write(0x80002004L, PTE(NewLeafPPN, 0xdfL))
      bus.releaseRead()
      dut.io.flush.poke(true.B)
      bus.tick(dut.io.bus, dut.clock)
      dut.io.flush.poke(false.B)
      withClue("MMU flush 当拍叶子响应未被接收: ") {
        bus.hasPending should be(false)
      }

      waitMMUIdle(dut, bus)
      // flush 必须丢弃旧请求的响应；不能把 flush 前的翻译结果返回。
      var stray = 0
      for (_ <- 0 until 6) {
        bus.tick(dut.io.bus, dut.clock)
        if (bool(dut.io.response.valid)) stray += 1
      }
      withClue("MMU flush 后仍返回了旧请求的响应: ") { stray should be(0) }

      issueMMU(dut, bus, 0x1234L)
      val (paddr, fault) = waitMMUResponse(dut, bus)
      fault should be(false)
      withClue("MMU flush 同拍响应后仍命中旧 PTW cache 表项: ") {
        paddr should be(BigInt(NewLeaf + 0x234L))
      }
    }
  }

  it should "排空 reqQueue 中尚未分配上下文的旧 satp 请求" in {
    val bus = new PTEBus(latency = 1)
    seedTwoLevel(bus, PTE(LeafPPN, 0xdfL))
    // 第二个请求使用旧 satp，但会在 flush 前留在 reqQueue。
    bus.holdReadAddress = Some(0x80002004L)
    simulate(new ysyx_26030103_MMU(MMUProfile())) { dut =>
      initMMU(dut, bus)
      issueMMU(dut, bus, 0x1234L)
      var guard = 0
      while (!bus.heldPending && guard < 50) {
        bus.tick(dut.io.bus, dut.clock)
        guard += 1
      }
      withClue("首笔请求未停在叶子 PTE 读: ") { bus.heldPending should be(true) }

      // 第二笔请求进入 reqQueue 后 flush。它属于旧 satp，绝不能在新
      // 地址空间下继续翻译。
      issueMMU(dut, bus, 0x2345L)

      dut.io.flush.poke(true.B)
      bus.tick(dut.io.bus, dut.clock)
      dut.io.flush.poke(false.B)

      // 旧 walk 的响应在 flush 后返回，MMU 只用于排空 PTW。
      bus.releaseRead()
      bus.tick(dut.io.bus, dut.clock)
      waitMMUIdle(dut, bus)

      // 排空期间不得返回任何响应；新页表使用不同根表。
      var stray = 0
      for (_ <- 0 until 10) {
        bus.tick(dut.io.bus, dut.clock)
        if (bool(dut.io.response.valid)) stray += 1
      }
      withClue("被 flush 的 reqQueue 请求仍产生了翻译响应: ") { stray should be(0) }

      // 新地址空间：新根表和新叶子。
      val NewRootPPN = 0x80005L
      val NewRoot = NewRootPPN << 12
      bus.write(NewRoot, PTE(0x80006L, 0x1L))
      bus.write(0x80006004L, PTE(0x80007L, 0xdfL))
      val newSatp = (0x80000000L | NewRootPPN).U(32.W)
      issueMMU(dut, bus, 0x1234L, newSatp)
      val (paddr, fault) = waitMMUResponse(dut, bus)
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
      ASIDBits = 9,
      globalEnable = false,
      adMode = "hardware"
    )) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.flush.poke(true.B)
      dut.io.fillValid.poke(false.B)
      dut.io.fillVirtualAddress.poke(0.U)
      dut.io.FillASID.poke(0.U)
      dut.io.FillPTE.poke(0.U)
      dut.io.fillLevel.poke(0.U)
      dut.io.lookup(0).vaddr.poke(0.U)
      dut.io.lookup(0).ASID.poke(0.U)
      dut.io.lookup(0).privilege.poke(0.U)
      dut.io.lookup(0).access.poke(ysyx_26030103_AccessType.Load)
      dut.io.lookup(0).sum.poke(false.B)
      dut.io.lookup(0).mxr.poke(false.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)

      def fill(flags: Long): Unit = {
        dut.io.fillValid.poke(true.B)
        dut.io.fillVirtualAddress.poke(0x1234.U)
        dut.io.FillASID.poke(0.U)
        dut.io.FillPTE.poke(PTE(LeafPPN, flags).U(32.W))
        dut.io.fillLevel.poke(0.U)
        dut.clock.step()
        dut.io.fillValid.poke(false.B)
        dut.io.lookup(0).vaddr.poke(0x1234.U)
        dut.io.lookup(0).ASID.poke(0.U)
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
    val bus = new PTEBus(latency = 1)
    // 根 PTE + 叶子 PTE：V|R|W|X|U|A，D=0。
    seedTwoLevel(bus, PTE(LeafPPN, 0x5fL))
    val profile = ysyx_26030103_MMUProfile.fromEnv(
      _ => None, xlen = 32).copy(
      enabled = true,
      TLBEnabled = true,
      TLBOrganization = "split",
      adMode = "hardware",
      PTWOutstanding = 1,
      PTWCache = true,
      PTWCacheEntries = 16,
      PTWCacheWays = 2
    )
    simulate(new ysyx_26030103_MMU(profile)) { dut =>
      initMMU(dut, bus)

      // load 填 DTLB：A=1 无需写回，D=0 原样进入 TLB。
      issueMMU(dut, bus, 0x1234L)
      val (loadPhysicalAddress, loadFault) = waitMMUResponse(dut, bus)
      loadFault should be(false)
      loadPhysicalAddress should be(BigInt(Leaf + 0x234L))
      withClue("A=1 的 load 不应产生页表写: ") { bus.writes should be(0) }

      // 同页 store 命中 A=1,D=0 的 DTLB 表项：必须重新 walk 写回 D。
      issueMMU(dut, bus, 0x1234L, access = ysyx_26030103_AccessType.Store)
      val (storePhysicalAddress, storeFault) = waitMMUResponse(dut, bus)
      storeFault should be(false)
      storePhysicalAddress should be(BigInt(Leaf + 0x234L))
      withClue("store 命中 D=0 表项后页表 D 仍未写回: ") {
        (bus.read(0x80002004L) & BigInt(0x80)) should be(BigInt(0x80))
      }
      withClue("store 命中 D=0 表项应产生一次 D 写回: ") {
        bus.writes should be(1)
      }
      waitMMUIdle(dut, bus)

      // D=1 已回填 DTLB；再次 store/load 必须直接命中，不再访问页表。
      val readsAfterWrite = bus.reads
      val writesAfterWrite = bus.writes
      issueMMU(dut, bus, 0x1234L, access = ysyx_26030103_AccessType.Store)
      val (store2PhysicalAddress, store2Fault) = waitMMUResponse(dut, bus)
      store2Fault should be(false)
      store2PhysicalAddress should be(BigInt(Leaf + 0x234L))
      withClue("D 写回后再次 store 不应重新访问页表: ") {
        bus.reads should be(readsAfterWrite)
        bus.writes should be(writesAfterWrite)
      }

      issueMMU(dut, bus, 0x1234L)
      val (load2PhysicalAddress, load2Fault) = waitMMUResponse(dut, bus)
      load2Fault should be(false)
      load2PhysicalAddress should be(BigInt(Leaf + 0x234L))
      withClue("D 写回后同页 load 不应重新访问页表: ") {
        bus.reads should be(readsAfterWrite)
        bus.writes should be(writesAfterWrite)
      }
    }
  }

  "MMU pipelined PTW" should
    "flush 后同一 PTE 地址的迟到旧响应不得满足或污染新 walk" in {
    val bus = new MultiBus(latency = 1)
    val leafAddress = 0x80002004L
    seedTwoLevel(bus, PTE(LeafPPN, 0xdfL))
    bus.holdOnceAddress = Some(leafAddress)
    val profile = ysyx_26030103_MMUProfile.fromEnv(
      _ => None, xlen = 32).copy(
      enabled = true,
      TLBEnabled = false,
      PTWArch = "pipelined",
      PTWOutstanding = 2,
      PTWBusOutstanding = 2,
      PTWCache = true,
      PTWCacheEntries = 16,
      PTWCacheWays = 2
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
        dut.io.request.bits.satp.poke(Satp)
        dut.io.request.valid.poke(true.B)
        var guard = 0
        while (!bool(dut.io.request.ready) && guard < 80) {
          bus.tick(dut.io.bus, dut.clock)
          guard += 1
        }
        withClue(s"pipelined MMU 未在 $guard 拍内接收请求: ") {
          bool(dut.io.request.ready) should be(true)
        }
        bus.tick(dut.io.bus, dut.clock)
        dut.io.request.valid.poke(false.B)
      }

      def waitResponse(): (BigInt, Boolean) = {
        var result: Option[(BigInt, Boolean)] = None
        var guard = 0
        while (result.isEmpty && guard < 120) {
          bus.tick(dut.io.bus, dut.clock)
          if (bool(dut.io.response.valid)) {
            result = Some((
              dut.io.response.bits.paddr.peek().litValue,
              bool(dut.io.response.bits.fault)
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
      bus.write(leafAddress, PTE(NewLeafPPN, 0xdfL))

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
          bool(dut.io.response.valid) should be(false)
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
    val bus = new PTEBus(latency = 1)
    seedTwoLevel(bus, PTE(LeafPPN, 0xdfL))
    bus.holdReadAddress = Some(0x80002004L)
    simulate(new ysyx_26030103_MMU(MMUProfile(TLBEnabled = true))) { dut =>
      initMMU(dut, bus)
      issueMMU(dut, bus, 0x1234L)
      var guard = 0
      while (!bus.heldPending && guard < 50) {
        bus.tick(dut.io.bus, dut.clock)
        guard += 1
      }
      withClue("DTLB miss 的叶子读未被挂起: ") { bus.heldPending should be(true) }

      bus.write(0x80002004L, PTE(NewLeafPPN, 0xdfL))
      bus.releaseRead()
      dut.io.flush.poke(true.B)
      bus.tick(dut.io.bus, dut.clock)
      dut.io.flush.poke(false.B)
      waitMMUIdle(dut, bus)

      issueMMU(dut, bus, 0x1234L)
      val (paddr, fault) = waitMMUResponse(dut, bus)
      fault should be(false)
      withClue("flush 后 TLB 仍命中 flush 前回填的旧表项: ") {
        paddr should be(BigInt(NewLeaf + 0x234L))
      }
    }
  }
}
