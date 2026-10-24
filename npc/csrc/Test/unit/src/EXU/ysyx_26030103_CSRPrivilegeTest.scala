package ysyx_26030103.exu

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** 测试用的 CSR 顶层，把显式 clk/rst 连接到仿真器的隐式时钟复位。 */
class ysyx_26030103_CSRHarness(
    profile: _root_.ysyx_26030103.config.ysyx_26030103_MMUProfile =
      _root_.ysyx_26030103.config.ysyx_26030103_MMUProfile()
) extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())
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
    val rs1Data = Input(UInt(32.W))
    val pc = Input(UInt(32.W))
    val trapTval = Input(UInt(32.W))
    val interrupt = Input(Bool())
    val timerInterrupt = Input(Bool())
    val trapValid = Input(Bool())
    val trapCause = Input(UInt(32.W))
    val memTrap = Input(Bool())
    val rdata = Output(UInt(32.W))
    val exceptionTaken = Output(Bool())
    val exceptionTarget = Output(UInt(32.W))
    val flushTLB = Output(Bool())
    val privilege = Output(UInt(2.W))
    val satp = Output(UInt(32.W))
    val trapCommit = Output(Bool())
    val committedCause = Output(UInt(32.W))
    val stateMstatus = Output(UInt(32.W))
    val stateMepc = Output(UInt(32.W))
    val stateMcause = Output(UInt(32.W))
  })
  val csr = Module(new ysyx_26030103_CSR(profile))
  csr.io.clk := clock
  csr.io.rst := reset
  csr.io.Enable := io.enable
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
  csr.io.Interrupt := io.interrupt
  csr.io.TimerInterrupt := io.timerInterrupt
  csr.io.Mtime := 0.U
  csr.io.TrapValid := io.trapValid
  csr.io.TrapCause := io.trapCause
  csr.io.MemTrap := io.memTrap
  io.rdata := csr.io.CSR_rdata
  io.exceptionTaken := csr.io.ExceptionTaken
  io.exceptionTarget := csr.io.ExceptionTarget
  io.flushTLB := csr.io.FlushTLB
  io.privilege := csr.io.Privilege
  io.satp := csr.io.Satp
  io.trapCommit := csr.io.TrapCommit
  io.committedCause := csr.io.CommittedCause
  io.stateMstatus := csr.io.StateMstatus
  io.stateMepc := csr.io.StateMepc
  io.stateMcause := csr.io.StateMcause
}

class ysyx_26030103_CSRPrivilegeTest extends AnyFlatSpec with Matchers {
  private val MSTATUS = 0x300
  private val MIE = 0x304
  private val MEPC = 0x341
  private val MEDELEG = 0x302
  private val STVEC = 0x105
  private val SEPC = 0x141
  private val SCAUSE = 0x142
  private val STVAL = 0x143
  private val SATP = 0x180

  private def bool(v: Bool): Boolean = v.peek().litValue == 1
  private def long(v: UInt): Long = v.peek().litValue.toLong

  private def setup(dut: ysyx_26030103_CSRHarness): Unit = {
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
    dut.io.enable.poke(false.B)
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
    dut.io.interrupt.poke(false.B)
    dut.io.timerInterrupt.poke(false.B)
    dut.io.trapValid.poke(false.B)
    dut.io.trapCause.poke(0.U)
    dut.io.memTrap.poke(false.B)
  }

  private def write(dut: ysyx_26030103_CSRHarness, addr: Int, data: BigInt): Unit = {
    dut.io.csrAddr.poke(addr.U)
    dut.io.rs1.poke(1.U)
    dut.io.rs1Data.poke(data.U(32.W))
    dut.io.enable.poke(true.B)
    dut.io.isCsrrw.poke(true.B)
    dut.clock.step()
    dut.io.enable.poke(false.B)
    dut.io.isCsrrw.poke(false.B)
  }

  private def read(dut: ysyx_26030103_CSRHarness, addr: Int): BigInt = {
    dut.io.csrAddr.poke(addr.U)
    dut.io.enable.poke(false.B)
    dut.clock.step()
    dut.io.rdata.peek().litValue
  }

