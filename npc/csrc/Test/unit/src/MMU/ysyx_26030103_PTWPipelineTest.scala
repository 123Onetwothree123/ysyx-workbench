package ysyx_26030103.mmu

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.ysyx_26030103.config.ysyx_26030103_PTEConfig
import _root_.ysyx_26030103.test.ysyx_26030103_TestCommon.rv32Profile
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** 流水化 PTW 的定向功能测试。
  *
  * 总线模型可以插入任意读延迟；测试在页表读等待期间继续送入下一笔请求，
  * 用于确认流水化实现不会把请求阻塞成单笔串行 walk。
  */
class ysyx_26030103_PTWPipelineTest extends AnyFlatSpec with Matchers {
  // 本套件按 Sv32（2 级、4B PTE、32 位 VA/PA）编写，使用公共 RV32 profile。
  private val Rv32PTE: ysyx_26030103_PTEConfig =
    ysyx_26030103_PTEConfig(PTEBits = 32, PPNHigh = 31)

  private val PageShift = 12
  private val RootPPN = 0x80001L
  private val Root = RootPPN << PageShift
  private val LeafPPN = 0x80003L

  private def PTE(PPN: Long, flags: Long): BigInt =
    ((PPN & 0x3fffffL) << 10) | flags

  private def asBool(value: Bool): Boolean = value.peek().litValue == 1
  private def asLong(value: UInt): Long = value.peek().litValue.toLong

  /** 带读延迟、可同时容纳多笔在途事务的稀疏页表内存。
    *
    * 每笔请求带独立事务 ID；默认按请求顺序返回，设置 reverseResponses
    * 后同拍完成的事务按相反顺序返回，用于检查 PTW 的 ID 匹配。
    */
  private class BusModel(var latency: Int, var reverseResponses: Boolean = false) {
    private case class Pending(ID: Int, var remain: Int, data: BigInt)
    val data = scala.collection.mutable.Map.empty[Long, BigInt]
    private val pending = scala.collection.mutable.ArrayBuffer.empty[Pending]
    private val latencyByAddress = scala.collection.mutable.Map.empty[Long, Int]
    var reads = 0
    var writes = 0
    var peakPending = 0
    var responseIds = scala.collection.mutable.ArrayBuffer.empty[Int]

    def write(addr: Long, value: BigInt): Unit = data(addr & 0xffffffffL) = value
    def read(addr: Long): BigInt = data.getOrElse(addr & 0xffffffffL, BigInt(0))
    def setLatency(addr: Long, value: Int): Unit =
      latencyByAddress(addr & 0xffffffffL) = value
    def pendingCount: Int = pending.size
    def hasPending: Boolean = pending.nonEmpty
    private def latencyOf(addr: Long): Int =
      latencyByAddress.getOrElse(addr & 0xffffffffL, latency)

    def tick(bus: ysyx_26030103_PTEBusIO, clock: Clock): Unit = {
      val RequestFire = asBool(bus.request.valid) && asBool(bus.request.ready)
      val RequestID = asLong(bus.request.bits.ID).toInt
      val RequestAddress = asLong(bus.request.bits.address)
      val RequestWrite = asBool(bus.request.bits.write)
      val RequestData = asLong(bus.request.bits.data)

      val readyIndex =
        if (reverseResponses) pending.lastIndexWhere(_.remain == 0)
        else pending.indexWhere(_.remain == 0)
      val ResponseValid = readyIndex >= 0
      bus.response.valid.poke(ResponseValid.B)
      bus.response.bits.ID.poke((if (ResponseValid) pending(readyIndex).ID else 0).U(4.W))
      bus.response.bits.data.poke(
        (if (ResponseValid) pending(readyIndex).data else BigInt(0)).U(32.W))
      bus.response.bits.fault.poke(false.B)
      val ResponseReady = asBool(bus.response.ready)
      if (ResponseValid && ResponseReady) responseIds += pending(readyIndex).ID

      clock.step()

      if (ResponseValid && ResponseReady) pending.remove(readyIndex)
      pending.foreach(p => p.remain = math.max(p.remain - 1, 0))

      if (RequestFire) {
        if (RequestWrite) {
          writes += 1
          data(RequestAddress & 0xffffffffL) = RequestData.U(32.W).litValue
          pending += Pending(RequestID, latencyOf(RequestAddress), BigInt(0))
        } else {
          reads += 1
          pending += Pending(RequestID, latencyOf(RequestAddress), read(RequestAddress))
        }
      }
      peakPending = math.max(peakPending, pending.size)
    }
  }

