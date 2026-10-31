package ysyx_26030103.audit

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.ysyx_26030103.infra.ysyx_26030103_PMP
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** PMP 审计：多匹配项优先级与 NAPOT 全 1 边界。
  *
  * RISC-V PMP 规定最低编号的匹配项优先；当前实现用 Mux1H 对多个匹配
  * 项的权限做 OR，低编号 deny + 高编号 allow 会放行。NAPOT 的
  * trailingOnes 在 pmpaddr 全 1 时返回 0，可能把全地址空间入口退化成
  * 8 字节入口。当前默认 HAS_PMP=n，这两个问题是“打开 PMP 才会触发”的
  * 潜伏缺陷；本测试直接例化 PMP 叶子模块来确认逻辑本身。
  */
class ysyx_26030103_PMPAuditTest extends AnyFlatSpec with Matchers {
  private def bool(v: Bool): Boolean = v.peek().litValue == 1

  private def setup(dut: ysyx_26030103_PMP): Unit = {
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
    for (i <- 0 until 16) {
      dut.io.cfg(i).poke(0.U)
      dut.io.addr(i).poke(0.U)
    }
    dut.io.fetchAddr.poke(0.U)
    dut.io.fetchPriv.poke(3.U)
    dut.io.dataAddr.poke(0.U)
    dut.io.dataLastAddr.poke(0.U)
    dut.io.dataPriv.poke(1.U) // S 模式
    dut.io.dataRead.poke(false.B)
    dut.io.dataWrite.poke(false.B)
  }

  private def napot(cfg: Int, base: Long, sizeBytes: Long): (Int, Long) = {
    // pmpaddr = (base >> 2) | (size/8 - 1)
    val encoded = (base >> 2) | (sizeBytes / 8 - 1)
    (cfg, encoded)
  }

  "PMP" should "让最低编号的匹配项优先，而不是 OR 所有匹配项" in {
    simulate(new ysyx_26030103_PMP(16)) { dut =>
      setup(dut)
      // entry0 与 entry1 都匹配 0x80000000..0x80000fff。
      // entry0 只设置 A=NAPOT，R/W/X 全 0（拒绝）；entry1 允许 R/W/X。
      val (cfg0, addr0) = napot(0x18, 0x80000000L, 0x1000L)
      val (cfg1, addr1) = napot(0x1f, 0x80000000L, 0x1000L)
      dut.io.cfg(0).poke(cfg0.U)
      dut.io.addr(0).poke(addr0.U)
      dut.io.cfg(1).poke(cfg1.U)
      dut.io.addr(1).poke(addr1.U)
      dut.io.dataAddr.poke("h80000000".U)
      dut.io.dataLastAddr.poke("h80000000".U)
      dut.io.dataPriv.poke(1.U)
      dut.io.dataRead.poke(true.B)
      // 最低编号 entry0 拒绝读，因此应 fault；Mux1H 会 OR 成 allow。
      bool(dut.io.dataFault) should be(true)
    }
  }

  it should "把 pmpaddr 全 1 的 NAPOT 入口当作全地址空间入口" in {
    simulate(new ysyx_26030103_PMP(16)) { dut =>
      setup(dut)
      // 常见的 OpenSBI 全内存 PMP：NAPOT + R/W/X，pmpaddr 全 1。
      dut.io.cfg(0).poke("h1f".U)
      dut.io.addr(0).poke("h3fffffff".U)
      dut.io.dataAddr.poke("h80000000".U)
      dut.io.dataLastAddr.poke("h80000000".U)
      dut.io.dataPriv.poke(1.U)
      dut.io.dataRead.poke(true.B)
      bool(dut.io.dataFault) should be(false)
    }
  }

  it should "保留 4KiB NAPOT 作为对照" in {
    simulate(new ysyx_26030103_PMP(16)) { dut =>
      setup(dut)
      // 4KiB NAPOT：0x80000000..0x80000fff，R/W/X。
      dut.io.cfg(0).poke("h1f".U)
      dut.io.addr(0).poke("h200001ff".U)
      dut.io.dataAddr.poke("h80000000".U)
      dut.io.dataLastAddr.poke("h80000000".U)
      dut.io.dataPriv.poke(1.U)
      dut.io.dataRead.poke(true.B)
      bool(dut.io.dataFault) should be(false)
    }
  }
}