  "CSR" should "支持 satp 写入和 sfence.vma 刷新脉冲" in {
    simulate(new ysyx_26030103_CSRHarness) { dut =>
      setup(dut)
      val satp = (BigInt(1) << 31) | 0x80001
      write(dut, SATP, satp)
      long(dut.io.satp) should be(satp.toLong)
      dut.io.isSfenceVma.poke(true.B)
      dut.io.enable.poke(true.B)
      bool(dut.io.flushTLB) should be(true)
      dut.clock.step()
    }
  }

  it should "从 M 模式 MRET 到 S，并支持 S 模式异常委托" in {
    simulate(new ysyx_26030103_CSRHarness) { dut =>
      setup(dut)
      // mstatus.MPP=S，mepc=0x80001000，然后执行 MRET。
      write(dut, MSTATUS, BigInt("00000800", 16))
      write(dut, MEPC, 0x80001000L)
      dut.io.enable.poke(true.B)
      dut.io.isMret.poke(true.B)
      dut.clock.step()
      dut.io.enable.poke(false.B)
      dut.io.isMret.poke(false.B)
      long(dut.io.privilege) should be(1L)

      // 把 load page fault(cause=13)委托给 S，并设置 stvec。
      write(dut, MEDELEG, BigInt(1) << 13)
      write(dut, STVEC, 0x80002000L)

      dut.io.pc.poke(0x80001234L)
      dut.io.trapTval.poke(0xdeadbeefL)
      dut.io.trapCause.poke(13.U)
      dut.io.trapValid.poke(true.B)
      dut.io.enable.poke(true.B)
      bool(dut.io.exceptionTaken) should be(true)
      long(dut.io.exceptionTarget) should be(0x80002000L)
      bool(dut.io.trapCommit) should be(true)
      dut.clock.step()
      dut.io.trapValid.poke(false.B)
      dut.io.enable.poke(false.B)
      long(dut.io.privilege) should be(1L)
      read(dut, SCAUSE) should be(BigInt(13))
      read(dut, STVAL) should be(BigInt("deadbeef", 16))
      read(dut, SEPC) should be(0x80001234L)
      (read(dut, 0x100) & BigInt(0x100)) should be(BigInt(0x100))
    }
  }

  it should "支持 mstatus/satp 字段 mask 配置" in {
    val profile = _root_.ysyx_26030103.config.ysyx_26030103_MMUProfile(
      csrMstatusReadMask = 0,
      csrMstatusReadSet = 0x8,
      csrMstatusWriteMask = 0,
      csrSatpReadMask = 0x000000ff,
      csrSatpReadSet = 0x100,
      csrSatpWriteMask = 0x0000000f
    )
    simulate(new ysyx_26030103_CSRHarness(profile)) { dut =>
      setup(dut)
      write(dut, MSTATUS, BigInt("00001888", 16))
      read(dut, MSTATUS) should be(BigInt(8))
      write(dut, SATP, BigInt("80000123", 16))
      // 低 4 位可写；高 4 位来自 read set，ASID/PPN 被 mask。
      (read(dut, SATP) & BigInt(0xff)) should be(BigInt(0x3))
    }
  }

  it should "timer 中断产生 cause=7" in {
    simulate(new ysyx_26030103_CSRHarness) { dut =>
      setup(dut)
      write(dut, MSTATUS, BigInt(8))
      write(dut, MIE, BigInt(0x80))
      dut.io.timerInterrupt.poke(true.B)
      dut.clock.step()
      dut.io.enable.poke(true.B)
      bool(dut.io.trapCommit) should be(true)
      long(dut.io.committedCause) should be(0x80000007L)
      dut.clock.step()
    }
  }

