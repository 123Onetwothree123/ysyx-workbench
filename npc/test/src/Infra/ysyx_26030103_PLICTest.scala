package ysyx_26030103.infra

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** PLIC 的 AXI 寄存器/claim 行为测试。 */
class ysyx_26030103_PLICTest extends AnyFlatSpec with Matchers {
  private def bool(v: Bool): Boolean = v.peek().litValue == 1
  private def long(v: UInt): Long = v.peek().litValue.toLong

  private def setup(dut: ysyx_26030103_PLIC): Unit = {
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
    dut.sources.poke(0.U)
    dut.io.AW.AWVALID.poke(false.B)
    dut.io.AW.AWADDR.poke(0.U)
    dut.io.AW.AWID.poke(0.U)
    dut.io.W.WVALID.poke(false.B)
    dut.io.W.WDATA.poke(0.U)
    dut.io.W.WSTRB.poke(0.U)
    dut.io.W.WLAST.poke(false.B)
    dut.io.B.BREADY.poke(false.B)
    dut.io.AR.ARVALID.poke(false.B)
    dut.io.AR.ARADDR.poke(0.U)
    dut.io.AR.ARID.poke(0.U)
    dut.io.R.RREADY.poke(false.B)
  }

  private def write(dut: ysyx_26030103_PLIC, addr: Long, data: BigInt): Unit = {
    dut.io.AW.AWVALID.poke(true.B)
    dut.io.AW.AWADDR.poke(addr.U)
    dut.io.W.WVALID.poke(true.B)
    dut.io.W.WDATA.poke(data.U(32.W))
    dut.io.W.WSTRB.poke("hf".U)
    dut.io.W.WLAST.poke(true.B)
    dut.clock.step()
    dut.io.AW.AWVALID.poke(false.B)
    dut.io.W.WVALID.poke(false.B)
    while (!bool(dut.io.B.BVALID)) { dut.clock.step() }
    dut.io.B.BREADY.poke(true.B)
    dut.clock.step()
    dut.io.B.BREADY.poke(false.B)
  }

  private def read(dut: ysyx_26030103_PLIC, addr: Long): BigInt = {
    dut.io.AR.ARVALID.poke(true.B)
    dut.io.AR.ARADDR.poke(addr.U)
    dut.clock.step()
    dut.io.AR.ARVALID.poke(false.B)
    while (!bool(dut.io.R.RVALID)) { dut.clock.step() }
    val data = dut.io.R.RDATA.peek().litValue
    dut.io.R.RREADY.poke(true.B)
    dut.clock.step()
    dut.io.R.RREADY.poke(false.B)
    data
  }

  "PLIC" should "支持优先级、pending、claim 和清 pending" in {
    simulate(new ysyx_26030103_PLIC()) { dut =>
      setup(dut)
      // source 1 优先级 7，enable 第 1 位，threshold 0。
      write(dut, 0x0c000004L, 7)
      write(dut, 0x0c002000L, 2)
      write(dut, 0x0c200000L, 0)
      dut.sources.poke(2.U)
      dut.clock.step()
      bool(dut.external_irq) should be(true)
      (read(dut, 0x0c001000L) & 3) should be(BigInt(2))
      dut.sources.poke(0.U)
      dut.clock.step()
      read(dut, 0x0c200004L) should be(BigInt(2))
      (read(dut, 0x0c001000L) & 3) should be(BigInt(0))
    }
  }

  it should "threshold 能屏蔽低优先级中断" in {
    simulate(new ysyx_26030103_PLIC()) { dut =>
      setup(dut)
      write(dut, 0x0c000004L, 3)
      write(dut, 0x0c002000L, 2)
      write(dut, 0x0c200000L, 4)
      dut.sources.poke(2.U)
      dut.clock.step()
      bool(dut.external_irq) should be(false)
    }
  }
}
