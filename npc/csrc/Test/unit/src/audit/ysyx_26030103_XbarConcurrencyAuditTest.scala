package ysyx_26030103.audit

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.ysyx_26030103.infra.{ysyx_26030103_AXI5IO, ysyx_26030103_AXI5Xbar}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

/** Xbar 并发读/写与乱序响应路由审计。
  *
  * 覆盖既有多拍/优先级测试未涉及的组合：
  *   - SoCBus 与 CLINT 同时有独立 AR，读槽位按 ID 保存；
  *   - 两个目标的 R 响应乱序返回，RID/数据/RLAST 不能串线；
  *   - 写 SoCBus 与读 CLINT 同时进行；
  *   - 本地非法地址的 DECERR 不泄漏到任何下游。
  */
class ysyx_26030103_XbarConcurrencyAuditTest
    extends AnyFlatSpec with Matchers {
  private def bool(value: Bool): Boolean = value.peek().litValue == 1
  private def big(value: UInt): BigInt = value.peek().litValue

  private case class ReadReq(
      id: BigInt,
      addr: Long,
      len: Int,
      size: Int,
      beat: Int = 0
  )
  private case class ReadBeat(id: BigInt, data: BigInt, resp: Int, last: Boolean)

  /** 每个下游目标一套单在途读 + 单写响应模型。 */
  private class Downstreams(dut: ysyx_26030103_AXI5Xbar) {
    private val socReads = mutable.Queue.empty[ReadReq]
    private val clintReads = mutable.Queue.empty[ReadReq]
    private val plicReads = mutable.Queue.empty[ReadReq]
    private var socCurrent: Option[ReadReq] = None
    private var clintCurrent: Option[ReadReq] = None
    private var plicCurrent: Option[ReadReq] = None
    private val upstreamBeats = mutable.ArrayBuffer.empty[ReadBeat]

    private var aw: Option[(BigInt, Long, Int)] = None
    var arHold = false
    private var wDone = false
    private var bValid = false
    private var bId = BigInt(0)
    private var bResp = 0
    var socArCount = 0
    var clintArCount = 0
    var plicArCount = 0
    var awSeen = false
    var bObserved = false
    var bObservedId = BigInt(0)

    private def dataOf(addr: Long, beat: Int): BigInt =
      BigInt(((addr >> 2) + beat.toLong * 0x01010101L) & 0xffffffffL)

    private def currentOf(
        kind: Int
    ): Option[ReadReq] = kind match {
      case 0 => socCurrent
      case 1 => clintCurrent
      case _ => plicCurrent
    }
    private def setCurrent(kind: Int, req: Option[ReadReq]): Unit =
      kind match {
        case 0 => socCurrent = req
        case 1 => clintCurrent = req
        case _ => plicCurrent = req
      }
    private def queueOf(kind: Int): mutable.Queue[ReadReq] = kind match {
      case 0 => socReads
      case 1 => clintReads
      case _ => plicReads
    }

    private def driveRead(
        kind: Int,
        port: ysyx_26030103_AXI5IO
    ): Boolean = {
      val cur = currentOf(kind)
      port.AR.ARREADY.poke((cur.isEmpty && !arHold).B)
      port.R.RVALID.poke(cur.isDefined.B)
      cur.foreach { req =>
        val addr = req.addr + req.beat.toLong * (1L << req.size)
        port.R.RID.poke(req.id.U)
        port.R.RDATA.poke(dataOf(addr, 0).U(32.W))
        port.R.RRESP.poke(0.U)
        port.R.RLAST.poke((req.beat == req.len).B)
      }
      cur.isDefined && bool(port.R.RREADY)
    }

    private def sampleRead(
        kind: Int,
        port: ysyx_26030103_AXI5IO
    ): Unit = {
      if (bool(port.AR.ARVALID) && bool(port.AR.ARREADY)) {
        val req = ReadReq(
          big(port.AR.ARID),
          big(port.AR.ARADDR).toLong,
          big(port.AR.ARLEN).toInt,
          big(port.AR.ARSIZE).toInt
        )
        queueOf(kind).enqueue(req)
        kind match {
          case 0 => socArCount += 1
          case 1 => clintArCount += 1
          case _ => plicArCount += 1
        }
      }
    }

    private def advanceRead(kind: Int): Unit = {
      currentOf(kind).foreach { req =>
        if (req.beat == req.len) setCurrent(kind, None)
        else setCurrent(kind, Some(req.copy(beat = req.beat + 1)))
      }
    }

    def tick(): Unit = {
      val soc = dut.io.SoCBus
      val clint = dut.io.CLINT
      val plic = dut.io.PLIC
      val in = dut.io.in

      if (socCurrent.isEmpty && socReads.nonEmpty)
        socCurrent = Some(socReads.dequeue())
      if (clintCurrent.isEmpty && clintReads.nonEmpty)
        clintCurrent = Some(clintReads.dequeue())
      if (plicCurrent.isEmpty && plicReads.nonEmpty)
        plicCurrent = Some(plicReads.dequeue())

      val socRFire = driveRead(0, soc)
      val clintRFire = driveRead(1, clint)
      val plicRFire = driveRead(2, plic)

      soc.AW.AWREADY.poke((aw.isEmpty && !bValid).B)
      soc.W.WREADY.poke((!bValid).B)
      soc.B.BVALID.poke(bValid.B)
      soc.B.BID.poke(bId.U)
      soc.B.BRESP.poke(bResp.U)
      clint.AW.AWREADY.poke(false.B)
      clint.W.WREADY.poke(false.B)
      clint.B.BVALID.poke(false.B)
      clint.B.BID.poke(0.U)
      clint.B.BRESP.poke(0.U)
      plic.AW.AWREADY.poke(false.B)
      plic.W.WREADY.poke(false.B)
      plic.B.BVALID.poke(false.B)
      plic.B.BID.poke(0.U)
      plic.B.BRESP.poke(0.U)

      sampleRead(0, soc)
      sampleRead(1, clint)
      sampleRead(2, plic)

      val awFire = bool(soc.AW.AWVALID) && bool(soc.AW.AWREADY)
      val wFire = bool(soc.W.WVALID) && bool(soc.W.WREADY)
      val wLast = wFire && bool(soc.W.WLAST)
      val bFire = bValid && bool(soc.B.BREADY)
      val awId = if (awFire) big(soc.AW.AWID) else BigInt(0)
      val awAddr = if (awFire) big(soc.AW.AWADDR).toLong else 0L
      val awLen = if (awFire) big(soc.AW.AWLEN).toInt else 0

      if (bool(in.B.BVALID)) {
        bObserved = true
        bObservedId = big(in.B.BID)
      }
      if (bool(in.R.RVALID) && bool(in.R.RREADY)) {
        upstreamBeats += ReadBeat(
          big(in.R.RID),
          big(in.R.RDATA),
          in.R.RRESP.peek().litValue.toInt,
          bool(in.R.RLAST)
        )
      }

      dut.clock.step()

      if (socRFire) advanceRead(0)
      if (clintRFire) advanceRead(1)
      if (plicRFire) advanceRead(2)
      if (bFire) {
        bValid = false
        aw = None
        wDone = false
      }
      if (awFire) { awSeen = true; aw = Some((awId, awAddr, awLen)) }
      if (wLast) wDone = true
      if (aw.nonEmpty && wDone && !bValid) {
        bValid = true
        bId = aw.get._1
        bResp = 0
      }
    }

    def beats: Seq[ReadBeat] = upstreamBeats.toSeq
  }

  private def resetDut(
      dut: ysyx_26030103_AXI5Xbar,
      clock: Clock,
      reset: Reset
  ): Unit = {
    reset.poke(true.B)
    clock.step(2)
    reset.poke(false.B)
    val in = dut.io.in
    in.AW.AWVALID.poke(false.B)
    in.AW.AWADDR.poke(0.U)
    in.AW.AWID.poke(0.U)
    in.AW.AWLEN.poke(0.U)
    in.AW.AWSIZE.poke(2.U)
    in.AW.AWBURST.poke(0.U)
    in.AW.AWPROT.poke(0.U)
    in.AW.AWCACHE.poke(0.U)
    in.AW.AWQOS.poke(0.U)
    in.W.WVALID.poke(false.B)
    in.W.WDATA.poke(0.U)
    in.W.WSTRB.poke(0.U)
    in.W.WLAST.poke(false.B)
    in.B.BREADY.poke(false.B)
    in.AR.ARVALID.poke(false.B)
    in.AR.ARADDR.poke(0.U)
    in.AR.ARID.poke(0.U)
    in.AR.ARLEN.poke(0.U)
    in.AR.ARSIZE.poke(2.U)
    in.AR.ARBURST.poke(1.U)
    in.AR.ARPROT.poke(0.U)
    in.AR.ARCACHE.poke(0.U)
    in.AR.ARQOS.poke(0.U)
    in.R.RREADY.poke(true.B)
    val soc = dut.io.SoCBus
    soc.AR.ARREADY.poke(false.B)
    soc.R.RVALID.poke(false.B)
    soc.R.RID.poke(0.U)
    soc.R.RDATA.poke(0.U)
    soc.R.RRESP.poke(0.U)
    soc.R.RLAST.poke(false.B)
    val clint = dut.io.CLINT
    clint.AR.ARREADY.poke(false.B)
    clint.R.RVALID.poke(false.B)
    clint.R.RID.poke(0.U)
    clint.R.RDATA.poke(0.U)
    clint.R.RRESP.poke(0.U)
    clint.R.RLAST.poke(false.B)
    val plic = dut.io.PLIC
    plic.AR.ARREADY.poke(false.B)
    plic.R.RVALID.poke(false.B)
    plic.R.RID.poke(0.U)
    plic.R.RDATA.poke(0.U)
    plic.R.RRESP.poke(0.U)
    plic.R.RLAST.poke(false.B)
  }

  private def offerRead(
      dut: ysyx_26030103_AXI5Xbar,
      id: Int,
      addr: Long,
      len: Int,
      size: Int,
      burst: Int = 1
  ): Unit = {
    val in = dut.io.in
    in.AR.ARADDR.poke(addr.U)
    in.AR.ARID.poke(id.U)
    in.AR.ARLEN.poke(len.U)
    in.AR.ARSIZE.poke(size.U)
    in.AR.ARBURST.poke(burst.U)
    in.AR.ARVALID.poke(true.B)
  }

  private def waitArFire(
      dut: ysyx_26030103_AXI5Xbar,
      model: Downstreams,
      limit: Int = 30
  ): Unit = {
    var cycles = 0
    while (!bool(dut.io.in.AR.ARREADY) && cycles < limit) {
      model.tick(); cycles += 1
    }
    withClue(s"Xbar 未在 $limit 拍内接受 AR: ") {
      bool(dut.io.in.AR.ARREADY) should be(true)
    }
    model.tick()
    dut.io.in.AR.ARVALID.poke(false.B)
  }

  private def waitBeats(
      dut: ysyx_26030103_AXI5Xbar,
      model: Downstreams,
      count: Int,
      limit: Int = 200
  ): Seq[ReadBeat] = {
    val start = model.beats.size
    var cycles = 0
    while (model.beats.size < start + count && cycles < limit) {
      model.tick(); cycles += 1
    }
    withClue(s"$count 个 R beat 未在 $limit 拍内返回: ") {
      (model.beats.size - start) should be >= count
    }
    model.beats.slice(start, start + count).toSeq
  }

  "AXI5Xbar" should
    "SoCBus/CLINT 并发读、乱序 R 响应按 RID 正确路由" in {
    simulate(new ysyx_26030103_AXI5Xbar()) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      val model = new Downstreams(dut)

      // 先扣住下游 ARREADY，让两个请求都登记在读槽里，再释放以观察
      // Xbar 能否同时向 SoCBus/CLINT 发出两个独立 AR。
      model.arHold = true
      offerRead(dut, 0, 0x80000000L, 0, 2)
      waitArFire(dut, model)
      offerRead(dut, 1, 0x02004000L, 0, 2)
      waitArFire(dut, model)

      var both = false
      for (_ <- 0 until 20 if !both) {
        if (bool(dut.io.SoCBus.AR.ARVALID) &&
            bool(dut.io.CLINT.AR.ARVALID)) both = true
        if (!both) model.tick()
      }
      withClue("SoCBus 与 CLINT 的 AR 不能同时在途: ") { both should be(true) }
      model.arHold = false

      val beats = waitBeats(dut, model, 2)
      beats.map(_.id).toSet should be(Set(BigInt(0), BigInt(1)))
      all(beats.map(_.resp)) should be(0)
      all(beats.map(_.last)) should be(true)
      val byId = beats.map(b => b.id -> b.data).toMap
      byId(BigInt(0)) should be(BigInt(((0x80000000L >> 2) & 0xffffffffL)))
      byId(BigInt(1)) should be(BigInt(((0x02004000L >> 2) & 0xffffffffL)))
    }
  }

  it should "两个目标的 burst 读交错返回时 RLAST/RID 不串线" in {
    simulate(new ysyx_26030103_AXI5Xbar()) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      val model = new Downstreams(dut)
      model.arHold = true

      offerRead(dut, 2, 0x80000040L, 2, 2)
      waitArFire(dut, model)
      offerRead(dut, 3, 0x0200bff8L, 1, 2)
      waitArFire(dut, model)
      // 两个 AR 都在下游等待，放行后响应可任意交错。
      model.arHold = false

      val beats = waitBeats(dut, model, 5)
      val socBeats = beats.filter(_.id == 2)
      val clintBeats = beats.filter(_.id == 3)
      socBeats.size should be(3)
      clintBeats.size should be(2)
      socBeats.map(_.last) should be(Seq(false, false, true))
      clintBeats.map(_.last) should be(Seq(false, true))
      socBeats.map(_.data) should be(
        Seq(0x80000040L, 0x80000044L, 0x80000048L)
          .map(a => BigInt(((a >> 2) & 0xffffffffL)))
      )
      clintBeats.map(_.data) should be(
        Seq(0x0200bff8L, 0x0200bffcL)
          .map(a => BigInt(((a >> 2) & 0xffffffffL)))
      )
    }
  }

  it should "写 SoCBus 与读 CLINT 并发完成，BID/RID 保持各自请求值" in {
    simulate(new ysyx_26030103_AXI5Xbar()) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      val model = new Downstreams(dut)
      val in = dut.io.in

      // 写 SoCBus ID=4。
      in.AW.AWVALID.poke(true.B)
      in.AW.AWADDR.poke(0x80000100L.U)
      in.AW.AWID.poke(4.U)
      in.AW.AWLEN.poke(0.U)
      in.AW.AWSIZE.poke(2.U)
      in.AW.AWBURST.poke(1.U)
      in.W.WVALID.poke(true.B)
      in.W.WDATA.poke("hdeadbeef".U(32.W))
      in.W.WSTRB.poke(15.U)
      in.W.WLAST.poke(true.B)
      in.B.BREADY.poke(true.B)
      // 同时发 CLINT 读 ID=5。
      offerRead(dut, 5, 0x02004000L, 0, 2)

      var cycles = 0
      var bSeen = false
      var arSeen = false
      var readBeatSeen = false
      while (cycles < 150 && !(bSeen && arSeen && readBeatSeen)) {
        model.tick(); cycles += 1
        if (model.bObserved) {
          bSeen = true
          model.bObservedId should be(BigInt(4))
        }
        if (model.clintArCount > 0) arSeen = true
        if (model.beats.nonEmpty) readBeatSeen = true
      }
      val sockAw = model.awSeen
      in.AR.ARVALID.poke(false.B)
      in.AW.AWVALID.poke(false.B)
      in.W.WVALID.poke(false.B)
      in.B.BREADY.poke(false.B)

      withClue(s"并发读写未完成: b=$bSeen aw=$sockAw ar=$arSeen r=$readBeatSeen: ") {
        (bSeen && sockAw && arSeen && readBeatSeen) should be(true)
      }
      val beat = model.beats.head
      beat.id should be(BigInt(5))
      beat.last should be(true)
    }
  }

  it should "未映射读地址本地 DECERR，不向任何下游发 AR" in {
    simulate(new ysyx_26030103_AXI5Xbar()) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      val model = new Downstreams(dut)

      offerRead(dut, 6, 0x50000000L, 0, 2)
      waitArFire(dut, model)
      val beats = waitBeats(dut, model, 1)
      beats.head.id should be(BigInt(6))
      beats.head.resp should be(3)
      beats.head.last should be(true)
      model.socArCount should be(0)
      model.clintArCount should be(0)
      model.plicArCount should be(0)
    }
  }
}
