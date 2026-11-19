package ysyx_26030103.audit

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.ysyx_26030103.infra._
import _root_.ysyx_26030103.riscv32e_npc_AXIRAM
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** PLIC/CLINT/UART 未被既有定向测试覆盖的组合。 */
class ysyx_26030103_InfraComboAuditTest
    extends AnyFlatSpec with Matchers {
  private def bool(value: Bool): Boolean = value.peek().litValue == 1
  private def long(value: UInt): Long = value.peek().litValue.toLong

  private def setupPLIC(dut: ysyx_26030103_PLIC): Unit = {
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

  private def plicWrite(
      dut: ysyx_26030103_PLIC,
      addr: Long,
      data: BigInt
  ): Unit = {
    dut.io.AW.AWVALID.poke(true.B)
    dut.io.AW.AWADDR.poke(addr.U)
    dut.io.AW.AWLEN.poke(0.U)
    dut.io.W.WVALID.poke(true.B)
    dut.io.W.WDATA.poke(data.U(32.W))
    dut.io.W.WSTRB.poke(15.U)
    dut.io.W.WLAST.poke(true.B)
    dut.clock.step()
    dut.io.AW.AWVALID.poke(false.B)
    dut.io.W.WVALID.poke(false.B)
    while (!bool(dut.io.B.BVALID)) { dut.clock.step() }
    dut.io.B.BREADY.poke(true.B)
    dut.clock.step()
    dut.io.B.BREADY.poke(false.B)
  }

  private def plicRead(
      dut: ysyx_26030103_PLIC,
      addr: Long
  ): BigInt = {
    dut.io.AR.ARVALID.poke(true.B)
    dut.io.AR.ARADDR.poke(addr.U)
    dut.io.AR.ARLEN.poke(0.U)
    dut.clock.step()
    dut.io.AR.ARVALID.poke(false.B)
    while (!bool(dut.io.R.RVALID)) { dut.clock.step() }
    val data = dut.io.R.RDATA.peek().litValue
    dut.io.R.RREADY.poke(true.B)
    dut.clock.step()
    dut.io.R.RREADY.poke(false.B)
    data
  }

  "PLIC" should "支持多 source 并发 claim/complete 与 source 31" in {
    simulate(new ysyx_26030103_PLIC(DataWidth = 32)) { dut =>
      setupPLIC(dut)
      plicWrite(dut, 0x0c000008L, 5) // source 2
      plicWrite(dut, 0x0c000028L, 7) // source 10
      plicWrite(dut, 0x0c00007cL, 6) // source 31
      plicWrite(dut, 0x0c002000L, (1 << 2) | (1 << 10) | (1L << 31))
      plicWrite(dut, 0x0c200000L, 0)
      dut.sources.poke(((1 << 2) | (1 << 10) | (1L << 31)).U)
      dut.clock.step()
      bool(dut.ExternalIRQ) should be(true)

      plicRead(dut, 0x0c200004L) should be(BigInt(10))
      plicRead(dut, 0x0c200004L) should be(BigInt(31))
      plicRead(dut, 0x0c200004L) should be(BigInt(2))
      plicRead(dut, 0x0c200004L) should be(BigInt(0))

      // source 10 仍为高，complete 后应重新 pending 并可再次 claim。
      plicWrite(dut, 0x0c200004L, 10)
      dut.clock.step(2)
      bool(dut.ExternalIRQ) should be(true)
      plicRead(dut, 0x0c200004L) should be(BigInt(10))

      // 拉低 source 后 complete 不应再挂起。
      dut.sources.poke((1 << 2) | (1L << 31))
      dut.clock.step(2)
      plicWrite(dut, 0x0c200004L, 10)
      dut.clock.step(2)
      plicRead(dut, 0x0c200004L) should be(BigInt(0))
    }
  }

  private def setupCLINT(dut: ysyx_26030103_AXI5CLINTSlave): Unit = {
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

  private def clintWrite(
      dut: ysyx_26030103_AXI5CLINTSlave,
      addr: Long,
      data: BigInt
  ): Int = {
    dut.io.AW.AWVALID.poke(true.B)
    dut.io.AW.AWADDR.poke(addr.U)
    dut.io.AW.AWLEN.poke(0.U)
    dut.io.W.WVALID.poke(true.B)
    dut.io.W.WDATA.poke(data.U(32.W))
    dut.io.W.WSTRB.poke(15.U)
    dut.io.W.WLAST.poke(true.B)
    dut.clock.step()
    dut.io.AW.AWVALID.poke(false.B)
    dut.io.W.WVALID.poke(false.B)
    while (!bool(dut.io.B.BVALID)) { dut.clock.step() }
    val response = dut.io.B.BRESP.peek().litValue.toInt
    dut.io.B.BREADY.poke(true.B)
    dut.clock.step()
    dut.io.B.BREADY.poke(false.B)
    response
  }

  private def clintRead(
      dut: ysyx_26030103_AXI5CLINTSlave,
      addr: Long
  ): (BigInt, Int) = {
    dut.io.AR.ARVALID.poke(true.B)
    dut.io.AR.ARADDR.poke(addr.U)
    dut.io.AR.ARLEN.poke(0.U)
    dut.clock.step()
    dut.io.AR.ARVALID.poke(false.B)
    while (!bool(dut.io.R.RVALID)) { dut.clock.step() }
    val data = dut.io.R.RDATA.peek().litValue
    val response = dut.io.R.RRESP.peek().litValue.toInt
    dut.io.R.RREADY.poke(true.B)
    dut.clock.step()
    dut.io.R.RREADY.poke(false.B)
    (data, response)
  }

  "CLINT" should "按完整 64 位写入 mtimecmp 的一半后正确触发/撤销 TimerIRQ" in {
    simulate(new ysyx_26030103_AXI5CLINTSlave(DataWidth = 32)) { dut =>
      setupCLINT(dut)
      // 高半写 0，低半写 0 => mtimecmp=0，TimerIRQ 必须拉高。
      clintWrite(dut, 0x02004004L, 0) should be(0)
      clintWrite(dut, 0x02004000L, 0) should be(0)
      dut.clock.step(2)
      bool(dut.io.TimerIRQ) should be(true)
      clintRead(dut, 0x02004000L)._1 should be(BigInt(0))
      clintRead(dut, 0x02004004L)._1 should be(BigInt(0))

      // 只把高半改成 0xffffffff、低半仍 0 => 未来时刻，IRQ 撤销。
      clintWrite(dut, 0x02004004L, BigInt("ffffffff", 16)) should be(0)
      dut.clock.step(2)
      bool(dut.io.TimerIRQ) should be(false)
      clintRead(dut, 0x02004000L)._1 should be(BigInt(0))
      clintRead(dut, 0x02004004L)._1 should be(BigInt("ffffffff", 16))

      // mtime 低/高读都必须返回有效且递增的计数。
      val (low1, r1) = clintRead(dut, 0x0200bff8L)
      val (high1, r2) = clintRead(dut, 0x0200bffcL)
      r1 should be(0)
      r2 should be(0)
      (low1 | (high1 << 32)) should be > BigInt(0)
    }
  }

  private def setupRam(dut: riscv32e_npc_AXIRAM): Unit = {
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
    dut.io.hostWriteValid.poke(false.B)
    dut.io.hostWriteIndex.poke(0.U)
    dut.io.hostWriteData.poke(0.U)
    dut.io.UARTRxValid.poke(false.B)
    dut.io.UARTRxData.poke(0.U)
    dut.io.AXI.AW.AWVALID.poke(false.B)
    dut.io.AXI.AW.AWADDR.poke(0.U)
    dut.io.AXI.AW.AWID.poke(0.U)
    dut.io.AXI.AW.AWLEN.poke(0.U)
    dut.io.AXI.AW.AWSIZE.poke(2.U)
    dut.io.AXI.AW.AWBURST.poke(0.U)
    dut.io.AXI.AW.AWPROT.poke(0.U)
    dut.io.AXI.AW.AWCACHE.poke(0.U)
    dut.io.AXI.AW.AWQOS.poke(0.U)
    dut.io.AXI.W.WVALID.poke(false.B)
    dut.io.AXI.W.WDATA.poke(0.U)
    dut.io.AXI.W.WSTRB.poke(0.U)
    dut.io.AXI.W.WLAST.poke(false.B)
    dut.io.AXI.B.BREADY.poke(false.B)
    dut.io.AXI.AR.ARVALID.poke(false.B)
    dut.io.AXI.AR.ARADDR.poke(0.U)
    dut.io.AXI.AR.ARID.poke(0.U)
    dut.io.AXI.AR.ARLEN.poke(0.U)
    dut.io.AXI.AR.ARSIZE.poke(2.U)
    dut.io.AXI.AR.ARBURST.poke(0.U)
    dut.io.AXI.AR.ARPROT.poke(0.U)
    dut.io.AXI.AR.ARCACHE.poke(0.U)
    dut.io.AXI.AR.ARQOS.poke(0.U)
    dut.io.AXI.R.RREADY.poke(false.B)
  }

  private def uartRead(
      dut: riscv32e_npc_AXIRAM,
      addr: Long,
      size: Int
  ): (BigInt, Int) = {
    dut.io.AXI.AR.ARVALID.poke(true.B)
    dut.io.AXI.AR.ARADDR.poke(addr.U)
    dut.io.AXI.AR.ARLEN.poke(0.U)
    dut.io.AXI.AR.ARSIZE.poke(size.U)
    dut.io.AXI.AR.ARBURST.poke(0.U)
    dut.io.AXI.R.RREADY.poke(true.B)
    dut.clock.step()
    dut.io.AXI.AR.ARVALID.poke(false.B)
    while (!bool(dut.io.AXI.R.RVALID)) { dut.clock.step() }
    val data = dut.io.AXI.R.RDATA.peek().litValue
    val response = dut.io.AXI.R.RRESP.peek().litValue.toInt
    dut.clock.step()
    dut.io.AXI.R.RREADY.poke(false.B)
    (data, response)
  }

  private def injectUart(
      dut: riscv32e_npc_AXIRAM,
      bytes: Seq[Int]
  ): Unit = {
    for (b <- bytes) {
      var guard = 0
      while (!bool(dut.io.UARTRxReady) && guard < 100) {
        dut.clock.step(); guard += 1
      }
      dut.io.UARTRxValid.poke(true.B)
      dut.io.UARTRxData.poke(b.U)
      dut.clock.step()
      dut.io.UARTRxValid.poke(false.B)
    }
    dut.clock.step()
  }

  "UART AXIRAM" should "多字节 RX FIFO 保持顺序并正确维护 DataReady" in {
    simulate(new riscv32e_npc_AXIRAM(4096)) { dut =>
      setupRam(dut)
      injectUart(dut, Seq(0x11, 0x22, 0x33, 0x44))
      val (lsr, lsrResponse) = uartRead(dut, 0x10000005L, 0)
      lsrResponse should be(0)
      (lsr & 1) should be(BigInt(1))
      for (expected <- Seq(0x11, 0x22, 0x33, 0x44)) {
        val (data, response) = uartRead(dut, 0x10000000L, 0)
        response should be(0)
        (data & 0xff) should be(BigInt(expected))
      }
      val (lsrEmpty, _) = uartRead(dut, 0x10000005L, 0)
      (lsrEmpty & 1) should be(BigInt(0))
    }
  }

  it should "RX FIFO 满后注入额外字节置 LSR overrun 且不破坏旧数据" in {
    simulate(new riscv32e_npc_AXIRAM(4096)) { dut =>
      setupRam(dut)
      injectUart(dut, (0 until 64).map(i => (i + 1) & 0xff))
      // FIFO 已满，仍注入一字节：rxReady 为低，但 overrun 必须置位。
      dut.io.UARTRxValid.poke(true.B)
      dut.io.UARTRxData.poke(0xee.U)
      dut.clock.step()
      dut.io.UARTRxValid.poke(false.B)
      dut.clock.step()
      val (lsr, _) = uartRead(dut, 0x10000005L, 0)
      ((lsr >> 1) & 1) should be(BigInt(1))
      val (first, _) = uartRead(dut, 0x10000000L, 0)
      (first & 0xff) should be(BigInt(1))
    }
  }
}
