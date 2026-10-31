package ysyx_26030103.audit

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.ysyx_26030103.config._
import _root_.ysyx_26030103.idu.ysyx_26030103_IDUPrivilegeHarness
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** 最小 ISA/CSR 合法性探针：misa.M、RV64 高半计数器、sstatus.SXL、M-only
  * cycle。只检查规范期望，不修改 RTL；失败即表示对应差异存在。
  */
class ysyx_26030103_ISALegalProbeAuditTest
    extends AnyFlatSpec with Matchers {

  private def csrProfile: ysyx_26030103_MMUProfile =
    ysyx_26030103_MMUProfile(
      enabled = false,
      xlen = 64,
      pageSizeBytes = 4096,
      levels = 3,
      pteSizeBytes = 8,
      pageTableSizeBytes = 4096,
      pteFormat = "riscv64",
      pteConfig = ysyx_26030103_PteConfig(pteBits = 64, ppnHigh = 53),
      hasS = true,
      hasU = true
    )

  private def csrSetup(dut: ysyx_26030103_CSRAuditHarness): Unit = {
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
    dut.io.enable.poke(false.B)
    dut.io.retireCommit.poke(false.B)
    dut.io.isCsrrw.poke(false.B)
    dut.io.isCsrrs.poke(false.B)
    dut.io.isCsrrc.poke(false.B)
    dut.io.isEcall.poke(false.B)
    dut.io.isEbreak.poke(false.B)
    dut.io.isMret.poke(false.B)
    dut.io.isSret.poke(false.B)
    dut.io.isSfenceVma.poke(false.B)
    dut.io.csrAddr.poke(0.U)
    dut.io.rs1.poke(0.U)
    dut.io.rs1Data.poke(0.U)
    dut.io.pc.poke(0.U)
    dut.io.trapTval.poke(0.U)
    dut.io.trapTvalValid.poke(false.B)
    dut.io.trapValid.poke(false.B)
    dut.io.trapCause.poke(0.U)
    dut.io.memTrap.poke(false.B)
    dut.io.interrupt.poke(false.B)
    dut.io.timerInterrupt.poke(false.B)
    dut.io.mtime.poke(0.U)
  }

  private def peekCsr(
      dut: ysyx_26030103_CSRAuditHarness,
      addr: Int
  ): BigInt = {
    dut.io.csrAddr.poke(addr.U)
    dut.io.enable.poke(false.B)
    dut.io.rdata.peek().litValue
  }

  private def iduConfig(
      hasS: Boolean,
      hasU: Boolean
  ): ysyx_26030103_NPCConfig =
    ysyx_26030103_NPCConfig(
      XLEN = 64,
      AddressWidth = 64,
      UseM = true,
      UseA = true,
      UseC = false,
      UseF = false,
      UseD = false,
      MMU = ysyx_26030103_MMUProfile(
        enabled = false,
        xlen = 64,
        pageSizeBytes = 4096,
        levels = 3,
        pteSizeBytes = 8,
        pageTableSizeBytes = 4096,
        pteFormat = "riscv64",
        pteConfig = ysyx_26030103_PteConfig(pteBits = 64, ppnHigh = 53),
        hasS = hasS,
        hasU = hasU,
        csrEnabled = Set(
          0x300, 0x301, 0xb00, 0xb02, 0xb80, 0xb82,
          0xc00, 0xc01, 0xc02, 0xc80, 0xc81, 0xc82
        )
      )
    )

  private def csrrs(addr: Int): Long =
    ((addr.toLong << 20) | (2L << 12) | (1L << 7) | 0x73L)

  private def iduSetup(dut: ysyx_26030103_IDUPrivilegeHarness): Unit = {
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
    dut.io.mstatus.poke(0.U)
    dut.io.instruction.poke(0.U)
    dut.io.privilege.poke(3.U)
  }

  "misa" should "在 RISCV_M=y 的 RV64 核上置位 M(bit12)" in {
    // RISCV_M=y 的核在 CSR 构造时传入 UseM=true，misa 必须置 M(bit12)。
    simulate(new ysyx_26030103_CSRAuditHarness(csrProfile, useM = true)) { dut =>
      csrSetup(dut)
      val misa = peekCsr(dut, 0x301)
      ((misa >> 12) & 1) should be(BigInt(1))
    }
  }

  "IDU" should "在 RV64 拒绝 mcycleh/minstreth/cycleh/timeh/instreth" in {
    simulate(new ysyx_26030103_IDUPrivilegeHarness(
      iduConfig(hasS = true, hasU = true)
    )) { dut =>
      iduSetup(dut)
      // 0xb80/0xb82 是 M 级高半字，0xc80/0xc81/0xc82 是用户高半字；
      // RV64 中都不存在，访问应产生非法指令 cause=2。
      val observed = Seq(0xb80, 0xb82, 0xc80, 0xc81, 0xc82).map { addr =>
        dut.io.instruction.poke(csrrs(addr).U)
        (addr, dut.io.exceptionValid.peek().litValue,
          dut.io.exceptionCause.peek().litValue)
      }
      observed should be(Seq(
        (0xb80, BigInt(1), BigInt(2)),
        (0xb82, BigInt(1), BigInt(2)),
        (0xc80, BigInt(1), BigInt(2)),
        (0xc81, BigInt(1), BigInt(2)),
        (0xc82, BigInt(1), BigInt(2))
      ))
    }
  }

  "sstatus" should "在 RV64 只暴露 UXL=2，不额外暴露 SXL" in {
    simulate(new ysyx_26030103_CSRAuditHarness(csrProfile)) { dut =>
      csrSetup(dut)
      val sstatus = peekCsr(dut, 0x100)
      ((sstatus >> 32) & 0xf) should be(BigInt(2))
    }
  }

  "IDU" should "允许 M-only 核在 M 模式读 cycle(0xc00)" in {
    simulate(new ysyx_26030103_IDUPrivilegeHarness(
      iduConfig(hasS = false, hasU = false)
    )) { dut =>
      iduSetup(dut)
      dut.io.instruction.poke(csrrs(0xc00).U)
      dut.io.exceptionValid.peek().litValue should be(BigInt(0))
    }
  }
}
