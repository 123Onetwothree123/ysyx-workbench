package ysyx_26030103.audit

import _root_.ysyx_26030103.riscv32e_npc_AXIRAM

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** direct-NPC UART 窗口测试：只有偏移 0..7 是实际寄存器，0x08..0x1f
  * 不能因地址截断别名到 RBR 并产生弹 FIFO 副作用。
  */
class ysyx_26030103_UARTWindowAuditTest extends AnyFlatSpec with Matchers {
  private def bool(value: Bool): Boolean = value.peek().litValue == 1

  private def setup(dut: riscv32e_npc_AXIRAM): Unit = {
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

  private def read(
      dut: riscv32e_npc_AXIRAM,
      addr: Long,
      size: Int
  ): (BigInt, Int) = {
    dut.io.AXI.AR.ARVALID.poke(true.B)
    dut.io.AXI.AR.ARADDR.poke(addr.U)
    dut.io.AXI.AR.ARID.poke(0.U)
    dut.io.AXI.AR.ARLEN.poke(0.U)
    dut.io.AXI.AR.ARSIZE.poke(size.U)
    dut.io.AXI.AR.ARBURST.poke(0.U)
    dut.io.AXI.AR.ARPROT.poke(0.U)
    dut.io.AXI.AR.ARCACHE.poke(0.U)
    dut.io.AXI.AR.ARQOS.poke(0.U)
    dut.io.AXI.R.RREADY.poke(true.B)

    var cycles = 0
    while (!bool(dut.io.AXI.R.RVALID) && cycles < 100) {
      dut.clock.step()
      cycles += 1
    }
    bool(dut.io.AXI.R.RVALID) should be(true)
    val data = dut.io.AXI.R.RDATA.peek().litValue
    val response = dut.io.AXI.R.RRESP.peek().litValue.toInt
    dut.io.AXI.AR.ARVALID.poke(false.B)
    dut.clock.step()
    dut.io.AXI.R.RREADY.poke(false.B)
    (data, response)
  }

  "UART window" should "拒绝 0x10000008 别名读且不弹出 RX FIFO" in {
    simulate(new riscv32e_npc_AXIRAM(4096)) { dut =>
      setup(dut)

      dut.io.UARTRxValid.poke(true.B)
      dut.io.UARTRxData.poke("h5a".U)
      dut.clock.step()
      dut.io.UARTRxValid.poke(false.B)
      dut.clock.step()

      // 0x08 在 16550 寄存器窗口之外，不能别名成 RBR 并触发 FIFO 弹出。
      val (_, badResponse) = read(dut, 0x10000008L, 2)
      badResponse should be(3) // DECERR

      val (lsr, lsrResponse) = read(dut, 0x10000005L, 0)
      lsrResponse should be(0)
      (lsr & 1) should be(BigInt(1))

      val (rbr, rbrResponse) = read(dut, 0x10000000L, 0)
      rbrResponse should be(0)
      // UART 读数据会复制到四个字节通道，这里检查 LSU 实际选中的低字节。
      (rbr & 0xff) should be(BigInt("5a", 16))

      val (lsrAfter, _) = read(dut, 0x10000005L, 0)
      (lsrAfter & 1) should be(BigInt(0))
    }
  }
}
