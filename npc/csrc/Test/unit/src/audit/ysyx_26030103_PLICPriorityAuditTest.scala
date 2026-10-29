package ysyx_26030103.infra

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** PLIC 叶子测试：claim 必须按优先级仲裁，claimed 必须屏蔽 gateway 重入，
  * 以及 enable/priority/threshold 的部分写必须按 WSTRB 合并。
  */
class ysyx_26030103_PLICPriorityAuditTest extends AnyFlatSpec with Matchers {
  private def bool(value: Bool): Boolean = value.peek().litValue == 1

  private def setup(dut: ysyx_26030103_PLIC): Unit = {
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
    dut.sources.poke(0.U)
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
      dut: ysyx_26030103_PLIC,
      addr: Long,
      data: BigInt,
      strb: BigInt = 15
  ): Unit = {
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
    dut.io.B.BREADY.poke(true.B)
    dut.clock.step()
    dut.io.B.BREADY.poke(false.B)
  }

  private def read(dut: ysyx_26030103_PLIC, addr: Long): BigInt = {
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
    dut.io.R.RREADY.poke(true.B)
    dut.clock.step()
    dut.io.R.RREADY.poke(false.B)
    data
  }

  "PLIC claim" should "选择最高优先级 source，而不是最低编号" in {
    simulate(new ysyx_26030103_PLIC()) { dut =>
      setup(dut)
      write(dut, 0x0c000004L, 1) // source 1 priority=1
      write(dut, 0x0c000028L, 7) // source 10 priority=7
      write(dut, 0x0c002000L, (1 << 1) | (1 << 10))
      write(dut, 0x0c200000L, 0) // threshold=0
      dut.sources.poke(((1 << 1) | (1 << 10)).U)
      dut.clock.step()
      bool(dut.external_irq) should be(true)
      read(dut, 0x0c200004L) should be(BigInt(10))
    }
  }

  it should "同优先级时选择最低 source ID" in {
    simulate(new ysyx_26030103_PLIC()) { dut =>
      setup(dut)
      write(dut, 0x0c000004L, 5)
      write(dut, 0x0c000008L, 5)
      write(dut, 0x0c002000L, (1 << 1) | (1 << 2))
      write(dut, 0x0c200000L, 0)
      dut.sources.poke(((1 << 1) | (1 << 2)).U)
      dut.clock.step()
      read(dut, 0x0c200004L) should be(BigInt(1))
    }
  }

  it should "claim 后屏蔽仍为高电平的 source，complete 后重新 pending" in {
    simulate(new ysyx_26030103_PLIC()) { dut =>
      setup(dut)
      write(dut, 0x0c000004L, 7) // source 1 priority=7
      write(dut, 0x0c002000L, 2)
      write(dut, 0x0c200000L, 0)
      dut.sources.poke(2.U)
      dut.clock.step()
      bool(dut.external_irq) should be(true)

      read(dut, 0x0c200004L) should be(BigInt(1))
      // source 电平仍为高，但 claimed 必须屏蔽 gateway。
      bool(dut.external_irq) should be(false)
      read(dut, 0x0c200004L) should be(BigInt(0))

      write(dut, 0x0c200004L, 1) // complete source 1
      dut.clock.step(2)
      bool(dut.external_irq) should be(true)
      read(dut, 0x0c200004L) should be(BigInt(1))
    }
  }

  "PLIC WSTRB" should "按字节合并 enable/priority/threshold 部分写" in {
    simulate(new ysyx_26030103_PLIC()) { dut =>
      setup(dut)

      write(dut, 0x0c002000L, BigInt("12345678", 16), 15)
      write(dut, 0x0c002000L, BigInt("aa", 16), 1)
      read(dut, 0x0c002000L) should be(BigInt("123456aa", 16))

      write(dut, 0x0c000004L, 7, 15)
      write(dut, 0x0c000004L, 0, 8)
      read(dut, 0x0c000004L) should be(BigInt(7))

      write(dut, 0x0c200000L, 7, 15)
      write(dut, 0x0c200000L, 0, 8)
      read(dut, 0x0c200000L) should be(BigInt(7))
    }
  }
}