  "PTWPipeline" should "在页表读等待时接收第二笔请求并返回正确物理地址" in {
    val bus = new BusModel(latency = 5)
    bus.write(Root, PTE(0x80002L, 0x1L))
    bus.write(0x80002004L, PTE(LeafPPN, 0xdfL))
    bus.write(0x80002014L, PTE(0x80004L, 0xdfL))

    simulate(new ysyx_26030103_PTWPipeline(
      outstanding = 2,
      adMode = "fault",
      inOrder = true,
      XLEN = 32, addressWidth = 32, vaBits = 32,
      levels = 2, tableEntries = 1024, PTEConfig = Rv32PTE,
      PTESizeBytes = 4
    )) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.request.valid.poke(false.B)
      dut.io.bus.request.ready.poke(true.B)
      dut.io.bus.response.valid.poke(false.B)
      dut.io.bus.response.bits.ID.poke(0.U)
      dut.io.bus.response.bits.data.poke(0.U)
      dut.io.bus.response.bits.fault.poke(false.B)
      dut.io.response(0).ready.poke(true.B)
      dut.io.flush.poke(false.B)

      def send(ID: Int, vaddr: Long): Unit = {
        dut.io.request.valid.poke(true.B)
        dut.io.request.bits.ID.poke(ID.U)
        dut.io.request.bits.vaddr.poke(vaddr.U)
        dut.io.request.bits.satp.poke((0x80000000L | RootPPN).U(32.W))
        dut.io.request.bits.privilege.poke(0.U)
        dut.io.request.bits.access.poke(ysyx_26030103_AccessType.Load)
        dut.io.request.bits.sum.poke(false.B)
        dut.io.request.bits.mxr.poke(false.B)
        var accepted = false
        while (!accepted) {
          accepted = asBool(dut.io.request.ready)
          bus.tick(dut.io.bus, dut.clock)
        }
        dut.io.request.valid.poke(false.B)
      }

      send(0, 0x1234L)
      // 等第一笔真正占用页表总线，再送第二笔；若 PTW 在总线等待时阻塞，
      // 这里的 request.ready 会保持为低。
      var guard = 0
      while (!bus.hasPending && guard < 20) {
        bus.tick(dut.io.bus, dut.clock)
        guard += 1
      }
      bus.hasPending should be(true)
      asBool(dut.io.request.ready) should be(true)
      send(3, 0x5678L)

      val responses = scala.collection.mutable.ArrayBuffer.empty[(Int, Long)]
      guard = 0
      while (responses.size < 2 && guard < 200) {
        bus.tick(dut.io.bus, dut.clock)
        if (asBool(dut.io.response(0).valid)) {
          responses += ((asLong(dut.io.response(0).bits.ID).toInt, asLong(dut.io.response(0).bits.paddr)))
        }
        guard += 1
      }

      responses.map(_._1) should be(Seq(0, 3))
      responses.map(_._2) should be(Seq(0x80003234L, 0x80004678L))
      bus.reads should be(4)
    }
  }

  "PTWPipeline" should "在页表总线上同时保持多笔读并按事务 ID 回收乱序响应" in {
    val bus = new BusModel(latency = 8, reverseResponses = true)
    // 两笔请求使用不同的根页表项，并让先发出的请求具有更长延迟，
    // 强制后发出的请求先完成，验证响应确实按事务 ID 而不是到达顺序归属。
    bus.write(Root, PTE(0x80002L, 0x1L))
    bus.write(0x80002004L, PTE(LeafPPN, 0xdfL))
    bus.write(Root + 0x10L, PTE(0x80005L, 0x1L))
    bus.write(0x800058d0L, PTE(0x80006L, 0xdfL))
    bus.setLatency(Root, 30)
    bus.setLatency(0x80002004L, 30)
    bus.setLatency(Root + 0x10L, 8)
    bus.setLatency(0x800058d0L, 2)

    simulate(new ysyx_26030103_PTWPipeline(
      outstanding = 4,
      busOutstanding = 4,
      adMode = "fault",
      inOrder = false,
      XLEN = 32, addressWidth = 32, vaBits = 32,
      levels = 2, tableEntries = 1024, PTEConfig = Rv32PTE,
      PTESizeBytes = 4
    )) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.request.valid.poke(false.B)
      dut.io.bus.request.ready.poke(true.B)
      dut.io.bus.response.valid.poke(false.B)
      dut.io.bus.response.bits.ID.poke(0.U)
      dut.io.bus.response.bits.data.poke(0.U)
      dut.io.bus.response.bits.fault.poke(false.B)
      dut.io.response(0).ready.poke(true.B)
      dut.io.flush.poke(false.B)

      def send(ID: Int, vaddr: Long): Unit = {
        dut.io.request.valid.poke(true.B)
        dut.io.request.bits.ID.poke(ID.U)
        dut.io.request.bits.vaddr.poke(vaddr.U)
        dut.io.request.bits.satp.poke((0x80000000L | RootPPN).U(32.W))
        dut.io.request.bits.privilege.poke(0.U)
        dut.io.request.bits.access.poke(ysyx_26030103_AccessType.Load)
        dut.io.request.bits.sum.poke(false.B)
        dut.io.request.bits.mxr.poke(false.B)
        var accepted = false
        while (!accepted) {
          accepted = asBool(dut.io.request.ready)
          bus.tick(dut.io.bus, dut.clock)
        }
        dut.io.request.valid.poke(false.B)
      }

      send(0, 0x1234L)
      send(1, 0x01234567L)

      // 在响应返回前，两笔根页表读都应已经在总线上；单槽实现只能到 1。
      var guard = 0
      while (bus.peakPending < 2 && guard < 40) {
        bus.tick(dut.io.bus, dut.clock)
        guard += 1
      }
      bus.peakPending should be >= 2

      val responses = scala.collection.mutable.ArrayBuffer.empty[(Int, Long)]
      guard = 0
      while (responses.size < 2 && guard < 300) {
        bus.tick(dut.io.bus, dut.clock)
        if (asBool(dut.io.response(0).valid)) {
          responses += ((asLong(dut.io.response(0).bits.ID).toInt,
            asLong(dut.io.response(0).bits.paddr)))
        }
        guard += 1
      }

      // 后发的 ctx 1 先完成；上下文仍按各自 ID 返回正确物理地址。
      responses should be(Seq(
        1 -> 0x80006567L,
        0 -> 0x80003234L
      ))
      bus.reads should be(4)
    }
  }

  "PTWPipeline" should "多笔在途时仍按 in-order 顺序返回" in {
    val bus = new BusModel(latency = 8, reverseResponses = true)
    bus.write(Root, PTE(0x80002L, 0x1L))
    bus.write(0x80002004L, PTE(LeafPPN, 0xdfL))
    bus.write(Root + 0x10L, PTE(0x80005L, 0x1L))
    bus.write(0x800058d0L, PTE(0x80006L, 0xdfL))
    // ctx 1 的两级都比 ctx 0 快，但它必须等 ctx 0 返回后才能出队。
    bus.setLatency(Root, 20)
    bus.setLatency(0x80002004L, 20)
    bus.setLatency(Root + 0x10L, 2)
    bus.setLatency(0x800058d0L, 2)

    simulate(new ysyx_26030103_PTWPipeline(
      outstanding = 4,
      busOutstanding = 4,
      adMode = "fault",
      inOrder = true,
      XLEN = 32, addressWidth = 32, vaBits = 32,
      levels = 2, tableEntries = 1024, PTEConfig = Rv32PTE,
      PTESizeBytes = 4
    )) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.request.valid.poke(false.B)
      dut.io.bus.request.ready.poke(true.B)
      dut.io.bus.response.valid.poke(false.B)
      dut.io.bus.response.bits.ID.poke(0.U)
      dut.io.bus.response.bits.data.poke(0.U)
      dut.io.bus.response.bits.fault.poke(false.B)
      dut.io.response(0).ready.poke(true.B)
      dut.io.flush.poke(false.B)

      def send(ID: Int, vaddr: Long): Unit = {
        dut.io.request.valid.poke(true.B)
        dut.io.request.bits.ID.poke(ID.U)
        dut.io.request.bits.vaddr.poke(vaddr.U)
        dut.io.request.bits.satp.poke((0x80000000L | RootPPN).U(32.W))
        dut.io.request.bits.privilege.poke(0.U)
        dut.io.request.bits.access.poke(ysyx_26030103_AccessType.Load)
        dut.io.request.bits.sum.poke(false.B)
        dut.io.request.bits.mxr.poke(false.B)
        var accepted = false
        while (!accepted) {
          accepted = asBool(dut.io.request.ready)
          bus.tick(dut.io.bus, dut.clock)
        }
        dut.io.request.valid.poke(false.B)
      }

      send(0, 0x1234L)
      send(1, 0x01234567L)

      var guard = 0
      while (bus.peakPending < 2 && guard < 40) {
        bus.tick(dut.io.bus, dut.clock)
        guard += 1
      }
      bus.peakPending should be >= 2

      val responses = scala.collection.mutable.ArrayBuffer.empty[(Int, Long)]
      guard = 0
      while (responses.size < 2 && guard < 300) {
        bus.tick(dut.io.bus, dut.clock)
        if (asBool(dut.io.response(0).valid)) {
          responses += ((asLong(dut.io.response(0).bits.ID).toInt,
            asLong(dut.io.response(0).bits.paddr)))
        }
        guard += 1
      }
      responses should be(Seq(
        0 -> 0x80003234L,
        1 -> 0x80006567L
      ))
    }
  }

  "PTWPipeline" should "多笔在途时保持 8 字节 PTE 的低/高字顺序" in {
    val bus = new BusModel(latency = 4, reverseResponses = true)
    bus.write(0x80001000L, PTE(0x80002L, 0x1L))
    bus.write(0x80002000L, PTE(0x80003L, 0x1L))
    bus.write(0x80003000L, PTE(0x80004L, 0x1L))
    bus.write(0x80004008L, PTE(0x80005L, 0xdfL))
    bus.write(0x80004010L, PTE(0x80006L, 0xdfL))

    simulate(new ysyx_26030103_PTWPipeline(
      outstanding = 4,
      busOutstanding = 4,
      levels = 4,
      tableEntries = 32,
      PTESizeBytes = 8,
      inOrder = false,
      XLEN = 32, addressWidth = 32, vaBits = 32
    )) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.request.valid.poke(false.B)
      dut.io.bus.request.ready.poke(true.B)
      dut.io.bus.response.valid.poke(false.B)
      dut.io.bus.response.bits.ID.poke(0.U)
      dut.io.bus.response.bits.data.poke(0.U)
      dut.io.bus.response.bits.fault.poke(false.B)
      dut.io.response(0).ready.poke(true.B)
      dut.io.flush.poke(false.B)

      def send(ID: Int, vaddr: Long): Unit = {
        dut.io.request.valid.poke(true.B)
        dut.io.request.bits.ID.poke(ID.U)
        dut.io.request.bits.vaddr.poke(vaddr.U)
        dut.io.request.bits.satp.poke((0x80000000L | RootPPN).U(32.W))
        dut.io.request.bits.privilege.poke(0.U)
        dut.io.request.bits.access.poke(ysyx_26030103_AccessType.Load)
        dut.io.request.bits.sum.poke(false.B)
        dut.io.request.bits.mxr.poke(false.B)
        var accepted = false
        while (!accepted) {
          accepted = asBool(dut.io.request.ready)
          bus.tick(dut.io.bus, dut.clock)
        }
        dut.io.request.valid.poke(false.B)
      }

      send(0, 0x1234L)
      send(1, 0x2234L)

      var guard = 0
      while (bus.peakPending < 2 && guard < 60) {
        bus.tick(dut.io.bus, dut.clock)
        guard += 1
      }
      bus.peakPending should be >= 2

      val responses = scala.collection.mutable.ArrayBuffer.empty[(Int, Long)]
      guard = 0
      while (responses.size < 2 && guard < 400) {
        bus.tick(dut.io.bus, dut.clock)
        if (asBool(dut.io.response(0).valid)) {
          responses += ((asLong(dut.io.response(0).bits.ID).toInt,
            asLong(dut.io.response(0).bits.paddr)))
        }
        guard += 1
      }
      responses.toMap should be(Map(
        0 -> 0x80005234L,
        1 -> 0x80006234L
      ))
      // 每笔 walk 4 级 × 2 个 32 位字。
      bus.reads should be(16)
    }
  }

  "PTWCluster" should "多个 walker 在页表总线上同时保持读并按事务 ID 回收" in {
    val bus = new BusModel(latency = 6)
    bus.write(Root, PTE(0x80002L, 0x1L))
    bus.write(0x80002004L, PTE(LeafPPN, 0xdfL))
    bus.write(Root + 0x10L, PTE(0x80005L, 0x1L))
    bus.write(0x800058d0L, PTE(0x80006L, 0xdfL))
    bus.setLatency(Root, 30)
    bus.setLatency(0x80002004L, 30)
    bus.setLatency(Root + 0x10L, 8)
    bus.setLatency(0x800058d0L, 2)

    simulate(new ysyx_26030103_PTWCluster(
      outstanding = 4,
      busOutstanding = 4,
      inOrder = false,
      XLEN = 32, addressWidth = 32, vaBits = 32,
      levels = 2, tableEntries = 1024, PTEConfig = Rv32PTE,
      PTESizeBytes = 4
    )) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.request.valid.poke(false.B)
      dut.io.bus.request.ready.poke(true.B)
      dut.io.bus.response.valid.poke(false.B)
      dut.io.bus.response.bits.ID.poke(0.U)
      dut.io.bus.response.bits.data.poke(0.U)
      dut.io.bus.response.bits.fault.poke(false.B)
      dut.io.response(0).ready.poke(true.B)
      dut.io.flush.poke(false.B)

      def send(ID: Int, vaddr: Long): Unit = {
        dut.io.request.valid.poke(true.B)
        dut.io.request.bits.ID.poke(ID.U)
        dut.io.request.bits.vaddr.poke(vaddr.U)
        dut.io.request.bits.satp.poke((0x80000000L | RootPPN).U(32.W))
        dut.io.request.bits.privilege.poke(0.U)
        dut.io.request.bits.access.poke(ysyx_26030103_AccessType.Load)
        dut.io.request.bits.sum.poke(false.B)
        dut.io.request.bits.mxr.poke(false.B)
        var accepted = false
        while (!accepted) {
          accepted = asBool(dut.io.request.ready)
          bus.tick(dut.io.bus, dut.clock)
        }
        dut.io.request.valid.poke(false.B)
      }

      send(0, 0x1234L)
      send(1, 0x01234567L)

      var guard = 0
      while (bus.peakPending < 2 && guard < 40) {
        bus.tick(dut.io.bus, dut.clock)
        guard += 1
      }
      bus.peakPending should be >= 2

      val responses = scala.collection.mutable.ArrayBuffer.empty[(Int, Long)]
      guard = 0
      while (responses.size < 2 && guard < 300) {
        bus.tick(dut.io.bus, dut.clock)
        if (asBool(dut.io.response(0).valid)) {
          responses += ((asLong(dut.io.response(0).bits.ID).toInt,
            asLong(dut.io.response(0).bits.paddr)))
        }
        guard += 1
      }
      responses.toMap should be(Map(
        0 -> 0x80003234L,
        1 -> 0x80006567L
      ))
      bus.reads should be(4)
    }
  }

  "PTWPipeline" should "PTE cache 命中后不再访问页表总线" in {
    val bus = new BusModel(latency = 1)
    bus.write(Root, PTE(0x80002L, 0x1L))
    bus.write(0x80002004L, PTE(LeafPPN, 0xdfL))

    simulate(new ysyx_26030103_PTWPipeline(
      outstanding = 2,
      cacheEnable = true,
      cacheEntries = 16,
      cacheWays = 2,
      XLEN = 32, addressWidth = 32, vaBits = 32,
      levels = 2, tableEntries = 1024, PTEConfig = Rv32PTE,
      PTESizeBytes = 4
    )) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.request.valid.poke(false.B)
      dut.io.bus.request.ready.poke(true.B)
      dut.io.bus.response.valid.poke(false.B)
      dut.io.bus.response.bits.ID.poke(0.U)
      dut.io.bus.response.bits.data.poke(0.U)
      dut.io.bus.response.bits.fault.poke(false.B)
      dut.io.response(0).ready.poke(true.B)
      dut.io.flush.poke(false.B)

      def access(): Unit = {
        dut.io.request.valid.poke(true.B)
        dut.io.request.bits.ID.poke(0.U)
        dut.io.request.bits.vaddr.poke(0x1234.U)
        dut.io.request.bits.satp.poke((0x80000000L | RootPPN).U(32.W))
        dut.io.request.bits.privilege.poke(0.U)
        dut.io.request.bits.access.poke(ysyx_26030103_AccessType.Load)
        dut.io.request.bits.sum.poke(false.B)
        dut.io.request.bits.mxr.poke(false.B)
        var accepted = false
        while (!accepted) {
          accepted = asBool(dut.io.request.ready)
          bus.tick(dut.io.bus, dut.clock)
        }
        dut.io.request.valid.poke(false.B)
        var done = false
        var guard = 0
        while (!done && guard < 100) {
          bus.tick(dut.io.bus, dut.clock)
          done = asBool(dut.io.response(0).valid)
          guard += 1
        }
        done should be(true)
      }

      access()
      access()
      bus.reads should be(2)
    }
  }

  "PTWPipeline" should "在总线等待时用 cache 推进另一笔请求并乱序返回" in {
    val bus = new BusModel(latency = 1)
    bus.write(Root, PTE(0x80002L, 0x1L))
    bus.write(0x80002004L, PTE(LeafPPN, 0xdfL))
    bus.write(0x80002014L, PTE(0x80004L, 0xdfL))

    simulate(new ysyx_26030103_PTWPipeline(
      outstanding = 2,
      cacheEnable = true,
      cacheEntries = 16,
      cacheWays = 2,
      inOrder = false,
      XLEN = 32, addressWidth = 32, vaBits = 32,
      levels = 2, tableEntries = 1024, PTEConfig = Rv32PTE,
      PTESizeBytes = 4
    )) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.request.valid.poke(false.B)
      dut.io.bus.request.ready.poke(true.B)
      dut.io.bus.response.valid.poke(false.B)
      dut.io.bus.response.bits.ID.poke(0.U)
      dut.io.bus.response.bits.data.poke(0.U)
      dut.io.bus.response.bits.fault.poke(false.B)
      dut.io.response(0).ready.poke(true.B)
      dut.io.flush.poke(false.B)

      def send(ID: Int, vaddr: Long): Unit = {
        dut.io.request.valid.poke(true.B)
        dut.io.request.bits.ID.poke(ID.U)
        dut.io.request.bits.vaddr.poke(vaddr.U)
        dut.io.request.bits.satp.poke((0x80000000L | RootPPN).U(32.W))
        dut.io.request.bits.privilege.poke(0.U)
        dut.io.request.bits.access.poke(ysyx_26030103_AccessType.Load)
        dut.io.request.bits.sum.poke(false.B)
        dut.io.request.bits.mxr.poke(false.B)
        var accepted = false
        while (!accepted) {
          accepted = asBool(dut.io.request.ready)
          bus.tick(dut.io.bus, dut.clock)
        }
        dut.io.request.valid.poke(false.B)
      }

      def receive(): (Int, Long) = {
        var result: Option[(Int, Long)] = None
        var guard = 0
        while (result.isEmpty && guard < 200) {
          bus.tick(dut.io.bus, dut.clock)
          if (asBool(dut.io.response(0).valid)) {
            result = Some((asLong(dut.io.response(0).bits.ID).toInt,
              asLong(dut.io.response(0).bits.paddr)))
          }
          guard += 1
        }
        result.get
      }

      // 先用一次请求预热两级的 PTE cache。
      send(7, 0x5678L)
      receive() should be((7, 0x80004678L))
      bus.reads should be(2)

      // 让 A 的叶子读在总线上等待，此时 B 的两级都命中 cache，应能在
      // A 的总线响应返回前完成；out_of_order 允许 B 先返回。
      bus.latency = 8
      send(0, 0x1234L)
      var guard = 0
      while (!bus.hasPending && guard < 20) {
        bus.tick(dut.io.bus, dut.clock)
        guard += 1
      }
      bus.hasPending should be(true)
      send(5, 0x5678L)
      val second = receive()
      second should be((5, 0x80004678L))
      bus.hasPending should be(true)
      receive() should be((0, 0x80003234L))
    }
  }

  "PTWPipeline" should "在 hardware 模式写回 A/D 后返回物理地址" in {
    val bus = new BusModel(latency = 1)
    bus.write(Root, PTE(0x80002L, 0x1L))
    bus.write(0x80002004L, PTE(LeafPPN, 0x1fL))

    simulate(new ysyx_26030103_PTWPipeline(
      outstanding = 2,
      adMode = "hardware",
      XLEN = 32, addressWidth = 32, vaBits = 32,
      levels = 2, tableEntries = 1024, PTEConfig = Rv32PTE,
      PTESizeBytes = 4
    )) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.request.valid.poke(false.B)
      dut.io.bus.request.ready.poke(true.B)
      dut.io.bus.response.valid.poke(false.B)
      dut.io.bus.response.bits.ID.poke(0.U)
      dut.io.bus.response.bits.data.poke(0.U)
      dut.io.bus.response.bits.fault.poke(false.B)
      dut.io.response(0).ready.poke(true.B)
      dut.io.flush.poke(false.B)

      dut.io.request.valid.poke(true.B)
      dut.io.request.bits.ID.poke(0.U)
      dut.io.request.bits.vaddr.poke(0x1234.U)
      dut.io.request.bits.satp.poke((0x80000000L | RootPPN).U(32.W))
      dut.io.request.bits.privilege.poke(0.U)
      dut.io.request.bits.access.poke(ysyx_26030103_AccessType.Store)
      dut.io.request.bits.sum.poke(false.B)
      dut.io.request.bits.mxr.poke(false.B)
      var accepted = false
      while (!accepted) {
        accepted = asBool(dut.io.request.ready)
        bus.tick(dut.io.bus, dut.clock)
      }
      dut.io.request.valid.poke(false.B)

      var response: Option[Long] = None
      var guard = 0
      while (response.isEmpty && guard < 100) {
        bus.tick(dut.io.bus, dut.clock)
        if (asBool(dut.io.response(0).valid)) {
          response = Some(asLong(dut.io.response(0).bits.paddr))
        }
        guard += 1
      }
      response should be(Some(0x80003234L))
      bus.writes should be(1)
      (bus.read(0x80002004L) & BigInt(0xc0)) should be(BigInt(0xc0))
    }
  }

  "PTWPipeline" should "支持 8 字节 PTE 的两拍读取" in {
    val bus = new BusModel(latency = 1)
    bus.write(0x80001000L, PTE(0x80002L, 0x1L))
    bus.write(0x80002000L, PTE(0x80003L, 0x1L))
    bus.write(0x80003000L, PTE(0x80004L, 0x1L))
    bus.write(0x80004008L, PTE(0x80005L, 0xdfL))

    simulate(new ysyx_26030103_PTWPipeline(
      outstanding = 2,
      levels = 4,
      tableEntries = 32,
      PTESizeBytes = 8,
      XLEN = 32, addressWidth = 32, vaBits = 32
    )) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.request.valid.poke(false.B)
      dut.io.bus.request.ready.poke(true.B)
      dut.io.bus.response.valid.poke(false.B)
      dut.io.bus.response.bits.ID.poke(0.U)
      dut.io.bus.response.bits.data.poke(0.U)
      dut.io.bus.response.bits.fault.poke(false.B)
      dut.io.response(0).ready.poke(true.B)
      dut.io.flush.poke(false.B)

      dut.io.request.valid.poke(true.B)
      dut.io.request.bits.ID.poke(0.U)
      dut.io.request.bits.vaddr.poke(0x1234.U)
      dut.io.request.bits.satp.poke((0x80000000L | RootPPN).U(32.W))
      dut.io.request.bits.privilege.poke(0.U)
      dut.io.request.bits.access.poke(ysyx_26030103_AccessType.Load)
      dut.io.request.bits.sum.poke(false.B)
      dut.io.request.bits.mxr.poke(false.B)
      var accepted = false
      while (!accepted) {
        accepted = asBool(dut.io.request.ready)
        bus.tick(dut.io.bus, dut.clock)
      }
      dut.io.request.valid.poke(false.B)

      var response: Option[Long] = None
      var guard = 0
      while (response.isEmpty && guard < 200) {
        bus.tick(dut.io.bus, dut.clock)
        if (asBool(dut.io.response(0).valid)) {
          response = Some(asLong(dut.io.response(0).bits.paddr))
        }
        guard += 1
      }
      response should be(Some(0x80005234L))
    }
  }

  "PTWPipeline" should "支持 4MiB 超级页" in {
    val bus = new BusModel(latency = 1)
    bus.write(Root, PTE(0x80000L, 0xdfL))

    simulate(new ysyx_26030103_PTWPipeline(
      outstanding = 2,
      superpage = true,
      XLEN = 32, addressWidth = 32, vaBits = 32,
      levels = 2, tableEntries = 1024, PTEConfig = Rv32PTE,
      PTESizeBytes = 4
    )) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.request.valid.poke(false.B)
      dut.io.bus.request.ready.poke(true.B)
      dut.io.bus.response.valid.poke(false.B)
      dut.io.bus.response.bits.ID.poke(0.U)
      dut.io.bus.response.bits.data.poke(0.U)
      dut.io.bus.response.bits.fault.poke(false.B)
      dut.io.response(0).ready.poke(true.B)
      dut.io.flush.poke(false.B)

      dut.io.request.valid.poke(true.B)
      dut.io.request.bits.ID.poke(0.U)
      dut.io.request.bits.vaddr.poke(0x1234.U)
      dut.io.request.bits.satp.poke((0x80000000L | RootPPN).U(32.W))
      dut.io.request.bits.privilege.poke(0.U)
      dut.io.request.bits.access.poke(ysyx_26030103_AccessType.Load)
      dut.io.request.bits.sum.poke(false.B)
      dut.io.request.bits.mxr.poke(false.B)
      var accepted = false
      while (!accepted) {
        accepted = asBool(dut.io.request.ready)
        bus.tick(dut.io.bus, dut.clock)
      }
      dut.io.request.valid.poke(false.B)

      var response: Option[Long] = None
      var guard = 0
      while (response.isEmpty && guard < 100) {
        bus.tick(dut.io.bus, dut.clock)
        if (asBool(dut.io.response(0).valid)) {
          response = Some(asLong(dut.io.response(0).bits.paddr))
        }
        guard += 1
      }
      response should be(Some(0x80001234L))
      bus.reads should be(1)
    }
  }

  "MMU" should "在 pipelined 配置下同时处理两笔上下文" in {
    val bus = new BusModel(latency = 4)
    bus.write(Root, PTE(0x80002L, 0x1L))
    bus.write(0x80002004L, PTE(LeafPPN, 0xdfL))
    bus.write(0x80002014L, PTE(0x80004L, 0xdfL))

    simulate(new ysyx_26030103_MMU(rv32Profile.copy(
      enabled = true,
      TLBEnabled = false,
      PTWArch = "pipelined",
      PTWOutstanding = 2,
      PTWOrder = "in_order"
    ))) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.request.valid.poke(false.B)
      dut.io.bus.request.ready.poke(true.B)
      dut.io.bus.response.valid.poke(false.B)
      dut.io.bus.response.bits.ID.poke(0.U)
      dut.io.bus.response.bits.data.poke(0.U)
      dut.io.bus.response.bits.fault.poke(false.B)
      dut.io.response.ready.poke(true.B)
      dut.io.flush.poke(false.B)

      def send(ID: Int, vaddr: Long): Unit = {
        dut.io.request.valid.poke(true.B)
        dut.io.request.bits.ID.poke(ID.U)
        dut.io.request.bits.vaddr.poke(vaddr.U)
        dut.io.request.bits.access.poke(ysyx_26030103_AccessType.Load)
        dut.io.request.bits.privilege.poke(0.U)
        dut.io.request.bits.sum.poke(false.B)
        dut.io.request.bits.mxr.poke(false.B)
        dut.io.request.bits.satp.poke((0x80000000L | RootPPN).U(32.W))
        var accepted = false
        while (!accepted) {
          accepted = asBool(dut.io.request.ready)
          bus.tick(dut.io.bus, dut.clock)
        }
        dut.io.request.valid.poke(false.B)
      }

      send(0, 0x1234L)
      send(1, 0x5678L)

      val responses = scala.collection.mutable.ArrayBuffer.empty[(Int, Long)]
      var guard = 0
      while (responses.size < 2 && guard < 300) {
        bus.tick(dut.io.bus, dut.clock)
        if (asBool(dut.io.response.valid)) {
          responses += ((asLong(dut.io.response.bits.ID).toInt, asLong(dut.io.response.bits.paddr)))
        }
        guard += 1
      }
      responses.map(_._1) should be(Seq(0, 1))
      responses.map(_._2) should be(Seq(0x80003234L, 0x80004678L))
      bus.reads should be(4)
    }
  }

  "PTWPipeline" should "8B PTE cache 复用低/高字并避免重复页表读" in {
    val bus = new BusModel(latency = 1)
    bus.write(0x80001000L, PTE(0x80002L, 0x1L))
    bus.write(0x80002000L, PTE(0x80003L, 0x1L))
    bus.write(0x80003000L, PTE(0x80004L, 0x1L))
    bus.write(0x80004008L, PTE(0x80005L, 0xdfL))

    simulate(new ysyx_26030103_PTWPipeline(
      outstanding = 2,
      levels = 4,
      tableEntries = 32,
      PTESizeBytes = 8,
      cacheEnable = true,
      cacheEntries = 8,
      cacheWays = 8,
      XLEN = 32, addressWidth = 32, vaBits = 32
    )) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.request.valid.poke(false.B)
      dut.io.bus.request.ready.poke(true.B)
      dut.io.bus.response.valid.poke(false.B)
      dut.io.bus.response.bits.ID.poke(0.U)
      dut.io.bus.response.bits.data.poke(0.U)
      dut.io.bus.response.bits.fault.poke(false.B)
      dut.io.response(0).ready.poke(true.B)
      dut.io.flush.poke(false.B)

      def access(): Long = {
        dut.io.request.valid.poke(true.B)
        dut.io.request.bits.ID.poke(0.U)
        dut.io.request.bits.vaddr.poke(0x1234.U)
        dut.io.request.bits.satp.poke((0x80000000L | RootPPN).U(32.W))
        dut.io.request.bits.privilege.poke(0.U)
        dut.io.request.bits.access.poke(ysyx_26030103_AccessType.Load)
        dut.io.request.bits.sum.poke(false.B)
        dut.io.request.bits.mxr.poke(false.B)
        var accepted = false
        while (!accepted) {
          accepted = asBool(dut.io.request.ready)
          bus.tick(dut.io.bus, dut.clock)
        }
        dut.io.request.valid.poke(false.B)
        var result: Option[Long] = None
        var guard = 0
        while (result.isEmpty && guard < 200) {
          bus.tick(dut.io.bus, dut.clock)
          if (asBool(dut.io.response(0).valid)) {
            result = Some(asLong(dut.io.response(0).bits.paddr))
          }
          guard += 1
        }
        result.get
      }

      access() should be(0x80005234L)
      // 4 级 × 低/高两个字。
      bus.reads should be(8)
      access() should be(0x80005234L)
      bus.reads should be(8)
    }
  }
}
