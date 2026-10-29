package ysyx_26030103.infra

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** CLINT 叶子测试：mtimecmp 的高低 32 位字都必须按 WSTRB 做字节合并，
  * 且只接受 mtimecmp 的精确字地址。
  */
class ysyx_26030103_CLINTWStrbAuditTest extends AnyFlatSpec with Matchers {
  private def bool(value: Bool): Boolean = value.peek().litValue == 1

  private def setup(dut: ysyx_26030103_AXI5CLINTSlave): Unit = {
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
    dut.io.AW.AWVALID.poke(false.B)
    dut.io.AW.AWADDR.poke(0.U)
    dut.io.AW.AWID.poke(0.U)
    dut.io.AW.AWLEN.poke(0.U)
    dut.io.AW.AWSIZE.poke(2.U)
    dut.io.AW.AWBURST.poke(0.U)
    dut.io.W.WVALID.poke(false.B)
    dut.io.W.WDATA.poke(0.U)
    dut.io.W.WSTRB.poke(0.U)
    dut.io.W.WLAST.poke(false.B)
    dut.io.B.BREADY.poke(false.B)
    dut.io.AR.ARVALID.poke(false.B)
    dut.io.AR.ARADDR.poke(0.U)
    dut.io.AR.ARID.poke(0.U)
    dut.io.AR.ARLEN.poke(0.U)
    dut.io.AR.ARSIZE.poke(2.U)
    dut.io.AR.ARBURST.poke(0.U)
    dut.io.R.RREADY.poke(false.B)
  }

  private def write(
      dut: ysyx_26030103_AXI5CLINTSlave,
      addr: Long,
      data: BigInt,
      strb: BigInt
  ): Int = {
    dut.io.AW.AWVALID.poke(true.B)
    dut.io.AW.AWADDR.poke(addr.U)
    dut.io.AW.AWID.poke(0.U)
    dut.io.AW.AWLEN.poke(0.U)
    dut.io.AW.AWSIZE.poke(2.U)
    dut.io.AW.AWBURST.poke(1.U)
    dut.io.W.WVALID.poke(true.B)
    dut.io.W.WDATA.poke(data.U(32.W))
    dut.io.W.WSTRB.poke(strb.U(4.W))
    dut.io.W.WLAST.poke(true.B)
    dut.clock.step()
    dut.io.AW.AWVALID.poke(false.B)
    dut.io.W.WVALID.poke(false.B)
    while (!bool(dut.io.B.BVALID)) { dut.clock.step() }
    val resp = dut.io.B.BRESP.peek().litValue.toInt
    dut.io.B.BREADY.poke(true.B)
    dut.clock.step()
    dut.io.B.BREADY.poke(false.B)
    resp
  }

  private def read(
      dut: ysyx_26030103_AXI5CLINTSlave,
      addr: Long
  ): (BigInt, Int) = {
    dut.io.AR.ARVALID.poke(true.B)
    dut.io.AR.ARADDR.poke(addr.U)
    dut.io.AR.ARID.poke(0.U)
    dut.io.AR.ARLEN.poke(0.U)
    dut.io.AR.ARSIZE.poke(2.U)
    dut.io.AR.ARBURST.poke(1.U)
    dut.clock.step()
    dut.io.AR.ARVALID.poke(false.B)
    while (!bool(dut.io.R.RVALID)) { dut.clock.step() }
    val data = dut.io.R.RDATA.peek().litValue
    val resp = dut.io.R.RRESP.peek().litValue.toInt
    dut.io.R.RREADY.poke(true.B)
    dut.clock.step()
    dut.io.R.RREADY.poke(false.B)
    (data, resp)
  }

  "CLINT mtimecmp" should "分别按 WSTRB 合并低字和高字" in {
    simulate(new ysyx_26030103_AXI5CLINTSlave()) { dut =>
      setup(dut)

      write(dut, 0x02004000L, BigInt("12345678", 16), 15) should be(0)
      write(dut, 0x02004000L, BigInt("aa", 16), 1) should be(0)
      read(dut, 0x02004000L)._1 should be(BigInt("123456aa", 16))

      write(dut, 0x02004004L, BigInt("11223344", 16), 15) should be(0)
      write(dut, 0x02004004L, BigInt("bb", 16), 1) should be(0)
      read(dut, 0x02004004L)._1 should be(BigInt("112233bb", 16))
      read(dut, 0x02004000L)._1 should be(BigInt("123456aa", 16))
    }
  }

  it should "拒绝非精确 mtimecmp 地址且不破坏原值" in {
    simulate(new ysyx_26030103_AXI5CLINTSlave()) { dut =>
      setup(dut)

      write(dut, 0x02004000L, BigInt("12345678", 16), 15) should be(0)
      // 窄写落在 mtimecmp 低字内部但地址不是精确字地址，必须返回 SLVERR。
      write(dut, 0x02004001L, BigInt("aa", 16), 2) should be(2)
      read(dut, 0x02004000L)._1 should be(BigInt("12345678", 16))
    }
  }
}
