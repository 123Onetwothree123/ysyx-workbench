package ysyx_26030103.infra

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Xbar + PLIC 叶子联合测试，重点覆盖 PLIC 只支持单拍访问的约束。
  *
  * PLIC 叶子不处理 AWLEN/WLAST：多拍写会在首拍后拉低 WREADY 等待 B，
  * 而 Xbar 又在等待剩余 W 拍，形成 B/WREADY 互等。多拍读时 PLIC 首拍
  * 直接给出 RLAST=1，会让 Xbar 提前释放读槽位。Xbar 必须把 PLIC 的
  * 非单拍请求在本地判为 DECERR，并排空写数据。
  */
class ysyx_26030103_AuditXbarPlicHarness extends Module {
  val io = IO(new Bundle {
    val in = Flipped(new ysyx_26030103_AXI5IO(32, 32, 4))
    val sources = Input(UInt(32.W))
    val irq = Output(Bool())
    val plicAwValid = Output(Bool())
    val plicArValid = Output(Bool())
  })

  val xbar = Module(new ysyx_26030103_AXI5Xbar())
  val plic = Module(new ysyx_26030103_PLIC())

  xbar.io.in <> io.in
  plic.sources := io.sources
  io.irq := plic.external_irq
  io.plicAwValid := xbar.io.PLIC.AW.AWVALID
  io.plicArValid := xbar.io.PLIC.AR.ARVALID

  xbar.io.PLIC.AW <> plic.io.AW
  xbar.io.PLIC.W <> plic.io.W
  xbar.io.PLIC.B <> plic.io.B
  xbar.io.PLIC.AR <> plic.io.AR
  xbar.io.PLIC.R <> plic.io.R

  private def quietDownstream(m: ysyx_26030103_AXI5IO): Unit = {
    m.AW.AWREADY := false.B
    m.W.WREADY := false.B
    m.B.BVALID := false.B
    m.B.BID := 0.U
    m.B.BRESP := 0.U
    m.AR.ARREADY := false.B
    m.R.RVALID := false.B
    m.R.RID := 0.U
    m.R.RDATA := 0.U
    m.R.RRESP := 0.U
    m.R.RLAST := false.B
  }
  quietDownstream(xbar.io.SoCBus)
  quietDownstream(xbar.io.CLINT)
}

class ysyx_26030103_PLICBurstAuditTest extends AnyFlatSpec with Matchers {
  private def bool(value: Bool): Boolean = value.peek().litValue == 1

  private case class WriteResult(resp: Int, plicAwSeen: Boolean, cycles: Int)
  private case class ReadBeat(resp: Int, data: BigInt, last: Boolean)
  private case class ReadResult(
      beats: Seq[ReadBeat],
      plicArSeen: Boolean,
      cycles: Int
  )

  private def resetDut(dut: ysyx_26030103_AuditXbarPlicHarness): Unit = {
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
    dut.io.sources.poke(0.U)

    dut.io.in.AW.AWVALID.poke(false.B)
    dut.io.in.AW.AWADDR.poke(0.U)
    dut.io.in.AW.AWID.poke(0.U)
    dut.io.in.AW.AWLEN.poke(0.U)
    dut.io.in.AW.AWSIZE.poke(2.U)
    dut.io.in.AW.AWBURST.poke(0.U)
    dut.io.in.AW.AWPROT.poke(0.U)
    dut.io.in.AW.AWCACHE.poke(0.U)
    dut.io.in.AW.AWQOS.poke(0.U)
    dut.io.in.W.WVALID.poke(false.B)
    dut.io.in.W.WDATA.poke(0.U)
    dut.io.in.W.WSTRB.poke(0.U)
    dut.io.in.W.WLAST.poke(false.B)
    dut.io.in.B.BREADY.poke(false.B)
    dut.io.in.AR.ARVALID.poke(false.B)
    dut.io.in.AR.ARADDR.poke(0.U)
    dut.io.in.AR.ARID.poke(0.U)
    dut.io.in.AR.ARLEN.poke(0.U)
    dut.io.in.AR.ARSIZE.poke(2.U)
    dut.io.in.AR.ARBURST.poke(0.U)
    dut.io.in.AR.ARPROT.poke(0.U)
    dut.io.in.AR.ARCACHE.poke(0.U)
    dut.io.in.AR.ARQOS.poke(0.U)
    dut.io.in.R.RREADY.poke(false.B)
  }

