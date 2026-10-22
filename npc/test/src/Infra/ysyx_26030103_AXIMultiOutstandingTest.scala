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
    simulate(new ysyx_26030103_PTWToAXI(busDepth = 8, axiReadSlots = 4)) {
      dut =>
        dut.reset.poke(true.B)
        dut.clock.step(2)
        dut.reset.poke(false.B)
        // req/resp 不是 AXI5IO，单独初始化。
        dut.io.req.valid.poke(false.B)
        dut.io.req.bits.id.poke(0.U)
        dut.io.req.bits.addr.poke(0.U)
        dut.io.req.bits.write.poke(false.B)
        dut.io.req.bits.data.poke(0.U)
        dut.io.req.bits.strb.poke(0.U)
        dut.io.resp.ready.poke(true.B)
        idleMasterInputs(dut.io.axi)
        idleMasterInputs(dut.io.axiWrite)

        val issued = scala.collection.mutable.ArrayBuffer.empty[(Int, Long)]
        val responses = scala.collection.mutable.ArrayBuffer.empty[(Int, Long)]

        def step(): Unit = {
          if (asBool(dut.io.axi.AR.ARVALID) &&
              asBool(dut.io.axi.AR.ARREADY)) {
            issued += ((asLong(dut.io.axi.AR.ARID).toInt,
              asLong(dut.io.axi.AR.ARADDR)))
          }
          if (asBool(dut.io.resp.valid) && asBool(dut.io.resp.ready)) {
            responses += ((asLong(dut.io.resp.bits.id).toInt,
              asLong(dut.io.resp.bits.data)))
          }
          dut.clock.step()
        }

        def sendRead(origId: Int, addr: Long): Unit = {
          dut.io.req.valid.poke(true.B)
          dut.io.req.bits.id.poke(origId.U)
          dut.io.req.bits.addr.poke(addr.U)
          dut.io.req.bits.write.poke(false.B)
          dut.io.req.bits.data.poke(0.U)
          dut.io.req.bits.strb.poke(0.U)
          var guard = 0
          while (!asBool(dut.io.req.ready) && guard < 50) {
            step()
            guard += 1
          }
          asBool(dut.io.req.ready) should be(true)
          step()
          dut.io.req.valid.poke(false.B)
        }

        def sendResp(axiId: Int, data: Long): Unit = {
          dut.io.axi.R.RVALID.poke(true.B)
          dut.io.axi.R.RID.poke(axiId.U)
          dut.io.axi.R.RDATA.poke(data.U)
          dut.io.axi.R.RRESP.poke(0.U)
          dut.io.axi.R.RLAST.poke(true.B)
          var guard = 0
          while (!asBool(dut.io.axi.R.RREADY) && guard < 20) {
            step()
            guard += 1
          }
          asBool(dut.io.axi.R.RREADY) should be(true)
          step()
          dut.io.axi.R.RVALID.poke(false.B)
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
          id should (be >= ysyx_26030103_AXIReadId.PtwBase and
            be <= ysyx_26030103_AXIReadId.PtwLast)
        }
        val idByAddr = issued.map { case (id, addr) => addr -> id }.toMap
        idByAddr.keySet should be(Set(0x1000L, 0x2000L, 0x3000L))

        // 乱序返回：每笔响应必须按 AXI ID 送回对应的 PTW 原 ID。
        sendResp(idByAddr(0x2000L), 0x777L)
        collectUntil(1)
        responses should be(Seq(7 -> 0x777L))
        sendResp(idByAddr(0x1000L), 0x222L)
        collectUntil(2)
        responses(1) should be(2 -> 0x222L)
        sendResp(idByAddr(0x3000L), 0x555L)
        collectUntil(3)
        responses(2) should be(5 -> 0x555L)
    }
  }

  "PTWToAXI" should "在 ARREADY 反压期间保持页表读 ID/地址稳定" in {
    simulate(new ysyx_26030103_PTWToAXI(busDepth = 4, axiReadSlots = 4)) {
      dut =>
        dut.reset.poke(true.B)
        dut.clock.step(2)
        dut.reset.poke(false.B)
        dut.io.req.valid.poke(false.B)
        dut.io.req.bits.id.poke(0.U)
        dut.io.req.bits.addr.poke(0.U)
        dut.io.req.bits.write.poke(false.B)
        dut.io.req.bits.data.poke(0.U)
        dut.io.req.bits.strb.poke(0.U)
        dut.io.resp.ready.poke(true.B)
        idleMasterInputs(dut.io.axi)
        idleMasterInputs(dut.io.axiWrite)

        val issued = scala.collection.mutable.ArrayBuffer.empty[(Int, Long)]
        val responses = scala.collection.mutable.ArrayBuffer.empty[(Int, Long)]
        def step(): Unit = {
          if (asBool(dut.io.axi.AR.ARVALID) &&
              asBool(dut.io.axi.AR.ARREADY)) {
            issued += ((asLong(dut.io.axi.AR.ARID).toInt,
              asLong(dut.io.axi.AR.ARADDR)))
          }
          if (asBool(dut.io.resp.valid) && asBool(dut.io.resp.ready)) {
            responses += ((asLong(dut.io.resp.bits.id).toInt,
              asLong(dut.io.resp.bits.data)))
          }
          dut.clock.step()
        }
        def sendRead(origId: Int, addr: Long): Unit = {
          dut.io.req.valid.poke(true.B)
          dut.io.req.bits.id.poke(origId.U)
          dut.io.req.bits.addr.poke(addr.U)
          dut.io.req.bits.write.poke(false.B)
          var guard = 0
          while (!asBool(dut.io.req.ready) && guard < 20) {
            step(); guard += 1
          }
          step()
          dut.io.req.valid.poke(false.B)
        }
        def sendResp(axiId: Int, data: Long): Unit = {
          dut.io.axi.R.RVALID.poke(true.B)
          dut.io.axi.R.RID.poke(axiId.U)
          dut.io.axi.R.RDATA.poke(data.U)
          dut.io.axi.R.RRESP.poke(0.U)
          dut.io.axi.R.RLAST.poke(true.B)
          var guard = 0
          while (!asBool(dut.io.axi.R.RREADY) && guard < 20) {
            step(); guard += 1
          }
          asBool(dut.io.axi.R.RREADY) should be(true)
          step()
          dut.io.axi.R.RVALID.poke(false.B)
        }

        // 先在 slot0 发起一笔读。
        dut.io.axi.AR.ARREADY.poke(true.B)
        sendRead(1, 0x1000L)
        var guard = 0
        while (issued.size < 1 && guard < 20) { step(); guard += 1 }
        issued should be(Seq(3 -> 0x1000L))

        // 关闭 ARREADY，让第二笔读停在 slot1 的 AR 上。
        dut.io.axi.AR.ARREADY.poke(false.B)
        sendRead(2, 0x2000L)
        guard = 0
        while (!asBool(dut.io.axi.AR.ARVALID) && guard < 20) {
          step(); guard += 1
        }
        asLong(dut.io.axi.AR.ARID) should be(4L)
        asLong(dut.io.axi.AR.ARADDR) should be(0x2000L)

        // slot0 的响应返回并被 PTW 接收后，slot0 变空。此时被反压的
        // slot1 AR 不能把 ID 从 4 改成 3，否则违反 AXI 稳定性。
        sendResp(3, 0xaaa1L)
        guard = 0
        while (responses.isEmpty && guard < 20) { step(); guard += 1 }
        responses should be(Seq(1 -> 0xaaa1L))
        for (_ <- 0 until 3) {
          step()
          asBool(dut.io.axi.AR.ARVALID) should be(true)
          asLong(dut.io.axi.AR.ARID) should be(4L)
          asLong(dut.io.axi.AR.ARADDR) should be(0x2000L)
        }

        dut.io.axi.AR.ARREADY.poke(true.B)
        step()
        issued should be(Seq(3 -> 0x1000L, 4 -> 0x2000L))
        sendResp(4, 0xbbb2L)
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
      val aResp = scala.collection.mutable.ArrayBuffer.empty[(Int, Long)]
      val bResp = scala.collection.mutable.ArrayBuffer.empty[(Int, Long)]

      def step(): Unit = {
        if (asBool(dut.io.out.AR.ARVALID) &&
            asBool(dut.io.out.AR.ARREADY)) {
          issued += ((asLong(dut.io.out.AR.ARID).toInt,
            asLong(dut.io.out.AR.ARADDR),
            asLong(dut.io.out.AR.ARLEN).toInt))
        }
        if (asBool(dut.io.a.R.RVALID) && asBool(dut.io.a.R.RREADY)) {
          aResp += ((asLong(dut.io.a.R.RID).toInt,
            asLong(dut.io.a.R.RDATA)))
        }
        if (asBool(dut.io.b.R.RVALID) && asBool(dut.io.b.R.RREADY)) {
          bResp += ((asLong(dut.io.b.R.RID).toInt,
            asLong(dut.io.b.R.RDATA)))
        }
        dut.clock.step()
      }

      def issueA(addr: Long, len: Int): Unit = {
        dut.io.a.AR.ARVALID.poke(true.B)
        dut.io.a.AR.ARID.poke(0.U)
        dut.io.a.AR.ARADDR.poke(addr.U)
        dut.io.a.AR.ARLEN.poke(len.U)
        var guard = 0
        while (!asBool(dut.io.a.AR.ARREADY) && guard < 20) {
          step()
          guard += 1
        }
        asBool(dut.io.a.AR.ARREADY) should be(true)
        step()
        dut.io.a.AR.ARVALID.poke(false.B)
      }

      def issueB(id: Int, addr: Long): Unit = {
        dut.io.b.AR.ARVALID.poke(true.B)
        dut.io.b.AR.ARID.poke(id.U)
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

      def sendResp(rid: Int, data: Long, last: Boolean): Unit = {
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
      sendResp(5, 0x55L, last = true)
      sendResp(0, 0x10L, last = false)
      sendResp(3, 0x33L, last = true)
      sendResp(0, 0x11L, last = false)
      sendResp(0, 0x12L, last = false)
      sendResp(4, 0x44L, last = true)
      sendResp(0, 0x13L, last = true)

      aResp should be(Seq(
        0 -> 0x10L,
        0 -> 0x11L,
        0 -> 0x12L,
        0 -> 0x13L
      ))
      bResp should be(Seq(
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
      idleSlaveInputs(dut.io.lsu)
      idleSlaveInputs(dut.io.dma)
      idleMasterInputs(dut.io.memory)
      dut.io.dmaARLock.poke(false.B)
      dut.io.dmaAWLock.poke(false.B)

      case class Req(addr: Long, id: Int, len: Int)
      val ifuQ = scala.collection.mutable.Queue(
        Req(0x1000L, 0, 3),
        Req(0x1300L, 3, 0),
        Req(0x1400L, 4, 0)
      )
      val lsuQ = scala.collection.mutable.Queue(Req(0x2000L, 0, 0))
      val dmaQ = scala.collection.mutable.Queue(Req(0xc0001000L, 7, 0))

      val arLog = scala.collection.mutable.ArrayBuffer.empty[(Int, Long)]
      val ifuResp = scala.collection.mutable.ArrayBuffer.empty[(Int, Long)]
      val lsuResp = scala.collection.mutable.ArrayBuffer.empty[(Int, Long)]
      val dmaResp = scala.collection.mutable.ArrayBuffer.empty[(Int, Long)]

      def step(): Unit = {
        ifuQ.headOption match {
          case Some(r) =>
            dut.io.ifu.AR.ARVALID.poke(true.B)
            dut.io.ifu.AR.ARID.poke(r.id.U)
            dut.io.ifu.AR.ARADDR.poke(r.addr.U)
            dut.io.ifu.AR.ARLEN.poke(r.len.U)
          case None => dut.io.ifu.AR.ARVALID.poke(false.B)
        }
        lsuQ.headOption match {
          case Some(r) =>
            dut.io.lsu.AR.ARVALID.poke(true.B)
            dut.io.lsu.AR.ARID.poke(r.id.U)
            dut.io.lsu.AR.ARADDR.poke(r.addr.U)
            dut.io.lsu.AR.ARLEN.poke(r.len.U)
          case None => dut.io.lsu.AR.ARVALID.poke(false.B)
        }
        dmaQ.headOption match {
          case Some(r) =>
            dut.io.dma.AR.ARVALID.poke(true.B)
            dut.io.dma.AR.ARID.poke(r.id.U)
            dut.io.dma.AR.ARADDR.poke(r.addr.U)
            dut.io.dma.AR.ARLEN.poke(r.len.U)
          case None => dut.io.dma.AR.ARVALID.poke(false.B)
        }

        val ifuFire = ifuQ.nonEmpty && asBool(dut.io.ifu.AR.ARREADY)
        val lsuFire = lsuQ.nonEmpty && asBool(dut.io.lsu.AR.ARREADY)
        val dmaFire = dmaQ.nonEmpty && asBool(dut.io.dma.AR.ARREADY)
        if (asBool(dut.io.memory.AR.ARVALID) &&
            asBool(dut.io.memory.AR.ARREADY)) {
          arLog += ((asLong(dut.io.memory.AR.ARID).toInt,
            asLong(dut.io.memory.AR.ARADDR)))
        }
        if (asBool(dut.io.ifu.R.RVALID) &&
            asBool(dut.io.ifu.R.RREADY)) {
          ifuResp += ((asLong(dut.io.ifu.R.RID).toInt,
            asLong(dut.io.ifu.R.RDATA)))
        }
        if (asBool(dut.io.lsu.R.RVALID) &&
            asBool(dut.io.lsu.R.RREADY)) {
          lsuResp += ((asLong(dut.io.lsu.R.RID).toInt,
            asLong(dut.io.lsu.R.RDATA)))
        }
        if (asBool(dut.io.dma.R.RVALID) &&
            asBool(dut.io.dma.R.RREADY)) {
          dmaResp += ((asLong(dut.io.dma.R.RID).toInt,
            asLong(dut.io.dma.R.RDATA)))
        }

        dut.clock.step()
        if (ifuFire) { ifuQ.dequeue(); () }
        if (lsuFire) { lsuQ.dequeue(); () }
        if (dmaFire) { dmaQ.dequeue(); () }
      }

      def sendResp(rid: Int, data: Long, last: Boolean): Unit = {
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

      def collectIfu(count: Int): Unit = {
        var guard = 0
        while (ifuResp.size < count && guard < 30) {
          step()
          guard += 1
        }
      }

      // 三笔 IFU 读（ICache burst + 两笔 PTW）与 LSU、DMA 同时请求。
      var guard = 0
      while ((ifuQ.nonEmpty || lsuQ.nonEmpty || dmaQ.nonEmpty) && guard < 40) {
        step()
        guard += 1
      }
      ifuQ shouldBe empty
      lsuQ shouldBe empty
      dmaQ shouldBe empty
      arLog.size should be(5)
      // 下游 ID：IFU 0/3/4 原样，LSU 局部 0 映射到 1，DMA 固定 2。
      val idByAddr = arLog.map { case (id, addr) => addr -> id }.toMap
      idByAddr(0x1000L) should be(0)
      idByAddr(0x1300L) should be(3)
      idByAddr(0x1400L) should be(4)
      idByAddr(0x2000L) should be(1)
      idByAddr(0xc0001000L) should be(2)

      // 响应乱序返回，IFU 的多个 ID、LSU 和 DMA 必须各自路由。
      sendResp(4, 0x44L, last = true)
      collectIfu(1)
      ifuResp should be(Seq(4 -> 0x44L))

      sendResp(1, 0x91L, last = true)
      guard = 0
      while (lsuResp.isEmpty && guard < 20) { step(); guard += 1 }
      lsuResp should be(Seq(0 -> 0x91L))

      sendResp(0, 0x10L, last = false)
      collectIfu(2)
      sendResp(3, 0x33L, last = true)
      collectIfu(3)
      sendResp(0, 0x11L, last = false)
      collectIfu(4)
      sendResp(2, 0xd2L, last = true)
      guard = 0
      while (dmaResp.isEmpty && guard < 20) { step(); guard += 1 }
      dmaResp should be(Seq(7 -> 0xd2L))

      sendResp(0, 0x12L, last = false)
      collectIfu(5)
      sendResp(0, 0x13L, last = true)
      collectIfu(6)

      ifuResp should be(Seq(
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
