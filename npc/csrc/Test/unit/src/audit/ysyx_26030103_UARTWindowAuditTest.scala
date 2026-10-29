package ysyx_26030103

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
    dut.io.uartRxValid.poke(false.B)
    dut.io.uartRxData.poke(0.U)

    dut.io.axi.AW.AWVALID.poke(false.B)
    dut.io.axi.AW.AWADDR.poke(0.U)
    dut.io.axi.AW.AWID.poke(0.U)
    dut.io.axi.AW.AWLEN.poke(0.U)
    dut.io.axi.AW.AWSIZE.poke(2.U)
    dut.io.axi.AW.AWBURST.poke(0.U)
    dut.io.axi.AW.AWPROT.poke(0.U)
    dut.io.axi.AW.AWCACHE.poke(0.U)
    dut.io.axi.AW.AWQOS.poke(0.U)
    dut.io.axi.W.WVALID.poke(false.B)
    dut.io.axi.W.WDATA.poke(0.U)
    dut.io.axi.W.WSTRB.poke(0.U)
    dut.io.axi.W.WLAST.poke(false.B)
    dut.io.axi.B.BREADY.poke(false.B)
    dut.io.axi.AR.ARVALID.poke(false.B)
    dut.io.axi.AR.ARADDR.poke(0.U)
    dut.io.axi.AR.ARID.poke(0.U)
    dut.io.axi.AR.ARLEN.poke(0.U)
    dut.io.axi.AR.ARSIZE.poke(2.U)
    dut.io.axi.AR.ARBURST.poke(0.U)
    dut.io.axi.AR.ARPROT.poke(0.U)
    dut.io.axi.AR.ARCACHE.poke(0.U)
    dut.io.axi.AR.ARQOS.poke(0.U)
    dut.io.axi.R.RREADY.poke(false.B)
  }

  private def read(
      dut: riscv32e_npc_AXIRAM,
      addr: Long,
      size: Int
  ): (BigInt, Int) = {
    dut.io.axi.AR.ARVALID.poke(true.B)
    dut.io.axi.AR.ARADDR.poke(addr.U)
    dut.io.axi.AR.ARID.poke(0.U)
    dut.io.axi.AR.ARLEN.poke(0.U)
    dut.io.axi.AR.ARSIZE.poke(size.U)
    dut.io.axi.AR.ARBURST.poke(0.U)
    dut.io.axi.AR.ARPROT.poke(0.U)
    dut.io.axi.AR.ARCACHE.poke(0.U)
    dut.io.axi.AR.ARQOS.poke(0.U)
    dut.io.axi.R.RREADY.poke(true.B)

    var cycles = 0
    while (!bool(dut.io.axi.R.RVALID) && cycles < 100) {
      dut.clock.step()
      cycles += 1
    }
    bool(dut.io.axi.R.RVALID) should be(true)
    val data = dut.io.axi.R.RDATA.peek().litValue
    val resp = dut.io.axi.R.RRESP.peek().litValue.toInt
    dut.io.axi.AR.ARVALID.poke(false.B)
    dut.clock.step()
    dut.io.axi.R.RREADY.poke(false.B)
    (data, resp)
  }

  "UART window" should "拒绝 0x10000008 别名读且不弹出 RX FIFO" in {
    simulate(new riscv32e_npc_AXIRAM(4096)) { dut =>
      setup(dut)

      dut.io.uartRxValid.poke(true.B)
      dut.io.uartRxData.poke("h5a".U)
      dut.clock.step()
      dut.io.uartRxValid.poke(false.B)
      dut.clock.step()

      // 0x08 在 16550 寄存器窗口之外，不能别名成 RBR 并触发 FIFO 弹出。
      val (_, badResp) = read(dut, 0x10000008L, 2)
      badResp should be(3) // DECERR

      val (lsr, lsrResp) = read(dut, 0x10000005L, 0)
      lsrResp should be(0)
      (lsr & 1) should be(BigInt(1))

      val (rbr, rbrResp) = read(dut, 0x10000000L, 0)
      rbrResp should be(0)
      // UART 读数据会复制到四个字节通道，这里检查 LSU 实际选中的低字节。
      (rbr & 0xff) should be(BigInt("5a", 16))

      val (lsrAfter, _) = read(dut, 0x10000005L, 0)
      (lsrAfter & 1) should be(BigInt(0))
    }
  }
}
