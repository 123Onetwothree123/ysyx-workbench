package ysyx_26030103.audit

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.ysyx_26030103.config._
import _root_.ysyx_26030103.exu.ysyx_26030103_CSR
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** CSR 功能/退休审计：计数器别名、RV64 sstatus 视图、ecall/中断 mtval、
  * minstret 退休计数、M 级中断全局使能与 PMP 配置位。
  *
  * 这些用例是按 RISC-V 规范写的“期望行为”测试；如果当前 RTL 的实现与
  * 规范不一致，测试会失败，从而把差异定位到一个具体 CSR/字段。
  */
class ysyx_26030103_CSRAuditHarness(
    profile: ysyx_26030103_MMUProfile,
    useM: Boolean = false
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
    val stateTval = Output(UInt(64.W))
    val trapCommit = Output(Bool())
    val committedCause = Output(UInt(32.W))
    val privilege = Output(UInt(2.W))
    val stateMstatus = Output(UInt(64.W))
  })
  val CSR = Module(new ysyx_26030103_CSR(
    profile, XLEN = 64, UseF = true, UseD = true, UseM = useM))
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
  io.stateTval := CSR.io.StateTval
  io.trapCommit := CSR.io.TrapCommit
  io.committedCause := CSR.io.CommittedCause
  io.privilege := CSR.io.Privilege
  io.stateMstatus := CSR.io.StateMstatus
}

