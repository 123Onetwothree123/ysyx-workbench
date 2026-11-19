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

  private case class ReadRequest(
      ID: BigInt,
      addr: Long,
      length: Int,
      size: Int,
      beat: Int = 0
  )
  private case class ReadBeat(ID: BigInt, data: BigInt, response: Int, last: Boolean)

  /** 每个下游目标一套单在途读 + 单写响应模型。 */
  private class Downstreams(dut: ysyx_26030103_AXI5Xbar) {
    private val SOCReads = mutable.Queue.empty[ReadRequest]
    private val CLINTReads = mutable.Queue.empty[ReadRequest]
    private val PLICReads = mutable.Queue.empty[ReadRequest]
    private var SOCCurrent: Option[ReadRequest] = None
    private var CLINTCurrent: Option[ReadRequest] = None
    private var PLICCurrent: Option[ReadRequest] = None
    private val upstreamBeats = mutable.ArrayBuffer.empty[ReadBeat]

    private var AW: Option[(BigInt, Long, Int)] = None
    var ARHold = false
    private var wDone = false
    private var bValid = false
    private var bID = BigInt(0)
    private var bResponse = 0
    var SOCARCount = 0
    var CLINTARCount = 0
    var PLICARCount = 0
    var AWSeen = false
    var bObserved = false
    var BObservedID = BigInt(0)

    private def dataOf(addr: Long, beat: Int): BigInt =
      BigInt(((addr >> 2) + beat.toLong * 0x01010101L) & 0xffffffffL)

    private def currentOf(
        kind: Int
    ): Option[ReadRequest] = kind match {
      case 0 => SOCCurrent
      case 1 => CLINTCurrent
      case _ => PLICCurrent
    }
    private def setCurrent(kind: Int, request: Option[ReadRequest]): Unit =
      kind match {
        case 0 => SOCCurrent = request
        case 1 => CLINTCurrent = request
        case _ => PLICCurrent = request
      }
    private def queueOf(kind: Int): mutable.Queue[ReadRequest] = kind match {
      case 0 => SOCReads
      case 1 => CLINTReads
      case _ => PLICReads
    }

    private def driveRead(
        kind: Int,
        port: ysyx_26030103_AXI5IO
    ): Boolean = {
      val current = currentOf(kind)
      port.AR.ARREADY.poke((current.isEmpty && !ARHold).B)
      port.R.RVALID.poke(current.isDefined.B)
      current.foreach { request =>
        val addr = request.addr + request.beat.toLong * (1L << request.size)
        port.R.RID.poke(request.ID.U)
        port.R.RDATA.poke(dataOf(addr, 0).U(32.W))
        port.R.RRESP.poke(0.U)
        port.R.RLAST.poke((request.beat == request.length).B)
      }
      current.isDefined && bool(port.R.RREADY)
    }

    private def sampleRead(
        kind: Int,
        port: ysyx_26030103_AXI5IO
    ): Unit = {
      if (bool(port.AR.ARVALID) && bool(port.AR.ARREADY)) {
        val request = ReadRequest(
          big(port.AR.ARID),
          big(port.AR.ARADDR).toLong,
          big(port.AR.ARLEN).toInt,
          big(port.AR.ARSIZE).toInt
        )
        queueOf(kind).enqueue(request)
        kind match {
          case 0 => SOCARCount += 1
          case 1 => CLINTARCount += 1
          case _ => PLICARCount += 1
        }
      }
    }

    private def advanceRead(kind: Int): Unit = {
      currentOf(kind).foreach { request =>
        if (request.beat == request.length) setCurrent(kind, None)
        else setCurrent(kind, Some(request.copy(beat = request.beat + 1)))
      }
    }

    def tick(): Unit = {
      val SOC = dut.io.SoCBus
      val CLINT = dut.io.CLINT
      val PLIC = dut.io.PLIC
      val in = dut.io.in

      if (SOCCurrent.isEmpty && SOCReads.nonEmpty)
        SOCCurrent = Some(SOCReads.dequeue())
      if (CLINTCurrent.isEmpty && CLINTReads.nonEmpty)
        CLINTCurrent = Some(CLINTReads.dequeue())
      if (PLICCurrent.isEmpty && PLICReads.nonEmpty)
        PLICCurrent = Some(PLICReads.dequeue())

      val SOCRFire = driveRead(0, SOC)
      val CLINTRFire = driveRead(1, CLINT)
      val PLICRFire = driveRead(2, PLIC)

      SOC.AW.AWREADY.poke((AW.isEmpty && !bValid).B)
      SOC.W.WREADY.poke((!bValid).B)
      SOC.B.BVALID.poke(bValid.B)
      SOC.B.BID.poke(bID.U)
      SOC.B.BRESP.poke(bResponse.U)
      CLINT.AW.AWREADY.poke(false.B)
      CLINT.W.WREADY.poke(false.B)
      CLINT.B.BVALID.poke(false.B)
      CLINT.B.BID.poke(0.U)
      CLINT.B.BRESP.poke(0.U)
      PLIC.AW.AWREADY.poke(false.B)
      PLIC.W.WREADY.poke(false.B)
      PLIC.B.BVALID.poke(false.B)
      PLIC.B.BID.poke(0.U)
      PLIC.B.BRESP.poke(0.U)

      sampleRead(0, SOC)
      sampleRead(1, CLINT)
      sampleRead(2, PLIC)

      val AWFire = bool(SOC.AW.AWVALID) && bool(SOC.AW.AWREADY)
      val wFire = bool(SOC.W.WVALID) && bool(SOC.W.WREADY)
      val wLast = wFire && bool(SOC.W.WLAST)
      val bFire = bValid && bool(SOC.B.BREADY)
      val awID = if (AWFire) big(SOC.AW.AWID) else BigInt(0)
      val AWAddress = if (AWFire) big(SOC.AW.AWADDR).toLong else 0L
      val awLen = if (AWFire) big(SOC.AW.AWLEN).toInt else 0

      if (bool(in.B.BVALID)) {
        bObserved = true
        BObservedID = big(in.B.BID)
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

      if (SOCRFire) advanceRead(0)
      if (CLINTRFire) advanceRead(1)
      if (PLICRFire) advanceRead(2)
      if (bFire) {
        bValid = false
        AW = None
        wDone = false
      }
      if (AWFire) { AWSeen = true; AW = Some((awID, AWAddress, awLen)) }
      if (wLast) wDone = true
      if (AW.nonEmpty && wDone && !bValid) {
        bValid = true
        bID = AW.get._1
        bResponse = 0
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
    val SOC = dut.io.SoCBus
    SOC.AR.ARREADY.poke(false.B)
    SOC.R.RVALID.poke(false.B)
    SOC.R.RID.poke(0.U)
    SOC.R.RDATA.poke(0.U)
    SOC.R.RRESP.poke(0.U)
    SOC.R.RLAST.poke(false.B)
    val CLINT = dut.io.CLINT
    CLINT.AR.ARREADY.poke(false.B)
    CLINT.R.RVALID.poke(false.B)
    CLINT.R.RID.poke(0.U)
    CLINT.R.RDATA.poke(0.U)
    CLINT.R.RRESP.poke(0.U)
    CLINT.R.RLAST.poke(false.B)
    val PLIC = dut.io.PLIC
    PLIC.AR.ARREADY.poke(false.B)
    PLIC.R.RVALID.poke(false.B)
    PLIC.R.RID.poke(0.U)
    PLIC.R.RDATA.poke(0.U)
    PLIC.R.RRESP.poke(0.U)
    PLIC.R.RLAST.poke(false.B)
  }

  private def offerRead(
      dut: ysyx_26030103_AXI5Xbar,
      ID: Int,
      addr: Long,
      length: Int,
      size: Int,
      burst: Int = 1
  ): Unit = {
    val in = dut.io.in
    in.AR.ARADDR.poke(addr.U)
    in.AR.ARID.poke(ID.U)
    in.AR.ARLEN.poke(length.U)
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
      model.ARHold = true
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
      model.ARHold = false

      val beats = waitBeats(dut, model, 2)
      beats.map(_.ID).toSet should be(Set(BigInt(0), BigInt(1)))
      all(beats.map(_.response)) should be(0)
      all(beats.map(_.last)) should be(true)
      val ByID = beats.map(b => b.ID -> b.data).toMap
      ByID(BigInt(0)) should be(BigInt(((0x80000000L >> 2) & 0xffffffffL)))
      ByID(BigInt(1)) should be(BigInt(((0x02004000L >> 2) & 0xffffffffL)))
    }
  }

  it should "两个目标的 burst 读交错返回时 RLAST/RID 不串线" in {
    simulate(new ysyx_26030103_AXI5Xbar()) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      val model = new Downstreams(dut)
      model.ARHold = true

      offerRead(dut, 2, 0x80000040L, 2, 2)
      waitArFire(dut, model)
      offerRead(dut, 3, 0x0200bff8L, 1, 2)
      waitArFire(dut, model)
      // 两个 AR 都在下游等待，放行后响应可任意交错。
      model.ARHold = false

      val beats = waitBeats(dut, model, 5)
      val SOCBeats = beats.filter(_.ID == 2)
      val CLINTBeats = beats.filter(_.ID == 3)
      SOCBeats.size should be(3)
      CLINTBeats.size should be(2)
      SOCBeats.map(_.last) should be(Seq(false, false, true))
      CLINTBeats.map(_.last) should be(Seq(false, true))
      SOCBeats.map(_.data) should be(
        Seq(0x80000040L, 0x80000044L, 0x80000048L)
          .map(a => BigInt(((a >> 2) & 0xffffffffL)))
      )
      CLINTBeats.map(_.data) should be(
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
      var ARSeen = false
      var readBeatSeen = false
      while (cycles < 150 && !(bSeen && ARSeen && readBeatSeen)) {
        model.tick(); cycles += 1
        if (model.bObserved) {
          bSeen = true
          model.BObservedID should be(BigInt(4))
        }
        if (model.CLINTARCount > 0) ARSeen = true
        if (model.beats.nonEmpty) readBeatSeen = true
      }
      val SockAW = model.AWSeen
      in.AR.ARVALID.poke(false.B)
      in.AW.AWVALID.poke(false.B)
      in.W.WVALID.poke(false.B)
      in.B.BREADY.poke(false.B)

      withClue(s"并发读写未完成: b=$bSeen AW=$SockAW ar=$ARSeen r=$readBeatSeen: ") {
        (bSeen && SockAW && ARSeen && readBeatSeen) should be(true)
      }
      val beat = model.beats.head
      beat.ID should be(BigInt(5))
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
      beats.head.ID should be(BigInt(6))
      beats.head.response should be(3)
      beats.head.last should be(true)
      model.SOCARCount should be(0)
      model.CLINTARCount should be(0)
      model.PLICARCount should be(0)
    }
  }
}