  it should "按字段配置生成 mstatus/satp 读写掩码" in {
    val p = _root_.ysyx_26030103.config.ysyx_26030103_MMUProfile.fromEnv(Map(
      "CSR_FIELD_DEFAULT_READ" -> "normal",
      "CSR_FIELD_DEFAULT_WRITE" -> "normal",
      "CSR_MSTATUS_MIE_READ" -> "zero",
      "CSR_MSTATUS_MIE_WRITE" -> "ignore",
      "CSR_MSTATUS_SPP_READ" -> "one",
      "CSR_SATP_ASID_READ" -> "zero"
    ).get)
    (p.csrMstatusReadMask & 0x8) should be(0)
    (p.csrMstatusReadSet & 0x8) should be(0)
    (p.csrMstatusWriteMask & 0x8) should be(0)
    (p.csrMstatusReadMask & 0x100) should be(0x100)
    (p.csrMstatusReadSet & 0x100) should be(0x100)
    (p.csrSatpReadMask & BigInt("7fc00000", 16).toInt) should be(0)
  }

  it should "关闭的 CSR 读 0 且写忽略" in {
    simulate(new ysyx_26030103_CSRHarness(
      _root_.ysyx_26030103.config.ysyx_26030103_MMUProfile(csrEnabled = Set(0x300))
    )) { dut =>
      setup(dut)
      write(dut, 0x180, BigInt("80080001", 16))
      read(dut, 0x180) should be(0)
    }
  }

  it should "S 模式外部中断经 mideleg 进入 stvec" in {
    simulate(new ysyx_26030103_CSRHarness) { dut =>
      setup(dut)
      // MPP=S，SIE=1，mideleg.SEIP=1，mie.SEIP=1，stvec 指向 S 入口。
      write(dut, MSTATUS, BigInt("00000802", 16))
      write(dut, 0x303, BigInt("00000200", 16))
      write(dut, MIE, BigInt("00000200", 16))
      write(dut, 0x105, BigInt("80001000", 16))
      dut.io.isMret.poke(true.B)
      dut.io.enable.poke(true.B)
      dut.clock.step()
      dut.io.isMret.poke(false.B)
      dut.io.interrupt.poke(true.B)
      dut.io.enable.poke(true.B)
      long(dut.io.committedCause) should be(0x80000009L)
      dut.io.exceptionTaken.peek().litValue should be(1)
      long(dut.io.exceptionTarget) should be(BigInt("80001000", 16).toLong)
      dut.clock.step()
    }
  }

  it should "direct_stip 直接产生 S 模式 timer 中断" in {
    simulate(new ysyx_26030103_CSRHarness(
      _root_.ysyx_26030103.config.ysyx_26030103_MMUProfile(timerPath = "direct_stip")
    )) { dut =>
      setup(dut)
      write(dut, MSTATUS, BigInt("00000802", 16))
      write(dut, 0x303, BigInt("00000020", 16))
      write(dut, MIE, BigInt("00000020", 16))
      write(dut, 0x105, BigInt("80001000", 16))
      dut.io.isMret.poke(true.B)
      dut.io.enable.poke(true.B)
      dut.clock.step()
      dut.io.isMret.poke(false.B)
      dut.io.timerInterrupt.poke(true.B)
      dut.io.enable.poke(true.B)
      long(dut.io.committedCause) should be(0x80000005L)
      dut.io.exceptionTaken.peek().litValue should be(1)
      long(dut.io.exceptionTarget) should be(BigInt("80001000", 16).toLong)
      dut.clock.step()
    }
  }

  it should "CSR independent 视图使用独立 sstatus" in {
    simulate(new ysyx_26030103_CSRHarness(
      _root_.ysyx_26030103.config.ysyx_26030103_MMUProfile(csrViewMode = "independent")
    )) { dut =>
      setup(dut)
      write(dut, 0x100, BigInt(2))
      read(dut, 0x100) should be(BigInt(2))
      write(dut, MSTATUS, BigInt(8))
      read(dut, 0x100) should be(BigInt(2))
      read(dut, MSTATUS) should be(BigInt(8))
    }
  }

