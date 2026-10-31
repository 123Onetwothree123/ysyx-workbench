package ysyx_26030103.audit

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.ysyx_26030103.infra.ysyx_26030103_PLIC
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** PLIC claim/threshold 回归：claim 不受 priority threshold 影响。
  *
  * RISC-V PLIC 规范中 threshold 只决定是否向 hart 发出外部中断通知；
  * 只要 source 已 enable、gateway 已挂起且未被 claim，软件读取 claim
  * 寄存器就必须拿到该 source，即使其优先级不高于 threshold。
  *
  * 当前实现把 `priority(i) > threshold` 同时放进了 claimCandidates，
  * 导致 threshold=5、source 1 priority=3 时 external_irq 被正确屏蔽，
  * 但 claim 也错误地返回 0。
  */
class ysyx_26030103_PlicClaimThresholdRegressionTest
    extends AnyFlatSpec with Matchers {
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

  "PLIC claim" should
    "return an enabled pending source even when its priority is below threshold" in {
      simulate(new ysyx_26030103_PLIC()) { dut =>
        setup(dut)
        write(dut, 0x0c000004L, 3) // source 1 priority=3
        write(dut, 0x0c002000L, 1 << 1) // enable source 1
        write(dut, 0x0c200000L, 5) // threshold=5 > priority 3
        dut.sources.poke((1 << 1).U) // source 1 电平
        dut.clock.step(2)

        // threshold 高于 source 优先级：不应向 hart 发外部中断。
        bool(dut.external_irq) should be(false)

        // claim 不受 threshold 影响，规范要求仍返回已 enable 的 source 1。
        withClue(
          "threshold=5 只应屏蔽 external_irq，不应让 claim 返回 0"
        ) {
          read(dut, 0x0c200004L) should be(BigInt(1))
        }
      }
    }
}
