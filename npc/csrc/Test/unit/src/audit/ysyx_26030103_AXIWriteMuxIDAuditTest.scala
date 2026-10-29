package ysyx_26030103.infra

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** AXIWriteMux 的 AWID 必须随选中的源透传，不能永远输出 0。 */
class ysyx_26030103_AXIWriteMuxIDAuditTest extends AnyFlatSpec with Matchers {
  private def bool(value: Bool): Boolean = value.peek().litValue == 1

  private def setup(dut: ysyx_26030103_AXIWriteMux): Unit = {
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)

    dut.io.a.AW.AWVALID.poke(false.B)
    dut.io.a.AW.AWADDR.poke(0.U)
    dut.io.a.AW.AWID.poke(0.U)
    dut.io.a.AW.AWLEN.poke(0.U)
    dut.io.a.AW.AWSIZE.poke(2.U)
    dut.io.a.AW.AWBURST.poke(0.U)
    dut.io.a.AW.AWPROT.poke(0.U)
    dut.io.a.AW.AWCACHE.poke(0.U)
    dut.io.a.AW.AWQOS.poke(0.U)
    dut.io.a.W.WVALID.poke(false.B)
    dut.io.a.W.WDATA.poke(0.U)
    dut.io.a.W.WSTRB.poke(0.U)
    dut.io.a.W.WLAST.poke(false.B)
    dut.io.a.B.BREADY.poke(false.B)
    dut.io.a.AR.ARVALID.poke(false.B)
    dut.io.a.AR.ARADDR.poke(0.U)
    dut.io.a.AR.ARID.poke(0.U)
    dut.io.a.AR.ARLEN.poke(0.U)
    dut.io.a.AR.ARSIZE.poke(2.U)
    dut.io.a.AR.ARBURST.poke(0.U)
    dut.io.a.AR.ARPROT.poke(0.U)
    dut.io.a.AR.ARCACHE.poke(0.U)
    dut.io.a.AR.ARQOS.poke(0.U)
    dut.io.a.R.RREADY.poke(false.B)

    dut.io.b.AW.AWVALID.poke(false.B)
    dut.io.b.AW.AWADDR.poke(0.U)
    dut.io.b.AW.AWID.poke(0.U)
    dut.io.b.AW.AWLEN.poke(0.U)
    dut.io.b.AW.AWSIZE.poke(2.U)
    dut.io.b.AW.AWBURST.poke(0.U)
    dut.io.b.AW.AWPROT.poke(0.U)
    dut.io.b.AW.AWCACHE.poke(0.U)
    dut.io.b.AW.AWQOS.poke(0.U)
    dut.io.b.W.WVALID.poke(false.B)
    dut.io.b.W.WDATA.poke(0.U)
    dut.io.b.W.WSTRB.poke(0.U)
    dut.io.b.W.WLAST.poke(false.B)
    dut.io.b.B.BREADY.poke(false.B)
    dut.io.b.AR.ARVALID.poke(false.B)
    dut.io.b.AR.ARADDR.poke(0.U)
    dut.io.b.AR.ARID.poke(0.U)
    dut.io.b.AR.ARLEN.poke(0.U)
    dut.io.b.AR.ARSIZE.poke(2.U)
    dut.io.b.AR.ARBURST.poke(0.U)
    dut.io.b.AR.ARPROT.poke(0.U)
    dut.io.b.AR.ARCACHE.poke(0.U)
    dut.io.b.AR.ARQOS.poke(0.U)
    dut.io.b.R.RREADY.poke(false.B)

    dut.io.out.AW.AWREADY.poke(false.B)
    dut.io.out.W.WREADY.poke(false.B)
    dut.io.out.B.BVALID.poke(false.B)
    dut.io.out.B.BID.poke(0.U)
    dut.io.out.B.BRESP.poke(0.U)
    dut.io.out.AR.ARREADY.poke(false.B)
    dut.io.out.R.RVALID.poke(false.B)
    dut.io.out.R.RID.poke(0.U)
    dut.io.out.R.RDATA.poke(0.U)
    dut.io.out.R.RRESP.poke(0.U)
    dut.io.out.R.RLAST.poke(false.B)
  }

  private def checkAwId(useA: Boolean, id: Int): Unit = {
    simulate(new ysyx_26030103_AXIWriteMux()) { dut =>
      setup(dut)
      if (useA) {
        dut.io.a.AW.AWVALID.poke(true.B)
        dut.io.a.AW.AWID.poke(id.U(4.W))
        dut.io.a.AW.AWADDR.poke(0x1000.U)
        dut.io.a.W.WVALID.poke(true.B)
        dut.io.a.W.WDATA.poke("hdeadbeef".U)
        dut.io.a.W.WSTRB.poke("hf".U)
        dut.io.a.W.WLAST.poke(true.B)
      } else {
        dut.io.b.AW.AWVALID.poke(true.B)
        dut.io.b.AW.AWID.poke(id.U(4.W))
        dut.io.b.AW.AWADDR.poke(0x2000.U)
        dut.io.b.W.WVALID.poke(true.B)
        dut.io.b.W.WDATA.poke("hcafebabe".U)
        dut.io.b.W.WSTRB.poke("hf".U)
        dut.io.b.W.WLAST.poke(true.B)
      }
      dut.io.out.AW.AWREADY.poke(true.B)
      dut.io.out.W.WREADY.poke(true.B)

      var cycles = 0
      while (!bool(dut.io.out.AW.AWVALID) && cycles < 10) {
        dut.clock.step()
        cycles += 1
      }
      bool(dut.io.out.AW.AWVALID) should be(true)
      dut.io.out.AW.AWID.peek().litValue should be(BigInt(id))
    }
  }

  "AXIWriteMux" should "透传 A 源的 AWID" in {
    checkAwId(useA = true, id = 5)
  }

  it should "透传 B 源的 AWID" in {
    checkAwId(useA = false, id = 9)
  }
}