  it should "SRET 按 SPP 返回 U/S" in {
    simulate(new ysyx_26030103_CSRHarness) { dut =>
      setup(dut)
      // 先进入 S。
      write(dut, MSTATUS, BigInt("00000800", 16))
      write(dut, MEPC, 0x80001000L)
      dut.io.enable.poke(true.B)
      dut.io.isMret.poke(true.B)
      dut.clock.step()
      dut.io.enable.poke(false.B)
      dut.io.isMret.poke(false.B)
      long(dut.io.privilege) should be(1L)

      // SPP=0 => SRET 返回 U；sepc 是返回地址。
      write(dut, SEPC, 0x80003000L)
      dut.io.enable.poke(true.B)
      dut.io.isSret.poke(true.B)
      dut.clock.step()
      dut.io.enable.poke(false.B)
      dut.io.isSret.poke(false.B)
      long(dut.io.privilege) should be(0L)
      long(dut.io.exceptionTarget) should be(0x80003000L)
    }
  }

  it should "HAS_U=n 时 MRET/SRET 降级到 S 且 ecall 不产生 cause 8" in {
    val profile = _root_.ysyx_26030103.config.ysyx_26030103_MMUProfile(hasU = false)
    simulate(new ysyx_26030103_CSRHarness(profile)) { dut =>
      setup(dut)
      // 软件尝试把 MPP 写成 U(0)，硬件必须夹到 S(1)。
      write(dut, MSTATUS, 0)
      ((read(dut, MSTATUS) >> 11) & 3) should be(1)

      // MRET 只能返回 S。
      write(dut, MEPC, 0x80001000L)
      dut.io.enable.poke(true.B)
      dut.io.isMret.poke(true.B)
      dut.clock.step()
      dut.io.enable.poke(false.B)
      dut.io.isMret.poke(false.B)
      long(dut.io.privilege) should be(1L)

      // SPP 写 0 同样被夹到 1，SRET 仍返回 S。
      write(dut, 0x100, 0)
      ((read(dut, 0x100) >> 8) & 1) should be(1)
      write(dut, SEPC, 0x80003000L)
      dut.io.enable.poke(true.B)
      dut.io.isSret.poke(true.B)
      dut.clock.step()
      long(dut.io.privilege) should be(1L)
      long(dut.io.exceptionTarget) should be(0x80003000L)
      dut.io.enable.poke(false.B)
      dut.io.isSret.poke(false.B)

      // 当前只能是 S，ecall 必须是 cause=9，不能出现 U 的 cause=8。
      dut.io.enable.poke(true.B)
      dut.io.isEcall.poke(true.B)
      bool(dut.io.trapCommit) should be(true)
      long(dut.io.committedCause) should be(9L)
      dut.clock.step()
      dut.io.enable.poke(false.B)
      dut.io.isEcall.poke(false.B)
    }
  }

  it should "HAS_U=n 时裁剪 U 级 CSR 地址" in {
    val withU = _root_.ysyx_26030103.config.ysyx_26030103_MMUProfile(
      csrEnabled = Set(0x100, 0x300, 0xc00)
    )
    val noU = withU.copy(hasU = false)
    withU.csrSupported(0xc00) should be(true)
    noU.csrSupported(0xc00) should be(false)
    noU.csrSupported(0x100) should be(true)
    noU.csrSupported(0x300) should be(true)
    noU.lowestPrivilege should be(1)
  }

