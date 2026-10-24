package ysyx_26030103.infra

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import _root_.ysyx_26030103.common._

/** RV64 Xbar 64 位地址定向测试。
  *
  * 高 PMA 区域必须路由到 SoCBus 并保持完整 64 位地址；未命中 PMA 的
  * 高地址请求应在 Xbar 内产生 DECERR，而不是截断后访问低地址设备。
  */
class ysyx_26030103_RV64XbarTest extends AnyFlatSpec with Matchers {
  private val AddrW = 64
  private def bool(value: Bool): Boolean = value.peek().litValue == 1
  private def big(value: UInt): BigInt = value.peek().litValue
  private val HighBase = 0x180000000L
  private val Regions = Seq(
    ysyx_26030103_PMARegion(HighBase, 0x10000L)
  )

  private def resetXbar(
      dut: ysyx_26030103_AXI5Xbar,
      clock: Clock,
      reset: Reset
  ): Unit = {
    reset.poke(true.B)
    clock.step(2)
    reset.poke(false.B)
    dut.io.in.AW.AWVALID.poke(false.B)
    dut.io.in.AW.AWADDR.poke(0.U(AddrW.W))
    dut.io.in.AW.AWID.poke(0.U(4.W))
    dut.io.in.AW.AWLEN.poke(0.U(8.W))
    dut.io.in.AW.AWSIZE.poke(2.U(3.W))
    dut.io.in.AW.AWBURST.poke(0.U(2.W))
    dut.io.in.AW.AWPROT.poke(0.U(3.W))
    dut.io.in.AW.AWCACHE.poke(0.U(4.W))
    dut.io.in.AW.AWQOS.poke(0.U(4.W))
    dut.io.in.W.WVALID.poke(false.B)
    dut.io.in.W.WDATA.poke(0.U(32.W))
    dut.io.in.W.WSTRB.poke(0.U(4.W))
    dut.io.in.W.WLAST.poke(false.B)
    dut.io.in.B.BREADY.poke(false.B)
    dut.io.in.AR.ARVALID.poke(false.B)
    dut.io.in.AR.ARADDR.poke(0.U(AddrW.W))
    dut.io.in.AR.ARID.poke(0.U(4.W))
    dut.io.in.AR.ARLEN.poke(0.U(8.W))
    dut.io.in.AR.ARSIZE.poke(2.U(3.W))
    dut.io.in.AR.ARBURST.poke(0.U(2.W))
    dut.io.in.AR.ARPROT.poke(0.U(3.W))
    dut.io.in.AR.ARCACHE.poke(0.U(4.W))
    dut.io.in.AR.ARQOS.poke(0.U(4.W))
    dut.io.in.R.RREADY.poke(false.B)
    dut.io.SoCBus.AR.ARREADY.poke(false.B)
    dut.io.SoCBus.R.RVALID.poke(false.B)
    dut.io.SoCBus.R.RID.poke(0.U(4.W))
    dut.io.SoCBus.R.RDATA.poke(0.U(32.W))
    dut.io.SoCBus.R.RRESP.poke(0.U(2.W))
    dut.io.SoCBus.R.RLAST.poke(false.B)
    dut.io.CLINT.AR.ARREADY.poke(false.B)
    dut.io.CLINT.R.RVALID.poke(false.B)
    dut.io.CLINT.R.RID.poke(0.U(4.W))
    dut.io.CLINT.R.RDATA.poke(0.U(32.W))
    dut.io.CLINT.R.RRESP.poke(0.U(2.W))
    dut.io.CLINT.R.RLAST.poke(false.B)
    dut.io.PLIC.AR.ARREADY.poke(false.B)
    dut.io.PLIC.R.RVALID.poke(false.B)
    dut.io.PLIC.R.RID.poke(0.U(4.W))
    dut.io.PLIC.R.RDATA.poke(0.U(32.W))
    dut.io.PLIC.R.RRESP.poke(0.U(2.W))
    dut.io.PLIC.R.RLAST.poke(false.B)
  }

  private def issueRead(
      dut: ysyx_26030103_AXI5Xbar,
      address: BigInt
  ): Unit = {
    dut.io.in.AR.ARVALID.poke(true.B)
    dut.io.in.AR.ARADDR.poke(address.U(AddrW.W))
    dut.io.in.AR.ARID.poke(0.U(4.W))
    dut.io.in.AR.ARLEN.poke(0.U(8.W))
    dut.io.in.AR.ARSIZE.poke(2.U(3.W))
    dut.io.in.AR.ARBURST.poke(0.U(2.W))
    dut.io.in.AR.ARPROT.poke(0.U(3.W))
    dut.io.in.AR.ARCACHE.poke(0.U(4.W))
    dut.io.in.AR.ARQOS.poke(0.U(4.W))
  }

  "RV64 Xbar" should "把高于 4GiB 的 PMA 读地址完整转发到 SoCBus" in {
    simulate(
      new ysyx_26030103_AXI5Xbar(
        AddressWidth = AddrW,
        PMARegions = Regions,
        PLICBaseValue = BigInt("0c000000", 16),
        PLICSizeBytes = BigInt("00100000", 16)
      )
    ) { dut =>
      val clock = dut.clock
      resetXbar(dut, clock, dut.reset)
      val Address = BigInt("180001234", 16)
      issueRead(dut, Address)
      var cycles = 0
      while (!bool(dut.io.SoCBus.AR.ARVALID) && cycles < 20) {
        clock.step(); cycles += 1
      }
      bool(dut.io.SoCBus.AR.ARVALID) should be(true)
      big(dut.io.SoCBus.AR.ARADDR) should be(Address)
      dut.io.SoCBus.AR.ARREADY.poke(true.B)
      clock.step()
      dut.io.SoCBus.AR.ARREADY.poke(false.B)
      dut.io.in.AR.ARVALID.poke(false.B)
    }
  }

  it should "对未映射的高地址返回本地 DECERR 且不发往 SoCBus" in {
    simulate(
      new ysyx_26030103_AXI5Xbar(
        AddressWidth = AddrW,
        PMARegions = Regions,
        PLICBaseValue = BigInt("0c000000", 16),
        PLICSizeBytes = BigInt("00100000", 16)
      )
    ) { dut =>
      val clock = dut.clock
      resetXbar(dut, clock, dut.reset)
      issueRead(dut, BigInt("200000000", 16))
      dut.io.SoCBus.AR.ARREADY.poke(true.B)
      dut.io.in.R.RREADY.poke(true.B)
      var sawDeccerr = false
      var cycles = 0
      while (!sawDeccerr && cycles < 40) {
        bool(dut.io.SoCBus.AR.ARVALID) should be(false)
        if (bool(dut.io.in.R.RVALID)) {
          sawDeccerr = true
          dut.io.in.R.RRESP.peek().litValue should be(3)
          dut.io.in.R.RLAST.peek().litValue should be(1)
        }
        clock.step(); cycles += 1
      }
      sawDeccerr should be(true)
    }
  }
}
