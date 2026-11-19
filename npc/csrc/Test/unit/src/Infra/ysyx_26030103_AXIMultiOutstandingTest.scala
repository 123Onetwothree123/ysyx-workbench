package ysyx_26030103.infra

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** AXI 多 outstanding 定向测试。
  *
  * 覆盖三件事：
  *   1. PTWToAXI 同时发出多笔页表读，并按 AXI RID 把数据送回 PTW 原事务 ID；
  *   2. AXIReadMux 允许 ICache burst 与多笔 PTW 读重叠，响应互不串扰；
  *   3. AXI5Arbiter 允许同一 IFU 端口上多笔不同 ID 的读同时存在，并与
  *      LSU/DMA 的固定下游 ID 正确共存。
  */
class ysyx_26030103_AXIMultiOutstandingTest
    extends AnyFlatSpec with Matchers {
  private def asBool(value: Bool): Boolean = value.peek().litValue == 1
  private def asLong(value: UInt): Long = value.peek().litValue.toLong

  /** 把 Flipped 的 AXI 从端口输入绑成空闲。 */
  private def idleSlaveInputs(p: ysyx_26030103_AXI5IO): Unit = {
    p.AW.AWVALID.poke(false.B)
    p.AW.AWID.poke(0.U)
    p.AW.AWADDR.poke(0.U)
    p.AW.AWLEN.poke(0.U)
    p.AW.AWSIZE.poke(2.U)
    p.AW.AWBURST.poke(0.U)
    p.AW.AWPROT.poke(0.U)
    p.AW.AWCACHE.poke(0.U)
    p.AW.AWQOS.poke(0.U)
    p.W.WVALID.poke(false.B)
    p.W.WDATA.poke(0.U)
    p.W.WSTRB.poke(0.U)
    p.W.WLAST.poke(false.B)
    p.B.BREADY.poke(false.B)
    p.AR.ARVALID.poke(false.B)
    p.AR.ARID.poke(0.U)
    p.AR.ARADDR.poke(0.U)
    p.AR.ARLEN.poke(0.U)
    p.AR.ARSIZE.poke(2.U)
    p.AR.ARBURST.poke(1.U)
    p.AR.ARPROT.poke(0.U)
    p.AR.ARCACHE.poke(0.U)
    p.AR.ARQOS.poke(0.U)
    p.R.RREADY.poke(true.B)
  }

  /** 把 AXI master 端口的响应输入绑成空闲；ARREADY 默认接收。 */
  private def idleMasterInputs(p: ysyx_26030103_AXI5IO): Unit = {
    p.AW.AWREADY.poke(false.B)
    p.W.WREADY.poke(false.B)
    p.B.BVALID.poke(false.B)
    p.B.BID.poke(0.U)
    p.B.BRESP.poke(0.U)
    p.AR.ARREADY.poke(true.B)
    p.R.RVALID.poke(false.B)
    p.R.RID.poke(0.U)
    p.R.RDATA.poke(0.U)
    p.R.RRESP.poke(0.U)
    p.R.RLAST.poke(false.B)
  }

  "PTWToAXI" should "同时发出多笔页表读并按原事务 ID 回收响应" in {
    simulate(new ysyx_26030103_PTWToAXI(busDepth = 8, AXIReadSlots = 4)) {
      dut =>
        dut.reset.poke(true.B)
        dut.clock.step(2)
        dut.reset.poke(false.B)
        // request/response 不是 AXI5IO，单独初始化。
        dut.io.request.valid.poke(false.B)
        dut.io.request.bits.ID.poke(0.U)
        dut.io.request.bits.address.poke(0.U)
        dut.io.request.bits.write.poke(false.B)
        dut.io.request.bits.data.poke(0.U)
        dut.io.request.bits.strb.poke(0.U)
        dut.io.response.ready.poke(true.B)
        idleMasterInputs(dut.io.AXI)
        idleMasterInputs(dut.io.AXIWrite)

        val issued = scala.collection.mutable.ArrayBuffer.empty[(Int, Long)]
        val responses = scala.collection.mutable.ArrayBuffer.empty[(Int, Long)]

        def step(): Unit = {
          if (asBool(dut.io.AXI.AR.ARVALID) &&
              asBool(dut.io.AXI.AR.ARREADY)) {
            issued += ((asLong(dut.io.AXI.AR.ARID).toInt,
              asLong(dut.io.AXI.AR.ARADDR)))
          }
          if (asBool(dut.io.response.valid) && asBool(dut.io.response.ready)) {
            responses += ((asLong(dut.io.response.bits.ID).toInt,
              asLong(dut.io.response.bits.data)))
          }
          dut.clock.step()
        }

        def sendRead(OrigID: Int, addr: Long): Unit = {
          dut.io.request.valid.poke(true.B)
          dut.io.request.bits.ID.poke(OrigID.U)
          dut.io.request.bits.address.poke(addr.U)
          dut.io.request.bits.write.poke(false.B)
          dut.io.request.bits.data.poke(0.U)
          dut.io.request.bits.strb.poke(0.U)
          var guard = 0
          while (!asBool(dut.io.request.ready) && guard < 50) {
            step()
            guard += 1
          }
          asBool(dut.io.request.ready) should be(true)
          step()
          dut.io.request.valid.poke(false.B)
        }

        def sendResponse(AXIID: Int, data: Long): Unit = {
          dut.io.AXI.R.RVALID.poke(true.B)
          dut.io.AXI.R.RID.poke(AXIID.U)
          dut.io.AXI.R.RDATA.poke(data.U)
          dut.io.AXI.R.RRESP.poke(0.U)
          dut.io.AXI.R.RLAST.poke(true.B)
          var guard = 0
          while (!asBool(dut.io.AXI.R.RREADY) && guard < 20) {
            step()
            guard += 1
          }
          asBool(dut.io.AXI.R.RREADY) should be(true)
          step()
          dut.io.AXI.R.RVALID.poke(false.B)
        }

        def collectUntil(count: Int): Unit = {
          var guard = 0
          while (responses.size < count && guard < 30) {
            step()
            guard += 1
          }
        }

        sendRead(2, 0x1000L)
        sendRead(7, 0x2000L)
        sendRead(5, 0x3000L)
        var guard = 0
        while (issued.size < 3 && guard < 30) {
          step()
          guard += 1
        }
        // 三笔读必须在任何响应返回前全部占用 AXI ID。
        issued.size should be(3)
        issued.map(_._1).toSet.size should be(3)
        issued.foreach { case (id, _) =>
          id should (be >= ysyx_26030103_AXIReadID.PTWBase and
            be <= ysyx_26030103_AXIReadID.PTWLast)
        }
        val IDByAddress = issued.map { case (id, addr) => addr -> id }.toMap
        IDByAddress.keySet should be(Set(0x1000L, 0x2000L, 0x3000L))

        // 乱序返回：每笔响应必须按 AXI ID 送回对应的 PTW 原 ID。
        sendResponse(IDByAddress(0x2000L), 0x777L)
        collectUntil(1)
        responses should be(Seq(7 -> 0x777L))
        sendResponse(IDByAddress(0x1000L), 0x222L)
        collectUntil(2)
        responses(1) should be(2 -> 0x222L)
        sendResponse(IDByAddress(0x3000L), 0x555L)
        collectUntil(3)
        responses(2) should be(5 -> 0x555L)
    }
  }

  "PTWToAXI" should "在 ARREADY 反压期间保持页表读 ID/地址稳定" in {
    simulate(new ysyx_26030103_PTWToAXI(busDepth = 4, AXIReadSlots = 4)) {
      dut =>
        dut.reset.poke(true.B)
        dut.clock.step(2)
        dut.reset.poke(false.B)
        dut.io.request.valid.poke(false.B)
        dut.io.request.bits.ID.poke(0.U)
        dut.io.request.bits.address.poke(0.U)
        dut.io.request.bits.write.poke(false.B)
        dut.io.request.bits.data.poke(0.U)
        dut.io.request.bits.strb.poke(0.U)
        dut.io.response.ready.poke(true.B)
        idleMasterInputs(dut.io.AXI)
        idleMasterInputs(dut.io.AXIWrite)

        val issued = scala.collection.mutable.ArrayBuffer.empty[(Int, Long)]
        val responses = scala.collection.mutable.ArrayBuffer.empty[(Int, Long)]
        def step(): Unit = {
          if (asBool(dut.io.AXI.AR.ARVALID) &&
              asBool(dut.io.AXI.AR.ARREADY)) {
            issued += ((asLong(dut.io.AXI.AR.ARID).toInt,
              asLong(dut.io.AXI.AR.ARADDR)))
          }
          if (asBool(dut.io.response.valid) && asBool(dut.io.response.ready)) {
            responses += ((asLong(dut.io.response.bits.ID).toInt,
              asLong(dut.io.response.bits.data)))
          }
          dut.clock.step()
        }
        def sendRead(OrigID: Int, addr: Long): Unit = {
          dut.io.request.valid.poke(true.B)
          dut.io.request.bits.ID.poke(OrigID.U)
          dut.io.request.bits.address.poke(addr.U)
          dut.io.request.bits.write.poke(false.B)
          var guard = 0
          while (!asBool(dut.io.request.ready) && guard < 20) {
            step(); guard += 1
          }
          step()
          dut.io.request.valid.poke(false.B)
        }
        def sendResponse(AXIID: Int, data: Long): Unit = {
          dut.io.AXI.R.RVALID.poke(true.B)
          dut.io.AXI.R.RID.poke(AXIID.U)
          dut.io.AXI.R.RDATA.poke(data.U)
          dut.io.AXI.R.RRESP.poke(0.U)
          dut.io.AXI.R.RLAST.poke(true.B)
          var guard = 0
          while (!asBool(dut.io.AXI.R.RREADY) && guard < 20) {
            step(); guard += 1
          }
          asBool(dut.io.AXI.R.RREADY) should be(true)
          step()
          dut.io.AXI.R.RVALID.poke(false.B)
        }

        // 先在 slot0 发起一笔读。
        dut.io.AXI.AR.ARREADY.poke(true.B)
        sendRead(1, 0x1000L)
        var guard = 0
        while (issued.size < 1 && guard < 20) { step(); guard += 1 }
        issued should be(Seq(3 -> 0x1000L))

        // 关闭 ARREADY，让第二笔读停在 slot1 的 AR 上。
        dut.io.AXI.AR.ARREADY.poke(false.B)
        sendRead(2, 0x2000L)
        guard = 0
        while (!asBool(dut.io.AXI.AR.ARVALID) && guard < 20) {
          step(); guard += 1
        }
        asLong(dut.io.AXI.AR.ARID) should be(4L)
        asLong(dut.io.AXI.AR.ARADDR) should be(0x2000L)

        // slot0 的响应返回并被 PTW 接收后，slot0 变空。此时被反压的
        // slot1 AR 不能把 ID 从 4 改成 3，否则违反 AXI 稳定性。
        sendResponse(3, 0xaaa1L)
        guard = 0
        while (responses.isEmpty && guard < 20) { step(); guard += 1 }
        responses should be(Seq(1 -> 0xaaa1L))
        for (_ <- 0 until 3) {
          step()
          asBool(dut.io.AXI.AR.ARVALID) should be(true)
          asLong(dut.io.AXI.AR.ARID) should be(4L)
          asLong(dut.io.AXI.AR.ARADDR) should be(0x2000L)
        }

        dut.io.AXI.AR.ARREADY.poke(true.B)
        step()
        issued should be(Seq(3 -> 0x1000L, 4 -> 0x2000L))
        sendResponse(4, 0xbbb2L)
        guard = 0
        while (responses.size < 2 && guard < 20) { step(); guard += 1 }
        responses should be(Seq(1 -> 0xaaa1L, 2 -> 0xbbb2L))
    }
  }

  "AXIReadMux" should "允许 ICache burst 与多笔 PTW 读重叠" in {
    simulate(new ysyx_26030103_AXIReadMux()) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      idleSlaveInputs(dut.io.a)
      idleSlaveInputs(dut.io.b)
      idleMasterInputs(dut.io.out)

      val issued = scala.collection.mutable.ArrayBuffer.empty[(Int, Long, Int)]
      val aResponse = scala.collection.mutable.ArrayBuffer.empty[(Int, Long)]
      val bResponse = scala.collection.mutable.ArrayBuffer.empty[(Int, Long)]

      def step(): Unit = {
        if (asBool(dut.io.out.AR.ARVALID) &&
            asBool(dut.io.out.AR.ARREADY)) {
          issued += ((asLong(dut.io.out.AR.ARID).toInt,
            asLong(dut.io.out.AR.ARADDR),
            asLong(dut.io.out.AR.ARLEN).toInt))
        }
        if (asBool(dut.io.a.R.RVALID) && asBool(dut.io.a.R.RREADY)) {
          aResponse += ((asLong(dut.io.a.R.RID).toInt,
            asLong(dut.io.a.R.RDATA)))
        }
        if (asBool(dut.io.b.R.RVALID) && asBool(dut.io.b.R.RREADY)) {
          bResponse += ((asLong(dut.io.b.R.RID).toInt,
            asLong(dut.io.b.R.RDATA)))
        }
        dut.clock.step()
      }

      def issueA(addr: Long, length: Int): Unit = {
        dut.io.a.AR.ARVALID.poke(true.B)
        dut.io.a.AR.ARID.poke(0.U)
        dut.io.a.AR.ARADDR.poke(addr.U)
        dut.io.a.AR.ARLEN.poke(length.U)
        var guard = 0
        while (!asBool(dut.io.a.AR.ARREADY) && guard < 20) {
          step()
          guard += 1
        }
        asBool(dut.io.a.AR.ARREADY) should be(true)
        step()
        dut.io.a.AR.ARVALID.poke(false.B)
      }

      def issueB(ID: Int, addr: Long): Unit = {
        dut.io.b.AR.ARVALID.poke(true.B)
        dut.io.b.AR.ARID.poke(ID.U)
        dut.io.b.AR.ARADDR.poke(addr.U)
        dut.io.b.AR.ARLEN.poke(0.U)
        var guard = 0
        while (!asBool(dut.io.b.AR.ARREADY) && guard < 20) {
          step()
          guard += 1
        }
        asBool(dut.io.b.AR.ARREADY) should be(true)
        step()
        dut.io.b.AR.ARVALID.poke(false.B)
      }

      def sendResponse(rid: Int, data: Long, last: Boolean): Unit = {
        dut.io.out.R.RVALID.poke(true.B)
        dut.io.out.R.RID.poke(rid.U)
        dut.io.out.R.RDATA.poke(data.U)
        dut.io.out.R.RRESP.poke(0.U)
        dut.io.out.R.RLAST.poke(last.B)
        var guard = 0
        while (!asBool(dut.io.out.R.RREADY) && guard < 20) {
          step()
          guard += 1
        }
        asBool(dut.io.out.R.RREADY) should be(true)
        step()
        dut.io.out.R.RVALID.poke(false.B)
      }

      // 先让 ICache 4 拍 burst 在途，再连续发三笔 PTW 单拍读。
      issueA(0x1000L, 3)
      issueB(3, 0x2000L)
      issueB(4, 0x3000L)
      issueB(5, 0x4000L)
      issued.size should be(4)
      issued.map(_._1) should be(Seq(0, 3, 4, 5))
      issued.head._2 should be(0x1000L)
      issued.head._3 should be(3)

      // 响应乱序返回；A 的 burst 与 B 的单拍读交错，但各自数据不能串。
      sendResponse(5, 0x55L, last = true)
      sendResponse(0, 0x10L, last = false)
      sendResponse(3, 0x33L, last = true)
      sendResponse(0, 0x11L, last = false)
      sendResponse(0, 0x12L, last = false)
      sendResponse(4, 0x44L, last = true)
      sendResponse(0, 0x13L, last = true)

      aResponse should be(Seq(
        0 -> 0x10L,
        0 -> 0x11L,
        0 -> 0x12L,
        0 -> 0x13L
      ))
      bResponse should be(Seq(
        5 -> 0x55L,
        3 -> 0x33L,
        4 -> 0x44L
      ))
    }
  }

  "AXI5Arbiter" should "允许 IFU 多 ID 与 LSU/DMA 同时在途并正确回路由" in {
    simulate(new ysyx_26030103_AXI5Arbiter) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      idleSlaveInputs(dut.io.ifu)
      idleSlaveInputs(dut.io.LSU)
      idleSlaveInputs(dut.io.DMA)
      idleMasterInputs(dut.io.memory)
      dut.io.DMAARLock.poke(false.B)
      dut.io.atomicBusy.poke(false.B)
      dut.io.DMAAWLock.poke(false.B)

      case class Request(addr: Long, ID: Int, length: Int)
      val IFUQ = scala.collection.mutable.Queue(
        Request(0x1000L, 0, 3),
        Request(0x1300L, 3, 0),
        Request(0x1400L, 4, 0)
      )
      val LSUQ = scala.collection.mutable.Queue(Request(0x2000L, 0, 0))
      val DMAQ = scala.collection.mutable.Queue(Request(0xc0001000L, 7, 0))

      val ARLog = scala.collection.mutable.ArrayBuffer.empty[(Int, Long)]
      val IFUResponse = scala.collection.mutable.ArrayBuffer.empty[(Int, Long)]
      val LSUResponse = scala.collection.mutable.ArrayBuffer.empty[(Int, Long)]
      val DMAResponse = scala.collection.mutable.ArrayBuffer.empty[(Int, Long)]

      def step(): Unit = {
        IFUQ.headOption match {
          case Some(r) =>
            dut.io.ifu.AR.ARVALID.poke(true.B)
            dut.io.ifu.AR.ARID.poke(r.ID.U)
            dut.io.ifu.AR.ARADDR.poke(r.addr.U)
            dut.io.ifu.AR.ARLEN.poke(r.length.U)
          case None => dut.io.ifu.AR.ARVALID.poke(false.B)
        }
        LSUQ.headOption match {
          case Some(r) =>
            dut.io.LSU.AR.ARVALID.poke(true.B)
            dut.io.LSU.AR.ARID.poke(r.ID.U)
            dut.io.LSU.AR.ARADDR.poke(r.addr.U)
            dut.io.LSU.AR.ARLEN.poke(r.length.U)
          case None => dut.io.LSU.AR.ARVALID.poke(false.B)
        }
        DMAQ.headOption match {
          case Some(r) =>
            dut.io.DMA.AR.ARVALID.poke(true.B)
            dut.io.DMA.AR.ARID.poke(r.ID.U)
            dut.io.DMA.AR.ARADDR.poke(r.addr.U)
            dut.io.DMA.AR.ARLEN.poke(r.length.U)
          case None => dut.io.DMA.AR.ARVALID.poke(false.B)
        }

        val IFUFire = IFUQ.nonEmpty && asBool(dut.io.ifu.AR.ARREADY)
        val LSUFire = LSUQ.nonEmpty && asBool(dut.io.LSU.AR.ARREADY)
        val DMAFire = DMAQ.nonEmpty && asBool(dut.io.DMA.AR.ARREADY)
        if (asBool(dut.io.memory.AR.ARVALID) &&
            asBool(dut.io.memory.AR.ARREADY)) {
          ARLog += ((asLong(dut.io.memory.AR.ARID).toInt,
            asLong(dut.io.memory.AR.ARADDR)))
        }
        if (asBool(dut.io.ifu.R.RVALID) &&
            asBool(dut.io.ifu.R.RREADY)) {
          IFUResponse += ((asLong(dut.io.ifu.R.RID).toInt,
            asLong(dut.io.ifu.R.RDATA)))
        }
        if (asBool(dut.io.LSU.R.RVALID) &&
            asBool(dut.io.LSU.R.RREADY)) {
          LSUResponse += ((asLong(dut.io.LSU.R.RID).toInt,
            asLong(dut.io.LSU.R.RDATA)))
        }
        if (asBool(dut.io.DMA.R.RVALID) &&
            asBool(dut.io.DMA.R.RREADY)) {
          DMAResponse += ((asLong(dut.io.DMA.R.RID).toInt,
            asLong(dut.io.DMA.R.RDATA)))
        }

        dut.clock.step()
        if (IFUFire) { IFUQ.dequeue(); () }
        if (LSUFire) { LSUQ.dequeue(); () }
        if (DMAFire) { DMAQ.dequeue(); () }
      }

      def sendResponse(rid: Int, data: Long, last: Boolean): Unit = {
        dut.io.memory.R.RVALID.poke(true.B)
        dut.io.memory.R.RID.poke(rid.U)
        dut.io.memory.R.RDATA.poke(data.U)
        dut.io.memory.R.RRESP.poke(0.U)
        dut.io.memory.R.RLAST.poke(last.B)
        var guard = 0
        while (!asBool(dut.io.memory.R.RREADY) && guard < 20) {
          step()
          guard += 1
        }
        asBool(dut.io.memory.R.RREADY) should be(true)
        step()
        dut.io.memory.R.RVALID.poke(false.B)
      }

      def collectIFU(count: Int): Unit = {
        var guard = 0
        while (IFUResponse.size < count && guard < 30) {
          step()
          guard += 1
        }
      }

      // 三笔 IFU 读（ICache burst + 两笔 PTW）与 LSU、DMA 同时请求。
      var guard = 0
      while ((IFUQ.nonEmpty || LSUQ.nonEmpty || DMAQ.nonEmpty) && guard < 40) {
        step()
        guard += 1
      }
      IFUQ shouldBe empty
      LSUQ shouldBe empty
      DMAQ shouldBe empty
      ARLog.size should be(5)
      // 下游 ID：IFU 0/3/4 原样，LSU 局部 0 映射到 1，DMA 固定 2。
      val IDByAddress = ARLog.map { case (id, addr) => addr -> id }.toMap
      IDByAddress(0x1000L) should be(0)
      IDByAddress(0x1300L) should be(3)
      IDByAddress(0x1400L) should be(4)
      IDByAddress(0x2000L) should be(1)
      IDByAddress(0xc0001000L) should be(2)

      // 响应乱序返回，IFU 的多个 ID、LSU 和 DMA 必须各自路由。
      sendResponse(4, 0x44L, last = true)
      collectIFU(1)
      IFUResponse should be(Seq(4 -> 0x44L))

      sendResponse(1, 0x91L, last = true)
      guard = 0
      while (LSUResponse.isEmpty && guard < 20) { step(); guard += 1 }
      LSUResponse should be(Seq(0 -> 0x91L))

      sendResponse(0, 0x10L, last = false)
      collectIFU(2)
      sendResponse(3, 0x33L, last = true)
      collectIFU(3)
      sendResponse(0, 0x11L, last = false)
      collectIFU(4)
      sendResponse(2, 0xd2L, last = true)
      guard = 0
      while (DMAResponse.isEmpty && guard < 20) { step(); guard += 1 }
      DMAResponse should be(Seq(7 -> 0xd2L))

      sendResponse(0, 0x12L, last = false)
      collectIFU(5)
      sendResponse(0, 0x13L, last = true)
      collectIFU(6)

      IFUResponse should be(Seq(
        4 -> 0x44L,
        0 -> 0x10L,
        3 -> 0x33L,
        0 -> 0x11L,
        0 -> 0x12L,
        0 -> 0x13L
      ))
    }
  }
}