  it should "HAS_S=n 时只保留 M 模式 CSR、ecall 和 MRET 入口" in {
    val profile = _root_.ysyx_26030103.config.ysyx_26030103_MMUProfile(
      hasS = false,
      hasU = false
    )
    simulate(new ysyx_26030103_CSRHarness(profile)) { dut =>
      setup(dut)
      long(dut.io.privilege) should be(3L)

      // S 模式 CSR 已被裁掉：读 0，写忽略。
      write(dut, 0x180, BigInt("80080001", 16))
      read(dut, 0x180) should be(0)
      read(dut, 0x100) should be(0)
      read(dut, 0x105) should be(0)
      long(dut.io.satp) should be(0L)

      // M-only 的 ecall 只能是 cause=11。
      dut.io.enable.poke(true.B)
      dut.io.isEcall.poke(true.B)
      bool(dut.io.trapCommit) should be(true)
      long(dut.io.committedCause) should be(11L)
      dut.clock.step()
      dut.io.enable.poke(false.B)
      dut.io.isEcall.poke(false.B)

      // SRET/SFENCE.VMA 不产生 CSR 侧提交或刷新。
      dut.io.enable.poke(true.B)
      dut.io.isSret.poke(true.B)
      bool(dut.io.exceptionTaken) should be(false)
      dut.io.isSret.poke(false.B)
      dut.io.isSfenceVma.poke(true.B)
      bool(dut.io.flushTLB) should be(false)
      dut.io.isSfenceVma.poke(false.B)
      dut.io.enable.poke(false.B)

      // MRET 仍返回 M，mepc 是目标。
      write(dut, MEPC, 0x80001000L)
      dut.io.enable.poke(true.B)
      dut.io.isMret.poke(true.B)
      bool(dut.io.exceptionTaken) should be(true)
      long(dut.io.exceptionTarget) should be(0x80001000L)
      dut.clock.step()
      dut.io.enable.poke(false.B)
      dut.io.isMret.poke(false.B)
      long(dut.io.privilege) should be(3L)
    }
  }

  it should "M+U：MRET 返回 U，U ecall 以 cause=8 进入 M 且 MPP=U" in {
    val profile = _root_.ysyx_26030103.config.ysyx_26030103_MMUProfile(
      hasS = false,
      hasU = true
    )
    simulate(new ysyx_26030103_CSRHarness(profile)) { dut =>
      setup(dut)
      // M+U 下 M 模式 ecall 仍然是 cause=11。
      dut.io.enable.poke(true.B)
      dut.io.isEcall.poke(true.B)
      bool(dut.io.trapCommit) should be(true)
      long(dut.io.committedCause) should be(11L)
      dut.clock.step()
      dut.io.enable.poke(false.B)
      dut.io.isEcall.poke(false.B)

      // M+U 没有 S：软件写 MPP=S(1) 时 WARL 收敛到 U(0)，MRET 进入 U。
      write(dut, 0x305, 0x80002000L)
      write(dut, MSTATUS, 0x800)
      ((read(dut, MSTATUS) >> 11) & 3) should be(0)
      write(dut, MSTATUS, 0)
      ((read(dut, MSTATUS) >> 11) & 3) should be(0)
      write(dut, MEPC, 0x80001000L)
      dut.io.enable.poke(true.B)
      dut.io.isMret.poke(true.B)
      bool(dut.io.exceptionTaken) should be(true)
      long(dut.io.exceptionTarget) should be(0x80001000L)
      dut.clock.step()
      dut.io.enable.poke(false.B)
      dut.io.isMret.poke(false.B)
      long(dut.io.privilege) should be(0L)

      // U 模式 ecall 直接进入 M，cause=8，mtvec 是入口。
      dut.io.pc.poke(0x80001004L)
      dut.io.enable.poke(true.B)
      dut.io.isEcall.poke(true.B)
      bool(dut.io.trapCommit) should be(true)
      long(dut.io.committedCause) should be(8L)
      long(dut.io.exceptionTarget) should be(0x80002000L)
      dut.clock.step()
      dut.io.enable.poke(false.B)
      dut.io.isEcall.poke(false.B)
      long(dut.io.privilege) should be(3L)
      read(dut, MEPC) should be(0x80001004L)
      read(dut, 0x342) should be(8L)
      ((read(dut, MSTATUS) >> 11) & 3) should be(0)

      // MPP 仍是 U，MRET 可以再次回到 U。
      write(dut, MEPC, 0x80003000L)
      dut.io.enable.poke(true.B)
      dut.io.isMret.poke(true.B)
      dut.clock.step()
      dut.io.enable.poke(false.B)
      dut.io.isMret.poke(false.B)
      long(dut.io.privilege) should be(0L)
      long(dut.io.exceptionTarget) should be(0x80003000L)
    }
  }

