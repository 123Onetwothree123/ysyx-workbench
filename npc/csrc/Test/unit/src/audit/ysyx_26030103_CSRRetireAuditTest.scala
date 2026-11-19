package ysyx_26030103.audit

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.ysyx_26030103.config._
import _root_.ysyx_26030103.exu.ysyx_26030103_CSR
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** CSR 退休计数、M 级中断全局使能与 PMP 配置位审计。
  *
  * 这些用例都直接例化 CSR，按架构语义驱动 RetireCommit 与过滤后的
  * MinstretRetireCommit 退休事件。
  */
class ysyx_26030103_CSRRetireAuditHarness(
    profile: ysyx_26030103_MMUProfile
) extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())
    val retireCommit = Input(Bool())
    val isCSRrw = Input(Bool())
    val isCSRrs = Input(Bool())
    val isCSRrc = Input(Bool())
    val isEcall = Input(Bool())
    val isEbreak = Input(Bool())
    val isMret = Input(Bool())
    val isSret = Input(Bool())
    val isSfenceVma = Input(Bool())
    val CSRAddress = Input(UInt(12.W))
    val rs1 = Input(UInt(5.W))
    val rs1Data = Input(UInt(64.W))
    val PC = Input(UInt(64.W))
    val trapTval = Input(UInt(64.W))
    val trapTvalValid = Input(Bool())
    val trapValid = Input(Bool())
    val trapCause = Input(UInt(32.W))
    val memTrap = Input(Bool())
    val interrupt = Input(Bool())
    val timerInterrupt = Input(Bool())
    val mtime = Input(UInt(64.W))
    val rdata = Output(UInt(64.W))
    val trapCommit = Output(Bool())
    val committedCause = Output(UInt(32.W))
    val privilege = Output(UInt(2.W))
  })
  val CSR = Module(new ysyx_26030103_CSR(
    profile, XLEN = 64, UseF = true, UseD = true))
  CSR.io.clock := clock
  CSR.io.reset := reset
  CSR.io.Enable := io.enable
  CSR.io.MinstretRetireCommit := io.retireCommit
  CSR.io.IsCSRrw := io.isCSRrw
  CSR.io.IsCSRrs := io.isCSRrs
  CSR.io.IsCSRrc := io.isCSRrc
  CSR.io.IsEcall := io.isEcall
  CSR.io.IsEbreak := io.isEbreak
  CSR.io.IsMret := io.isMret
  CSR.io.IsSret := io.isSret
  CSR.io.IsSfenceVma := io.isSfenceVma
  CSR.io.CSRAddress := io.CSRAddress
  CSR.io.rs1 := io.rs1
  CSR.io.Rs1Data := io.rs1Data
  CSR.io.PC := io.PC
  CSR.io.TrapTval := io.trapTval
  CSR.io.TrapTvalValid := io.trapTvalValid
  CSR.io.TrapValid := io.trapValid
  CSR.io.TrapCause := io.trapCause
  CSR.io.MemTrap := io.memTrap
  CSR.io.Interrupt := io.interrupt
  CSR.io.TimerInterrupt := io.timerInterrupt
  CSR.io.Mtime := io.mtime
  CSR.io.FPCommit := false.B
  CSR.io.FPFlags := 0.U
  CSR.io.FPStateDirtyCommit := false.B
  CSR.io.PMUEvents := 0.U
  io.rdata := CSR.io.CSR_rdata
  io.trapCommit := CSR.io.TrapCommit
  io.committedCause := CSR.io.CommittedCause
  io.privilege := CSR.io.Privilege
}

