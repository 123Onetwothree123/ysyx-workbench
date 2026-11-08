package ysyx_26030103.audit

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ysyx_26030103.infra._

/** AXIWriteMux 必须按 AWLEN 转发全部 W beat。
  *
  * LSU 在 32 位总线上做 8 字节 store 时会发出 AWLEN=1、AWSIZE=2 的两拍写。
  * 当前 `drive` 路径在第一拍 W 握手后就把 `wDone` 置 1 并跳到响应状态，
  * 若属实，第二拍 W 永远不会被下游接受，下游等 WLAST、上游等 B。
  * 本测试定向证明：两拍 W 必须都被转发，且 WLAST 必须出现在 out.W，
  * 并且在第二拍之前不得进入 B 响应状态。
  */
class ysyx_26030103_AXIWriteMuxWBeatAuditTest
    extends AnyFlatSpec
    with Matchers {
  private def bool(value: Bool): Boolean = value.peek().litValue == 1
  private def big(value: UInt): BigInt = value.peek().litValue

  private def resetDut(dut: ysyx_26030103_AXIWriteMux): Unit = {
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)

    // A 口（LSU）：写通道由本测试驱动；读通道在 PassRead=false 下不使用，
    // 但仍把所有输入打到确定值。
    dut.io.a.AW.AWVALID.poke(false.B)
    dut.io.a.AW.AWID.poke(0.U(4.W))
    dut.io.a.AW.AWADDR.poke(0.U(32.W))
    dut.io.a.AW.AWLEN.poke(0.U(8.W))
    dut.io.a.AW.AWSIZE.poke(2.U(3.W))
    dut.io.a.AW.AWBURST.poke(0.U(2.W))
    dut.io.a.AW.AWPROT.poke(0.U(3.W))
    dut.io.a.AW.AWCACHE.poke(0.U(4.W))
    dut.io.a.AW.AWQOS.poke(0.U(4.W))
    dut.io.a.W.WVALID.poke(false.B)
    dut.io.a.W.WDATA.poke(0.U(32.W))
    dut.io.a.W.WSTRB.poke(0.U(4.W))
    dut.io.a.W.WLAST.poke(false.B)
    // 一直保持 LSU 侧 BREADY：这样 out.B.BREADY 只有在 mux 进入 aResp
    // 状态时才会变高，可以用来观察“是否过早进入响应状态”。
    dut.io.a.B.BREADY.poke(true.B)
    dut.io.a.AR.ARVALID.poke(false.B)
    dut.io.a.AR.ARID.poke(0.U(4.W))
    dut.io.a.AR.ARADDR.poke(0.U(32.W))
    dut.io.a.AR.ARLEN.poke(0.U(8.W))
    dut.io.a.AR.ARSIZE.poke(2.U(3.W))
    dut.io.a.AR.ARBURST.poke(0.U(2.W))
    dut.io.a.AR.ARPROT.poke(0.U(3.W))
    dut.io.a.AR.ARCACHE.poke(0.U(4.W))
    dut.io.a.AR.ARQOS.poke(0.U(4.W))
    dut.io.a.R.RREADY.poke(false.B)

    // B 口（PTW A/D 写回）：保持空闲，确保仲裁选中 A。
    dut.io.b.AW.AWVALID.poke(false.B)
    dut.io.b.AW.AWID.poke(0.U(4.W))
    dut.io.b.AW.AWADDR.poke(0.U(32.W))
    dut.io.b.AW.AWLEN.poke(0.U(8.W))
    dut.io.b.AW.AWSIZE.poke(2.U(3.W))
    dut.io.b.AW.AWBURST.poke(0.U(2.W))
    dut.io.b.AW.AWPROT.poke(0.U(3.W))
    dut.io.b.AW.AWCACHE.poke(0.U(4.W))
    dut.io.b.AW.AWQOS.poke(0.U(4.W))
    dut.io.b.W.WVALID.poke(false.B)
    dut.io.b.W.WDATA.poke(0.U(32.W))
    dut.io.b.W.WSTRB.poke(0.U(4.W))
    dut.io.b.W.WLAST.poke(false.B)
    dut.io.b.B.BREADY.poke(false.B)
    dut.io.b.AR.ARVALID.poke(false.B)
    dut.io.b.AR.ARID.poke(0.U(4.W))
    dut.io.b.AR.ARADDR.poke(0.U(32.W))
    dut.io.b.AR.ARLEN.poke(0.U(8.W))
    dut.io.b.AR.ARSIZE.poke(2.U(3.W))
    dut.io.b.AR.ARBURST.poke(0.U(2.W))
    dut.io.b.AR.ARPROT.poke(0.U(3.W))
    dut.io.b.AR.ARCACHE.poke(0.U(4.W))
    dut.io.b.AR.ARQOS.poke(0.U(4.W))
    dut.io.b.R.RREADY.poke(false.B)

    // 下游：永远接受 AW/W，暂时不返回 B（模拟仍在等剩余 W 拍的下游）。
    dut.io.out.AW.AWREADY.poke(true.B)
    dut.io.out.W.WREADY.poke(true.B)
    dut.io.out.B.BVALID.poke(false.B)
    dut.io.out.B.BID.poke(0.U(4.W))
    dut.io.out.B.BRESP.poke(0.U(2.W))
    dut.io.out.AR.ARREADY.poke(false.B)
    dut.io.out.R.RVALID.poke(false.B)
    dut.io.out.R.RID.poke(0.U(4.W))
    dut.io.out.R.RDATA.poke(0.U(32.W))
    dut.io.out.R.RRESP.poke(0.U(2.W))
    dut.io.out.R.RLAST.poke(false.B)
  }

  "AXIWriteMux" should "forward both W beats of an AWLEN=1 write" in {
    simulate(
      new ysyx_26030103_AXIWriteMux(
        PassRead = false,
        AddressWidth = 32,
        DataWidth = 32
      )
    ) { dut =>
      resetDut(dut)

      // 8 字节 store 在 32 位总线上的典型形状：AWLEN=1，两拍。
      val beats = Seq(BigInt("11223344", 16), BigInt("aabbccdd", 16))

      var awAccepted = false
      var awForwarded = false
      var beatIndex = 0 // 上游已被 A 侧 WREADY 接受的拍数
      var wBeatsSeen = 0 // out.W 上观察到的 WVALID&&WREADY 次数
      var lastSeen = false // out.W 上是否见到 WLAST=1
      var forwardedData = Seq.empty[BigInt]
      var responseEnteredEarly = false
      var responseReachedAfterAllBeats = false
      var cycles = 0

      // 有界仿真：40 拍足够 AW 握手 + 两拍 W 转发 + 进入响应状态。
      // 若第二拍被吞，循环会自然跑到 40 拍后超时失败。
      while (cycles < 40 && !(wBeatsSeen >= beats.length &&
          responseReachedAfterAllBeats)) {
        // A 侧 AW：AWLEN=1（两拍），AWSIZE=2（4 字节），AWBURST=INCR。
        dut.io.a.AW.AWVALID.poke((!awAccepted).B)
        dut.io.a.AW.AWID.poke(7.U(4.W))
        dut.io.a.AW.AWADDR.poke(0x1000.U(32.W))
        dut.io.a.AW.AWLEN.poke(1.U(8.W))
        dut.io.a.AW.AWSIZE.poke(2.U(3.W))
        dut.io.a.AW.AWBURST.poke(1.U(2.W))
        dut.io.a.AW.AWPROT.poke(0.U(3.W))
        dut.io.a.AW.AWCACHE.poke(0.U(4.W))
        dut.io.a.AW.AWQOS.poke(0.U(4.W))

        // A 侧 W：第一拍 WLAST=0，第二拍 WLAST=1。
        val offerBeat = beatIndex < beats.length
        dut.io.a.W.WVALID.poke(offerBeat.B)
        dut.io.a.W.WDATA.poke(
          (if (offerBeat) beats(beatIndex) else BigInt(0)).U(32.W)
        )
        dut.io.a.W.WSTRB.poke((if (offerBeat) BigInt(15) else BigInt(0)).U(4.W))
        dut.io.a.W.WLAST.poke(
          (offerBeat && beatIndex == beats.length - 1).B
        )

        // 在时钟沿之前采样本拍的握手与状态观测点。
        if (bool(dut.io.out.AW.AWVALID) && bool(dut.io.out.AW.AWREADY)) {
          awForwarded = true
          big(dut.io.out.AW.AWLEN) should be(1)
        }
        if (bool(dut.io.out.W.WVALID) && bool(dut.io.out.W.WREADY)) {
          wBeatsSeen += 1
          forwardedData = forwardedData :+ big(dut.io.out.W.WDATA)
          if (bool(dut.io.out.W.WLAST)) { lastSeen = true }
        }
        if (bool(dut.io.a.AW.AWVALID) && bool(dut.io.a.AW.AWREADY)) {
          awAccepted = true
        }

        // aResp 是唯一驱动 out.B.BREADY 的状态（a.B.BREADY 恒为 1）。
        // 第二拍转发完成前绝不能出现，否则就是“首拍后提前进入响应”。
        if (bool(dut.io.out.B.BREADY) && wBeatsSeen >= 1 &&
            wBeatsSeen < beats.length) {
          responseEnteredEarly = true
        }
        if (bool(dut.io.out.B.BREADY) && wBeatsSeen >= beats.length) {
          responseReachedAfterAllBeats = true
        }

        val upstreamWReady = bool(dut.io.a.W.WREADY)
        val upstreamWValid = offerBeat
        dut.clock.step()
        if (upstreamWValid && upstreamWReady) { beatIndex += 1 }
        cycles += 1
      }

      // 失败时打印完整观测到的 AW/W/B 状态，便于定位卡在哪一拍。
      val observed =
        s"wBeatsSeen=$wBeatsSeen (expected ${beats.length}), " +
          s"lastSeen=$lastSeen, forwardedData=${forwardedData.map(d => "0x" + d.toString(16)).mkString("[", ", ", "]")}, " +
          s"awAccepted=$awAccepted, awForwarded=$awForwarded, " +
          s"upstreamBeatsAccepted=$beatIndex, " +
          s"out.AW(AWVALID=${bool(dut.io.out.AW.AWVALID)}, AWREADY=${bool(dut.io.out.AW.AWREADY)}, " +
          s"AWLEN=${big(dut.io.out.AW.AWLEN)}, AWSIZE=${big(dut.io.out.AW.AWSIZE)}), " +
          s"out.W(WVALID=${bool(dut.io.out.W.WVALID)}, WREADY=${bool(dut.io.out.W.WREADY)}, " +
          s"WDATA=0x${big(dut.io.out.W.WDATA).toString(16)}, WLAST=${bool(dut.io.out.W.WLAST)}), " +
          s"a.W.WREADY=${bool(dut.io.a.W.WREADY)}, " +
          s"out.B.BREADY=${bool(dut.io.out.B.BREADY)}, " +
          s"a.B(BVALID=${bool(dut.io.a.B.BVALID)}, BREADY=${bool(dut.io.a.B.BREADY)}), " +
          s"out.B.BVALID=${bool(dut.io.out.B.BVALID)}, cycles=$cycles"

      withClue(
        s"AXIWriteMux must forward AWLEN+1 W beats for a burst store; " +
          s"the second beat was dropped, so downstream waits for WLAST " +
          s"while upstream waits for B. Observed: $observed. "
      ) {
        wBeatsSeen should be(beats.length)
      }

      withClue(
        s"AXIWriteMux must forward the final W beat with WLAST=1; " +
          s"WLAST was never seen on out.W. Observed: $observed. "
      ) {
        lastSeen should be(true)
      }

      withClue(
        s"AXIWriteMux entered the B response state before all W beats " +
          s"were forwarded (it must wait for both beats of AWLEN=1). " +
          s"Observed: $observed. "
      ) {
        responseEnteredEarly should be(false)
      }

      withClue(
        s"AXIWriteMux should reach the B response state after the last " +
          s"W beat is forwarded. Observed: $observed. "
      ) {
        responseReachedAfterAllBeats should be(true)
      }
    }
  }
}