  it should "M+U：U 的非法指令和地址异常都进入 M" in {
    val profile = _root_.ysyx_26030103.config.ysyx_26030103_MMUProfile(
      hasS = false,
      hasU = true
    )
    simulate(new ysyx_26030103_CSRHarness(profile)) { dut =>
      setup(dut)
      write(dut, 0x305, 0x80002000L)
      write(dut, MSTATUS, 0)
      write(dut, MEPC, 0x80004000L)
      dut.io.enable.poke(true.B)
      dut.io.isMret.poke(true.B)
      dut.clock.step()
      dut.io.enable.poke(false.B)
      dut.io.isMret.poke(false.B)
      long(dut.io.privilege) should be(0L)

      // cause=2 非法指令，cause=1/5/7 取指/load/store 访问异常。
      val faults = Seq(
        2L -> 0xdeadbeefL,
        1L -> 0x80005000L,
        5L -> 0x80006000L,
        7L -> 0x80007000L
      )
      for ((cause, tval) <- faults) {
        val pc = 0x80004000L + (cause << 2)
        dut.io.pc.poke(pc.U)
        dut.io.trapTval.poke(tval.U)
        dut.io.trapCause.poke(cause.U)
        dut.io.trapValid.poke(true.B)
        dut.io.enable.poke(true.B)
        bool(dut.io.trapCommit) should be(true)
        long(dut.io.committedCause) should be(cause)
        long(dut.io.exceptionTarget) should be(0x80002000L)
        dut.clock.step()
        dut.io.trapValid.poke(false.B)
        dut.io.enable.poke(false.B)
        long(dut.io.privilege) should be(3L)
        read(dut, MEPC) should be(pc)
        read(dut, 0x342) should be(cause)
        read(dut, 0x343) should be(tval)
        ((read(dut, MSTATUS) >> 11) & 3) should be(0)

        // MPP=U，MRET 回到 U 继续下一项。
        dut.io.enable.poke(true.B)
        dut.io.isMret.poke(true.B)
        dut.clock.step()
        dut.io.enable.poke(false.B)
        dut.io.isMret.poke(false.B)
        long(dut.io.privilege) should be(0L)
      }
    }
  }

  it should "M+U 不生成 S CSR，SRET/SFENCE.VMA 不产生副作用" in {
    val profile = _root_.ysyx_26030103.config.ysyx_26030103_MMUProfile(
      hasS = false,
      hasU = true
    )
    val chirrtl = ChiselStage.emitCHIRRTL(new ysyx_26030103_CSR(profile))
    Seq("SatpReg", "Sscratch", "Stval", "Medeleg", "Mideleg",
      "SstatusReg").foreach { name =>
      chirrtl should not include name
    }
    simulate(new ysyx_26030103_CSRHarness(profile)) { dut =>
      setup(dut)
      read(dut, 0x100) should be(0)
      read(dut, 0x105) should be(0)
      read(dut, 0x141) should be(0)
      read(dut, 0x142) should be(0)
      read(dut, 0x143) should be(0)
      read(dut, 0x180) should be(0)
      dut.io.enable.poke(true.B)
      dut.io.isSret.poke(true.B)
      bool(dut.io.exceptionTaken) should be(false)
      dut.io.isSret.poke(false.B)
      dut.io.isSfenceVma.poke(true.B)
      bool(dut.io.flushTLB) should be(false)
      dut.io.isSfenceVma.poke(false.B)
      dut.io.enable.poke(false.B)
    }
  }

  it should "HAS_S=n 的结构中不出现 S 模式状态寄存器" in {
    val profile = _root_.ysyx_26030103.config.ysyx_26030103_MMUProfile(
      hasS = false,
      hasU = false
    )
    val chirrtl = ChiselStage.emitCHIRRTL(new ysyx_26030103_CSR(profile))
    Seq("SatpReg", "Sscratch", "Stval", "Medeleg", "Mideleg",
      "SstatusReg").foreach { name =>
      chirrtl should not include name
    }
  }
}
