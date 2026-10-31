package ysyx_26030103.audit

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.ysyx_26030103.config._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** CSR 中断优先级回归。
  *
  * RISC-V 特权规范要求：同时 pending 时机器级中断按 MSI(3) 先于
  * MTI(7) 处理；任何低于 M 的特权级都必须先处理目标为 M 的中断，
  * 再处理已委托给 S 的中断；sstatus.SIE 只控制 S 模式自身能否响应
  * S 级中断，U 模式不受它屏蔽。
  *
  * 当前 CSR 实现在 pendingCause 中先查 bit7 再查 bit3，并且 IrqCause
  * 在 SIntTaken 有效时直接选择 S 级 cause；SIntTaken 又错误地要求
  * sstatus.SIE=1。以下三个用例分别覆盖这三点。
  */
class ysyx_26030103_CSRInterruptPriorityRegressionTest
    extends AnyFlatSpec with Matchers {
  private val MSTATUS = 0x300
  private val MIDELEG = 0x303
  private val MIE = 0x304
  private val MTVEC = 0x305
  private val MEPC = 0x341
  private val SSTATUS = 0x100
  private val STVEC = 0x105
  private val MIP = 0x344

  private def profile: ysyx_26030103_MMUProfile =
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

  private def bool(value: Bool): Boolean = value.peek().litValue == 1
  private def big(value: UInt): BigInt = value.peek().litValue

  private def setup(dut: ysyx_26030103_CSRExceptionAuditHarness): Unit = {
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

  private def write(
      dut: ysyx_26030103_CSRExceptionAuditHarness,
      addr: Int,
      data: BigInt
  ): Unit = {
    dut.io.csrAddr.poke(addr.U)
    dut.io.rs1.poke(1.U)
    dut.io.rs1Data.poke(data.U(64.W))
    dut.io.enable.poke(true.B)
    dut.io.isCsrrw.poke(true.B)
    dut.clock.step()
    dut.io.enable.poke(false.B)
    dut.io.isCsrrw.poke(false.B)
  }

  private def peek(
      dut: ysyx_26030103_CSRExceptionAuditHarness,
      addr: Int
  ): BigInt = {
    dut.io.csrAddr.poke(addr.U)
    dut.io.enable.poke(false.B)
    dut.io.rdata.peek().litValue
  }

  private def mretTo(
      dut: ysyx_26030103_CSRExceptionAuditHarness,
      expectedTarget: BigInt,
      expectedPrivilege: BigInt
  ): Unit = {
    dut.io.enable.poke(true.B)
    dut.io.isMret.poke(true.B)
    bool(dut.io.exceptionTaken) should be(true)
    big(dut.io.exceptionTarget) should be(expectedTarget)
    dut.clock.step()
    dut.io.isMret.poke(false.B)
    dut.io.enable.poke(false.B)
    big(dut.io.privilege) should be(expectedPrivilege)
  }

  "CSR interrupt priority" should
    "M 级 MSI 与 MTI 同时 pending 时先报 MSI(cause=3)" in {
      simulate(new ysyx_26030103_CSRExceptionAuditHarness(profile)) { dut =>
        setup(dut)
        val mtvec = BigInt("0000000080002000", 16)
        write(dut, MTVEC, mtvec)
        write(dut, MSTATUS, BigInt(0x8)) // mstatus.MIE
        write(dut, MIE, BigInt(0x88))    // MSIE(3) + MTIE(7)
        write(dut, MIP, BigInt(0x88))    // MSIP(3) + MTIP(7)

        val pc = BigInt("0000000080001000", 16)
        dut.io.pc.poke(pc.U(64.W))
        dut.io.enable.poke(true.B)

        // 规范优先级：MEI > MSI > MTI。MSI 必须先于 MTI 上报。
        withClue("MSI/MTI 同时 pending 时 cause 应为 0x80000003: ") {
          bool(dut.io.trapCommit) should be(true)
          big(dut.io.committedCause) should be(BigInt("80000003", 16))
          big(dut.io.exceptionTarget) should be(mtvec)
        }
      }
    }

  it should "S 模式下未委托的 M timer 优先于已委托的 S external" in {
    simulate(new ysyx_26030103_CSRExceptionAuditHarness(profile)) { dut =>
      setup(dut)
      val mtvec = BigInt("0000000080002000", 16)
      val stvec = BigInt("0000000080003000", 16)
      val mepc = BigInt("0000000080001000", 16)
      write(dut, MTVEC, mtvec)
      write(dut, STVEC, stvec)
      write(dut, MIDELEG, BigInt(0x200)) // SEIP 委托给 S
      write(dut, MIE, BigInt(0x280))     // MTIE(7) + SEIE(9)

      // MPP=S、sstatus.SIE=1，但 mstatus.MIE=0。若在 M 模式且 MIE=1
      // 时让 MTIP 保持 pending，当前实现会在 MRET 当拍先接受中断而
      // 无法完成返回 S；这里先用 MIE=0 完成 MRET，再检查 S 模式下
      // M 中断不受 mstatus.MIE 影响（S 模式仍必须响应目标为 M 的
      // 中断）。这不改变要验证的“M 中断优先于已委托 S 中断”语义。
      write(dut, MSTATUS, BigInt(0x802))
      write(dut, MEPC, mepc)
      write(dut, MIP, BigInt(0x280)) // MTIP(7) + SEIP(9) 同时 pending
      mretTo(dut, mepc, BigInt(1))

      ((peek(dut, SSTATUS) >> 1) & 1) should be(BigInt(1))

      val pc = BigInt("0000000080001010", 16)
      dut.io.pc.poke(pc.U(64.W))
      dut.io.enable.poke(true.B)

      // M timer 未被委托，且 S 模式必须接受 M 级中断；不能因为
      // 同时存在已委托的 S external 就改走 stvec。
      withClue("M timer 与已委托 S external 同时 pending 时应先走 M: ") {
        bool(dut.io.trapCommit) should be(true)
        big(dut.io.committedCause) should be(BigInt("80000007", 16))
        big(dut.io.exceptionTarget) should be(mtvec)
      }
    }
  }

  it should "U 模式下 sstatus.SIE=0 不能屏蔽 S 级中断" in {
    simulate(new ysyx_26030103_CSRExceptionAuditHarness(profile)) { dut =>
      setup(dut)
      val stvec = BigInt("0000000080003000", 16)
      val mepc = BigInt("0000000080004000", 16)
      write(dut, MTVEC, BigInt("0000000080002000", 16))
      write(dut, STVEC, stvec)
      write(dut, MIDELEG, BigInt(0x200))
      write(dut, MIE, BigInt(0x200))
      write(dut, MIP, BigInt(0x200)) // SEIP pending，已委托给 S
      write(dut, MSTATUS, BigInt(0)) // MPP=U，sstatus.SIE=0
      write(dut, MEPC, mepc)
      mretTo(dut, mepc, BigInt(0))

      big(dut.io.privilege) should be(BigInt(0))
      ((peek(dut, SSTATUS) >> 1) & 1) should be(BigInt(0))

      val pc = BigInt("0000000080004010", 16)
      dut.io.pc.poke(pc.U(64.W))
      dut.io.enable.poke(true.B)

      // sstatus.SIE 只限制 S 模式；U 模式下 S 级中断始终使能。
      withClue("U 模式下 S external 不应被 sstatus.SIE=0 屏蔽: ") {
        bool(dut.io.trapCommit) should be(true)
        big(dut.io.committedCause) should be(BigInt("80000009", 16))
        big(dut.io.exceptionTarget) should be(stvec)
      }
    }
  }
}