  private def writeBurst(
      dut: ysyx_26030103_AuditXbarPlicHarness,
      addr: BigInt,
      len: Int,
      beats: Seq[BigInt]
  ): WriteResult = {
    var plicAwSeen = false
    var awSent = false
    var beatIndex = 0
    var resp = -1
    var cycles = 0
    dut.io.in.B.BREADY.poke(true.B)

    while (resp < 0 && cycles < 100) {
      val awValid = !awSent
      val wValid = beatIndex < beats.length
      dut.io.in.AW.AWVALID.poke(awValid.B)
      dut.io.in.AW.AWADDR.poke(addr.U(32.W))
      dut.io.in.AW.AWID.poke(1.U(4.W))
      dut.io.in.AW.AWLEN.poke(len.U(8.W))
      dut.io.in.AW.AWSIZE.poke(2.U(3.W))
      dut.io.in.AW.AWBURST.poke(1.U(2.W))
      dut.io.in.AW.AWPROT.poke(0.U(3.W))
      dut.io.in.AW.AWCACHE.poke(0.U(4.W))
      dut.io.in.AW.AWQOS.poke(0.U(4.W))
      dut.io.in.W.WVALID.poke(wValid.B)
      dut.io.in.W.WDATA.poke(
        (if (wValid) beats(beatIndex) else BigInt(0)).U(32.W)
      )
      dut.io.in.W.WSTRB.poke((if (wValid) BigInt(15) else BigInt(0)).U(4.W))
      dut.io.in.W.WLAST.poke(
        (wValid && beatIndex == beats.length - 1).B
      )

      if (bool(dut.io.plicAwValid)) { plicAwSeen = true }
      if (bool(dut.io.in.B.BVALID)) {
        resp = dut.io.in.B.BRESP.peek().litValue.toInt
      }

      val awReady = bool(dut.io.in.AW.AWREADY)
      val wReady = bool(dut.io.in.W.WREADY)
      dut.clock.step()
      if (awValid && awReady) { awSent = true }
      if (wValid && wReady) { beatIndex += 1 }
      cycles += 1
    }

    dut.io.in.AW.AWVALID.poke(false.B)
    dut.io.in.W.WVALID.poke(false.B)
    dut.io.in.B.BREADY.poke(false.B)
    WriteResult(resp, plicAwSeen, cycles)
  }

  private def readBurst(
      dut: ysyx_26030103_AuditXbarPlicHarness,
      addr: BigInt,
      len: Int
  ): ReadResult = {
    var plicArSeen = false
    var arSent = false
    var cycles = 0
    val beats = scala.collection.mutable.ArrayBuffer.empty[ReadBeat]
    dut.io.in.R.RREADY.poke(true.B)

    while ((beats.isEmpty || !beats.last.last) && cycles < 100) {
      val arValid = !arSent
      dut.io.in.AR.ARVALID.poke(arValid.B)
      dut.io.in.AR.ARADDR.poke(addr.U(32.W))
      dut.io.in.AR.ARID.poke(1.U(4.W))
      dut.io.in.AR.ARLEN.poke(len.U(8.W))
      dut.io.in.AR.ARSIZE.poke(2.U(3.W))
      dut.io.in.AR.ARBURST.poke(1.U(2.W))
      dut.io.in.AR.ARPROT.poke(0.U(3.W))
      dut.io.in.AR.ARCACHE.poke(0.U(4.W))
      dut.io.in.AR.ARQOS.poke(0.U(4.W))

      if (bool(dut.io.plicArValid)) { plicArSeen = true }
      if (bool(dut.io.in.R.RVALID)) {
        beats += ReadBeat(
          dut.io.in.R.RRESP.peek().litValue.toInt,
          dut.io.in.R.RDATA.peek().litValue,
          bool(dut.io.in.R.RLAST)
        )
      }

      val arReady = bool(dut.io.in.AR.ARREADY)
      dut.clock.step()
      if (arValid && arReady) { arSent = true }
      cycles += 1
    }

    dut.io.in.AR.ARVALID.poke(false.B)
    dut.io.in.R.RREADY.poke(false.B)
    ReadResult(beats.toSeq, plicArSeen, cycles)
  }

  "Xbar/PLIC" should "把 PLIC 多拍写本地判错并排空全部 W beat" in {
    simulate(new ysyx_26030103_AuditXbarPlicHarness()) { dut =>
      resetDut(dut)
      // PLIC priority 寄存器地址，AWLEN=1 表示两拍写。
      val result = writeBurst(
        dut,
        BigInt("0c000004", 16),
        1,
        Seq(BigInt("11111111", 16), BigInt("22222222", 16))
      )
      result.resp should be(3) // DECERR
      result.plicAwSeen should be(false)
      result.cycles should be < 100
    }
  }

  it should "把 PLIC 多拍读本地展开为 DECERR，而不是交给 PLIC" in {
    simulate(new ysyx_26030103_AuditXbarPlicHarness()) { dut =>
      resetDut(dut)
      val result = readBurst(dut, BigInt("0c200004", 16), 1)
      result.plicArSeen should be(false)
      result.beats.length should be(2)
      all(result.beats.map(_.resp)) should be(3)
      result.beats.map(_.last) should be(Seq(false, true))
    }
  }

  it should "仍然支持合法的 PLIC 单拍读写" in {
    simulate(new ysyx_26030103_AuditXbarPlicHarness()) { dut =>
      resetDut(dut)
      val priority = writeBurst(
        dut,
        BigInt("0c000004", 16),
        0,
        Seq(BigInt(7))
      )
      priority.resp should be(0)
      priority.plicAwSeen should be(true)

      val enable = writeBurst(
        dut,
        BigInt("0c002000", 16),
        0,
        Seq(BigInt(2))
      )
      enable.resp should be(0)

      val threshold = writeBurst(
        dut,
        BigInt("0c200000", 16),
        0,
        Seq(BigInt(0))
      )
      threshold.resp should be(0)

      dut.io.sources.poke(2.U)
      dut.clock.step()
      bool(dut.io.irq) should be(true)

      val claim = readBurst(dut, BigInt("0c200004", 16), 0)
      claim.plicArSeen should be(true)
      claim.beats.length should be(1)
      claim.beats.head.resp should be(0)
      claim.beats.head.data should be(BigInt(1))
      claim.beats.head.last should be(true)
    }
  }
}
