package ysyx_26030103.audit

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.ysyx_26030103.config._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** 自写小探针：medeleg 的 WARL 保留位。
  *
  * RISC-V Privileged Spec 要求 medeleg 中不可委托的异常的对应位、
  * 保留位以及 medeleg[11] / medeleg[16] 读回为 0。
  * 当前 RTL 的写路径是 `Medeleg := Write32(Medeleg)`，没有应用
  * 任何可写位掩码，因此软件写入这些位后会原样读回。
  */
class AgentCsrMedelegWarlTest extends AnyFlatSpec with Matchers {
  private val MEDELEG = 0x302

  private def bool(value: Bool): Boolean = value.peek().litValue == 1

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

  private def setup(dut: ysyx_26030103_CSRAuditHarness): Unit = {
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

  private def writeCsr(
      dut: ysyx_26030103_CSRAuditHarness,
      addr: Int,
      value: BigInt
  ): Unit = {
    dut.io.enable.poke(true.B)
    dut.io.isCsrrw.poke(true.B)
    dut.io.csrAddr.poke(addr.U(12.W))
    dut.io.rs1.poke(1.U)
    dut.io.rs1Data.poke(value.U(64.W))
    dut.clock.step()
    dut.io.enable.poke(false.B)
    dut.io.isCsrrw.poke(false.B)
    dut.io.rs1.poke(0.U)
    dut.io.rs1Data.poke(0.U)
    dut.clock.step()
  }

  private def readCsr(
      dut: ysyx_26030103_CSRAuditHarness,
      addr: Int
  ): BigInt = {
    dut.io.csrAddr.poke(addr.U(12.W))
    dut.io.enable.poke(false.B)
    dut.io.rdata.peek().litValue
  }

  "medeleg" should "把不可委托/保留位读回为零" in {
    simulate(new ysyx_26030103_CSRAuditHarness(profile)) { dut =>
      setup(dut)

      // 合法的 S 模式可委托异常：illegal(2)、load access(5)、
      // ecall U(8)、ecall S(9)。
      val legal = BigInt(1) << 2 | BigInt(1) << 5 |
        BigInt(1) << 8 | BigInt(1) << 9
      // 必须忽略：保留 10/14、ecall M 11、double trap 16、保留 31。
      val illegal = BigInt(1) << 10 | BigInt(1) << 11 |
        BigInt(1) << 14 | BigInt(1) << 16 | BigInt(1) << 31

      writeCsr(dut, MEDELEG, legal)
      readCsr(dut, MEDELEG) should be(legal)

      writeCsr(dut, MEDELEG, legal | illegal)
      val observed = readCsr(dut, MEDELEG)

      withClue(
        s"写 medeleg=0x${(legal | illegal).toString(16)} 后读回 " +
          s"0x${observed.toString(16)}；期望只保留合法位 " +
          s"0x${legal.toString(16)}"
      ) {
        observed should be(legal)
      }
    }
  }
}
