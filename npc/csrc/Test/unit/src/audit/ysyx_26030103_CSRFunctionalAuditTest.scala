package ysyx_26030103.audit

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.ysyx_26030103.config._
import _root_.ysyx_26030103.exu.ysyx_26030103_CSR
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** CSR 功能审计：用户计数器别名、RV64 sstatus 视图、ecall/中断 mtval。
  *
  * 这些用例是按 RISC-V 规范写的“期望行为”测试；如果当前 RTL 的实现与
  * 规范不一致，测试会失败，从而把差异定位到一个具体 CSR/字段。
  */
class ysyx_26030103_CSRAuditHarness(
    profile: ysyx_26030103_MMUProfile
) extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())
    val retireCommit = Input(Bool())
    val isCsrrw = Input(Bool())
    val isCsrrs = Input(Bool())
    val isCsrrc = Input(Bool())
    val isEcall = Input(Bool())
    val isEbreak = Input(Bool())
    val isMret = Input(Bool())
    val isSret = Input(Bool())
    val isSfenceVma = Input(Bool())
    val csrAddr = Input(UInt(12.W))
    val rs1 = Input(UInt(5.W))
    val rs1Data = Input(UInt(64.W))
    val pc = Input(UInt(64.W))
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
  val csr = Module(new ysyx_26030103_CSR(
    profile, XLEN = 64, UseF = true, UseD = true))
  csr.io.clk := clock
  csr.io.rst := reset
  csr.io.Enable := io.enable
  csr.io.RetireCommit := io.retireCommit
  csr.io.IsCsrrw := io.isCsrrw
  csr.io.IsCsrrs := io.isCsrrs
  csr.io.IsCsrrc := io.isCsrrc
  csr.io.IsEcall := io.isEcall
  csr.io.IsEbreak := io.isEbreak
  csr.io.IsMret := io.isMret
  csr.io.IsSret := io.isSret
  csr.io.IsSfenceVma := io.isSfenceVma
  csr.io.CSRAddress := io.csrAddr
  csr.io.rs1 := io.rs1
  csr.io.Rs1Data := io.rs1Data
  csr.io.pc := io.pc
  csr.io.TrapTval := io.trapTval
  csr.io.TrapTvalValid := io.trapTvalValid
  csr.io.TrapValid := io.trapValid
  csr.io.TrapCause := io.trapCause
  csr.io.MemTrap := io.memTrap
  csr.io.Interrupt := io.interrupt
  csr.io.TimerInterrupt := io.timerInterrupt
  csr.io.Mtime := io.mtime
  csr.io.FPCommit := false.B
  csr.io.FPFlags := 0.U
  csr.io.FPStateDirtyCommit := false.B
  io.rdata := csr.io.CSR_rdata
  io.stateTval := csr.io.StateTval
  io.trapCommit := csr.io.TrapCommit
  io.committedCause := csr.io.CommittedCause
  io.privilege := csr.io.Privilege
  io.stateMstatus := csr.io.StateMstatus
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

  /** 组合读 CSR，不推进时钟，保证多次读取看到同一拍的寄存器值。 */
  private def peekCsr(
      dut: ysyx_26030103_CSRAuditHarness,
      address: Int
  ): BigInt = {
    dut.io.csrAddr.poke(address.U)
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

  private def writeCsr(
      dut: ysyx_26030103_CSRAuditHarness,
      address: Int,
      data: BigInt
  ): Unit = {
    dut.io.csrAddr.poke(address.U)
    dut.io.rs1.poke(1.U)
    dut.io.rs1Data.poke(data.U(64.W))
    dut.io.enable.poke(true.B)
    dut.io.isCsrrw.poke(true.B)
    dut.clock.step()
    dut.io.enable.poke(false.B)
    dut.io.isCsrrw.poke(false.B)
  }

  it should "把 rdcycle 映射到 mcycle" in {
    simulate(new ysyx_26030103_CSRAuditHarness(profile)) { dut =>
      setup(dut)
      dut.clock.step(8)
      val mcycle = peekCsr(dut, MCYCLE)
      val cycle = peekCsr(dut, CYCLE)
      cycle should be(mcycle)
      cycle should not be BigInt(0)
    }
  }

  it should "把 rdtime 映射到 CLINT mtime" in {
    simulate(new ysyx_26030103_CSRAuditHarness(profile)) { dut =>
      setup(dut)
      dut.io.mtime.poke(BigInt("123456789abcdef0", 16).U(64.W))
      peekCsr(dut, TIME) should be(BigInt("123456789abcdef0", 16))
    }
  }

  it should "把 rdinstret 映射到 minstret" in {
    simulate(new ysyx_26030103_CSRAuditHarness(profile)) { dut =>
      setup(dut)
      stepEnable(dut, 4)
      val minstret = peekCsr(dut, MINSTRET)
      val instret = peekCsr(dut, INSTRET)
      instret should be(minstret)
      instret should not be BigInt(0)
    }
  }

  it should "在 RV64 的 sstatus 中保留 SXL/UXL=2" in {
    simulate(new ysyx_26030103_CSRAuditHarness(profile)) { dut =>
      setup(dut)
      val mstatus = peekCsr(dut, MSTATUS)
      val sstatus = peekCsr(dut, SSTATUS)
      // mstatus 是参照：CSR.scala 的 MstatusArchValue 会补 SXL/UXL。
      ((mstatus >> 32) & 0xf) should be(BigInt(0xa))
      // sstatus 是 mstatus 的受限视图，RV64 下同样应读回 SXL/UXL=2。
      ((sstatus >> 32) & 0xf) should be(BigInt(0xa))
    }
  }

  it should "在 ecall 时把 mtval 写成 0，而不是故障 PC" in {
    simulate(new ysyx_26030103_CSRAuditHarness(profile)) { dut =>
      setup(dut)
      val pc = BigInt("0000000080001234", 16)
      dut.io.pc.poke(pc.U(64.W))
      dut.io.trapTvalValid.poke(false.B)
      dut.io.enable.poke(true.B)
      dut.io.isEcall.poke(true.B)
      dut.io.trapCommit.peek().litToBoolean should be(true)
      dut.io.committedCause.peek().litValue should be(BigInt(11))
      dut.clock.step()
      dut.io.enable.poke(false.B)
      dut.io.isEcall.poke(false.B)
      // RISC-V 对 ecall 要求 mtval=0；当前实现会回退到 io.pc。
      peekCsr(dut, MTVAL) should be(BigInt(0))
      // 对照：mepc 仍应精确记录 ecall 指令的 PC。
      peekCsr(dut, MEPC) should be(pc)
    }
  }

  it should "不因纯中断提交自增 minstret" in {
    val irqProfile = profile.copy(hasSExternalIrq = false)
    simulate(new ysyx_26030103_CSRAuditHarness(irqProfile)) { dut =>
      setup(dut)
      writeCsr(dut, MSTATUS, BigInt(0x8))
      writeCsr(dut, 0x304, BigInt(0x800))
      writeCsr(dut, MTVEC, BigInt("0000000080002000", 16))
      val before = peekCsr(dut, MINSTRET)
      dut.io.pc.poke(BigInt("0000000080001000", 16).U(64.W))
      dut.io.enable.poke(true.B)
      dut.io.interrupt.poke(true.B)
      dut.io.trapCommit.peek().litToBoolean should be(true)
      dut.clock.step()
      dut.io.enable.poke(false.B)
      dut.io.interrupt.poke(false.B)
      // 中断没有退休指令，minstret 不能增加。
      peekCsr(dut, MINSTRET) should be(before)
    }
  }

  it should "支持 RV64 minstret 写入且写入拍不再自增" in {
    simulate(new ysyx_26030103_CSRAuditHarness(profile)) { dut =>
      setup(dut)
      writeCsr(dut, MINSTRET, BigInt(0x1234))
      peekCsr(dut, MINSTRET) should be(BigInt(0x1234))
      stepEnable(dut, 1)
      peekCsr(dut, MINSTRET) should be(BigInt(0x1235))
    }
  }

  it should "在中断提交时把 mtval 写成 0" in {
    // 默认 profile 的外部中断走 S 级；这里裁剪 hasSExternalIrq，让
    // io.interrupt 直接作为 M 级外部中断，在 M 模式验证中断 tval。
    val irqProfile = profile.copy(hasSExternalIrq = false)
    simulate(new ysyx_26030103_CSRAuditHarness(irqProfile)) { dut =>
      setup(dut)
      writeCsr(dut, MSTATUS, BigInt(0x8)) // mstatus.MIE
      writeCsr(dut, 0x304, BigInt(0x800)) // mie.MEIE
      writeCsr(dut, MTVEC, BigInt("0000000080002000", 16))
      // 预置非零 mtval，确保下面的 0 来自 trap 写入而不是复位值。
      writeCsr(dut, MTVAL, BigInt("deadbeef", 16))
      val pc = BigInt("0000000080001000", 16)
      dut.io.pc.poke(pc.U(64.W))
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
      peekCsr(dut, MTVAL) should be(BigInt(0))
      peekCsr(dut, MEPC) should be(pc)
    }
  }
}