class ysyx_26030103_CSRRetireAuditTest
    extends AnyFlatSpec with Matchers {
  private val MSTATUS = 0x300
  private val MIE = 0x304
  private val MEPC = 0x341
  private val MINSTRET = 0xb02
  private val PMPCFG0 = 0x3a0
  private val PMPADDR0 = 0x3b0

  private def profile: ysyx_26030103_MMUProfile =
    ysyx_26030103_MMUProfile(
      enabled = false,
      xlen = 64,
      pageSizeBytes = 4096,
      levels = 3,
      PTESizeBytes = 8,
      pageTableSizeBytes = 4096,
      PTEFormat = "riscv64",
      PTEConfig = ysyx_26030103_PTEConfig(PTEBits = 64, PPNHigh = 53),
      hasS = true,
      hasU = true
    )

  private def bool(v: Bool): Boolean = v.peek().litValue == 1
  private def big(v: UInt): BigInt = v.peek().litValue

  private def setup(dut: ysyx_26030103_CSRRetireAuditHarness): Unit = {
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
    dut.io.enable.poke(false.B)
    dut.io.retireCommit.poke(false.B)
    dut.io.isCSRrw.poke(false.B)
    dut.io.isCSRrs.poke(false.B)
    dut.io.isCSRrc.poke(false.B)
    dut.io.isEcall.poke(false.B)
    dut.io.isEbreak.poke(false.B)
    dut.io.isMret.poke(false.B)
    dut.io.isSret.poke(false.B)
    dut.io.isSfenceVma.poke(false.B)
    dut.io.CSRAddress.poke(0.U)
    dut.io.rs1.poke(0.U)
    dut.io.rs1Data.poke(0.U)
    dut.io.PC.poke(0.U)
    dut.io.trapTval.poke(0.U)
    dut.io.trapTvalValid.poke(false.B)
    dut.io.trapValid.poke(false.B)
    dut.io.trapCause.poke(0.U)
    dut.io.memTrap.poke(false.B)
    dut.io.interrupt.poke(false.B)
    dut.io.timerInterrupt.poke(false.B)
    dut.io.mtime.poke(0.U)
  }

  private def peekCSR(
      dut: ysyx_26030103_CSRRetireAuditHarness,
      addr: Int
  ): BigInt = {
    dut.io.CSRAddress.poke(addr.U)
    dut.io.rdata.peek().litValue
  }

  private def writeCSR(
      dut: ysyx_26030103_CSRRetireAuditHarness,
      addr: Int,
      data: BigInt
  ): Unit = {
    dut.io.CSRAddress.poke(addr.U)
    dut.io.rs1.poke(1.U)
    dut.io.rs1Data.poke(data.U(64.W))
    dut.io.enable.poke(true.B)
    dut.io.isCSRrw.poke(true.B)
    dut.clock.step()
    dut.io.enable.poke(false.B)
    dut.io.isCSRrw.poke(false.B)
  }

  "minstret" should "只跟随退休事件，而不是 EXU Enable" in {
    simulate(new ysyx_26030103_CSRRetireAuditHarness(profile)) { dut =>
      setup(dut)
      peekCSR(dut, MINSTRET) should be(BigInt(0))

      // EXU 提交拍（enable）没有指令退休时不得计数。
      dut.io.enable.poke(true.B)
      dut.io.retireCommit.poke(false.B)
      for (_ <- 0 until 4) { dut.clock.step() }
      dut.io.enable.poke(false.B)
      peekCSR(dut, MINSTRET) should be(BigInt(0))

      // 只有 WBU 退休事件才计数。
      dut.io.retireCommit.poke(true.B)
      for (_ <- 0 until 3) { dut.clock.step() }
      dut.io.retireCommit.poke(false.B)
      peekCSR(dut, MINSTRET) should be(BigInt(3))
    }
  }

  it should "保留 csrw minstret 写口，且之后退休继续自增" in {
    simulate(new ysyx_26030103_CSRRetireAuditHarness(profile)) { dut =>
      setup(dut)
      writeCSR(dut, MINSTRET, BigInt(0x1234))
      peekCSR(dut, MINSTRET) should be(BigInt(0x1234))

      dut.io.retireCommit.poke(true.B)
      dut.clock.step()
      dut.io.retireCommit.poke(false.B)
      peekCSR(dut, MINSTRET) should be(BigInt(0x1235))
    }
  }

  "M-mode interrupt" should "在 S 模式下不要求 mstatus.MIE" in {
    simulate(new ysyx_26030103_CSRRetireAuditHarness(profile)) { dut =>
      setup(dut)
      // 从 M 模式 MRET 到 S：mstatus.MPP=S，MIE/MPIE 均为 0。
      writeCSR(dut, MSTATUS, BigInt(0x800))
      writeCSR(dut, MEPC, BigInt("0000000080001000", 16))
      dut.io.enable.poke(true.B)
      dut.io.isMret.poke(true.B)
      dut.clock.step()
      dut.io.enable.poke(false.B)
      dut.io.isMret.poke(false.B)
      big(dut.io.privilege) should be(BigInt(1))
      ((peekCSR(dut, MSTATUS) >> 3) & 1) should be(BigInt(0))

      // 使能 M 级 timer 中断（mie.MTIE）。S 模式下即使 MIE=0，
      // M 级中断也必须能够抢占。
      writeCSR(dut, MIE, BigInt(0x80))
      dut.io.timerInterrupt.poke(true.B)
      dut.io.PC.poke(BigInt("0000000080002000", 16).U(64.W))
      dut.io.enable.poke(true.B)
      bool(dut.io.trapCommit) should be(true)
      big(dut.io.committedCause) should be(BigInt("80000007", 16))
      dut.clock.step()
      dut.io.enable.poke(false.B)
      dut.io.timerInterrupt.poke(false.B)
    }
  }

  "PMP config" should "保留 L 和 A[1] 位并清除保留位" in {
    val PMPProfile = profile.copy(PMPEnabled = true)
    simulate(new ysyx_26030103_CSRRetireAuditHarness(PMPProfile)) { dut =>
      setup(dut)
      writeCSR(dut, PMPCFG0, BigInt(0x1f))
      peekCSR(dut, PMPCFG0) should be(BigInt(0x1f))

      // bit[6:5] 是保留位，必须写 0；L/A[1:0]/X/W/R 保留。
      writeCSR(dut, PMPCFG0, BigInt(0x7f))
      peekCSR(dut, PMPCFG0) should be(BigInt(0x1f))
    }
  }

  it should "锁定表项忽略后续 cfg/addr 写入" in {
    val PMPProfile = profile.copy(PMPEnabled = true)
    simulate(new ysyx_26030103_CSRRetireAuditHarness(PMPProfile)) { dut =>
      setup(dut)
      // 先写入 pmpaddr0，再锁定 entry0（L=1, A=NAPOT, RWX）。
      writeCSR(dut, PMPADDR0, BigInt("200001ff", 16))
      peekCSR(dut, PMPADDR0) should be(BigInt("200001ff", 16))
      writeCSR(dut, PMPCFG0, BigInt(0x9f))
      peekCSR(dut, PMPCFG0) should be(BigInt(0x9f))
      writeCSR(dut, PMPCFG0, BigInt(0x0f))
      peekCSR(dut, PMPCFG0) should be(BigInt(0x9f))

      writeCSR(dut, PMPADDR0, BigInt("300001ff", 16))
      peekCSR(dut, PMPADDR0) should be(BigInt("200001ff", 16))
    }
  }
}