class ysyx_26030103_CSRFunctionalAuditTest
    extends AnyFlatSpec with Matchers {

  private val MSTATUS = 0x300
  private val MTVEC = 0x305
  private val MEPC = 0x341
  private val MCAUSE = 0x342
  private val MTVAL = 0x343
  private val SSTATUS = 0x100
  private val CYCLE = 0xc00
  private val TIME = 0xc01
  private val INSTRET = 0xc02
  private val MCYCLE = 0xb00
  private val MINSTRET = 0xb02
  private val MIE = 0x304
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

  private def setup(dut: ysyx_26030103_CSRAuditHarness): Unit = {
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

  /** 组合读 CSR，不推进时钟，保证多次读取看到同一拍的寄存器值。 */
  private def peekCSR(
      dut: ysyx_26030103_CSRAuditHarness,
      address: Int
  ): BigInt = {
    dut.io.CSRAddress.poke(address.U)
    dut.io.enable.poke(false.B)
    dut.io.rdata.peek().litValue
  }

  private def stepEnable(
      dut: ysyx_26030103_CSRAuditHarness,
      cycles: Int
  ): Unit = {
    // 旧测试用 enable 代表“退休了一拍”；新语义下 minstret 只跟随 WBU
    // 退休事件，因此这里同时驱动 retireCommit。
    dut.io.enable.poke(true.B)
    dut.io.retireCommit.poke(true.B)
    for (_ <- 0 until cycles) { dut.clock.step() }
    dut.io.enable.poke(false.B)
    dut.io.retireCommit.poke(false.B)
  }

  private def writeCSR(
      dut: ysyx_26030103_CSRAuditHarness,
      address: Int,
      data: BigInt
  ): Unit = {
    dut.io.CSRAddress.poke(address.U)
    dut.io.rs1.poke(1.U)
    dut.io.rs1Data.poke(data.U(64.W))
    dut.io.enable.poke(true.B)
    dut.io.isCSRrw.poke(true.B)
    dut.clock.step()
    dut.io.enable.poke(false.B)
    dut.io.isCSRrw.poke(false.B)
  }

  it should "把 rdcycle 映射到 mcycle" in {
    simulate(new ysyx_26030103_CSRAuditHarness(profile)) { dut =>
      setup(dut)
      dut.clock.step(8)
      val mcycle = peekCSR(dut, MCYCLE)
      val cycle = peekCSR(dut, CYCLE)
      cycle should be(mcycle)
      cycle should not be BigInt(0)
    }
  }

  it should "把 rdtime 映射到 CLINT mtime" in {
    simulate(new ysyx_26030103_CSRAuditHarness(profile)) { dut =>
      setup(dut)
      dut.io.mtime.poke(BigInt("123456789abcdef0", 16).U(64.W))
      peekCSR(dut, TIME) should be(BigInt("123456789abcdef0", 16))
    }
  }

  it should "把 rdinstret 映射到 minstret" in {
    simulate(new ysyx_26030103_CSRAuditHarness(profile)) { dut =>
      setup(dut)
      stepEnable(dut, 4)
      val minstret = peekCSR(dut, MINSTRET)
      val instret = peekCSR(dut, INSTRET)
      instret should be(minstret)
      instret should not be BigInt(0)
    }
  }

  it should "在 RV64 的 sstatus 中只保留 UXL=2" in {
    simulate(new ysyx_26030103_CSRAuditHarness(profile)) { dut =>
      setup(dut)
      val mstatus = peekCSR(dut, MSTATUS)
      val sstatus = peekCSR(dut, SSTATUS)
      // mstatus 是参照：CSR.scala 的 MstatusArchValue 会补 SXL/UXL。
      ((mstatus >> 32) & 0xf) should be(BigInt(0xa))
      // sstatus 只包含 UXL[33:32]；SXL 仅存在于 mstatus。
      ((sstatus >> 32) & 0xf) should be(BigInt(0x2))
    }
  }

  it should "在 ecall 时把 mtval 写成 0，而不是故障 PC" in {
    simulate(new ysyx_26030103_CSRAuditHarness(profile)) { dut =>
      setup(dut)
      val PC = BigInt("0000000080001234", 16)
      dut.io.PC.poke(PC.U(64.W))
      dut.io.trapTvalValid.poke(false.B)
      dut.io.enable.poke(true.B)
      dut.io.isEcall.poke(true.B)
      dut.io.trapCommit.peek().litToBoolean should be(true)
      dut.io.committedCause.peek().litValue should be(BigInt(11))
      dut.clock.step()
      dut.io.enable.poke(false.B)
      dut.io.isEcall.poke(false.B)
      // RISC-V 对 ecall 要求 mtval=0；当前实现会回退到 io.PC。
      peekCSR(dut, MTVAL) should be(BigInt(0))
      // 对照：mepc 仍应精确记录 ecall 指令的 PC。
      peekCSR(dut, MEPC) should be(PC)
    }
  }

  it should "不因纯中断提交自增 minstret" in {
    val IRQProfile = profile.copy(HasSExternalIRQ = false)
    simulate(new ysyx_26030103_CSRAuditHarness(IRQProfile)) { dut =>
      setup(dut)
      writeCSR(dut, MSTATUS, BigInt(0x8))
      writeCSR(dut, 0x304, BigInt(0x800))
      writeCSR(dut, MTVEC, BigInt("0000000080002000", 16))
      val before = peekCSR(dut, MINSTRET)
      dut.io.PC.poke(BigInt("0000000080001000", 16).U(64.W))
      dut.io.enable.poke(true.B)
      dut.io.interrupt.poke(true.B)
      dut.io.trapCommit.peek().litToBoolean should be(true)
      dut.clock.step()
      dut.io.enable.poke(false.B)
      dut.io.interrupt.poke(false.B)
      // 中断没有退休指令，minstret 不能增加。
      peekCSR(dut, MINSTRET) should be(before)
    }
  }

  it should "支持 RV64 minstret 写入且写入拍不再自增" in {
    simulate(new ysyx_26030103_CSRAuditHarness(profile)) { dut =>
      setup(dut)
      writeCSR(dut, MINSTRET, BigInt(0x1234))
      peekCSR(dut, MINSTRET) should be(BigInt(0x1234))
      stepEnable(dut, 1)
      peekCSR(dut, MINSTRET) should be(BigInt(0x1235))
    }
  }

  it should "在中断提交时把 mtval 写成 0" in {
    // 默认 profile 的外部中断走 S 级；这里裁剪 hasSExternalIrq，让
    // io.interrupt 直接作为 M 级外部中断，在 M 模式验证中断 tval。
    val IRQProfile = profile.copy(HasSExternalIRQ = false)
    simulate(new ysyx_26030103_CSRAuditHarness(IRQProfile)) { dut =>
      setup(dut)
      writeCSR(dut, MSTATUS, BigInt(0x8)) // mstatus.MIE
      writeCSR(dut, 0x304, BigInt(0x800)) // mie.MEIE
      writeCSR(dut, MTVEC, BigInt("0000000080002000", 16))
      // 预置非零 mtval，确保下面的 0 来自 trap 写入而不是复位值。
      writeCSR(dut, MTVAL, BigInt("deadbeef", 16))
      val PC = BigInt("0000000080001000", 16)
      dut.io.PC.poke(PC.U(64.W))
      dut.io.enable.poke(true.B)
      dut.io.interrupt.poke(true.B)
      dut.io.trapCommit.peek().litToBoolean should be(true)
      dut.io.committedCause.peek().litValue should be(BigInt("8000000b", 16))
      // StateTval 在 trap 提交拍就应给出最终写入的 0。
      dut.io.stateTval.peek().litValue should be(BigInt(0))
      dut.clock.step()
      dut.io.enable.poke(false.B)
      dut.io.interrupt.poke(false.B)
      // 中断没有 tval，mtval 必须为 0；mepc 仍记录被打断的 PC。
      peekCSR(dut, MTVAL) should be(BigInt(0))
      peekCSR(dut, MEPC) should be(PC)
    }
  }

  "minstret" should "只跟随退休事件，而不是 EXU Enable" in {
    simulate(new ysyx_26030103_CSRAuditHarness(profile)) { dut =>
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

  "PMP config" should "保留 L 和 A[1] 位并清除保留位" in {
    val PMPProfile = profile.copy(PMPEnabled = true)
    simulate(new ysyx_26030103_CSRAuditHarness(PMPProfile)) { dut =>
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
    simulate(new ysyx_26030103_CSRAuditHarness(PMPProfile)) { dut =>
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
