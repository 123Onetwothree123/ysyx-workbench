package ysyx_26030103.infra

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.ysyx_26030103.config.ysyx_26030103_MMUProfile
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** PMP 的 NAPOT/TOR、特权级和权限测试。 */
class ysyx_26030103_PMPTest extends AnyFlatSpec with Matchers {
  private def bool(v: Bool): Boolean = v.peek().litValue == 1

  "MMUProfile" should "允许 HAS_PMP=y 且 HAS_MMU=n" in {
    val profile = ysyx_26030103_MMUProfile(
      enabled = false,
      pmpEnabled = true,
      pmpEntries = 16
    )
    profile.pmpEnabled should be(true)
    profile.enabled should be(false)
  }

  "PMP" should "对 S/U 默认拒绝并按 NAPOT 放行" in {
    simulate(new ysyx_26030103_PMP(4)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      for (i <- 0 until 16) {
        dut.io.cfg(i).poke(0.U)
        dut.io.addr(i).poke(0.U)
      }
      // 4KiB NAPOT：0x80000000..0x80000fff，R/W/X，无 L。
      dut.io.cfg(0).poke("h1f".U)
      dut.io.addr(0).poke("h200001ff".U)
      dut.io.fetchAddr.poke("h80000000".U)
      dut.io.fetchPriv.poke(1.U)
      dut.io.dataAddr.poke("h80000004".U)
      dut.io.dataLastAddr.poke("h80000004".U)
      dut.io.dataPriv.poke(1.U)
      dut.io.dataRead.poke(true.B)
      dut.io.dataWrite.poke(false.B)
      bool(dut.io.fetchFault) should be(false)
      bool(dut.io.dataFault) should be(false)
      dut.io.dataWrite.poke(true.B)
      bool(dut.io.dataFault) should be(false)

      dut.io.dataAddr.poke("h80001000".U)
      dut.io.dataLastAddr.poke("h80001000".U)
      bool(dut.io.dataFault) should be(true)
      dut.io.dataPriv.poke(3.U)
      bool(dut.io.dataFault) should be(false)
    }
  }

  it should "锁定的只读项对 M 模式也生效" in {
    simulate(new ysyx_26030103_PMP(4)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      for (i <- 0 until 16) {
        dut.io.cfg(i).poke(0.U)
        dut.io.addr(i).poke(0.U)
      }
      // 4KiB NAPOT，R/X + L，A=NAPOT。
      dut.io.cfg(0).poke("h9d".U)
      dut.io.addr(0).poke("h200001ff".U)
      dut.io.dataAddr.poke("h80000000".U)
      dut.io.dataLastAddr.poke("h80000000".U)
      dut.io.dataPriv.poke(3.U)
      dut.io.dataRead.poke(false.B)
      dut.io.dataWrite.poke(true.B)
      bool(dut.io.dataFault) should be(true)
      dut.io.dataWrite.poke(false.B)
      dut.io.dataRead.poke(true.B)
      bool(dut.io.dataFault) should be(false)
    }
  }
}
