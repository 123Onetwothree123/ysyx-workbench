package ysyx_26030103.exu

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.ysyx_26030103.config._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** RV64 CSR 测试：64 位 satp WARL、sepc/stval 与 SRET/异常入口。 */
class ysyx_26030103_RV64CSRHarness(
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
    val trapValid = Input(Bool())
    val trapCause = Input(UInt(32.W))
    val memTrap = Input(Bool())
    val interrupt = Input(Bool())
    val rdata = Output(UInt(64.W))
    val exceptionTaken = Output(Bool())
    val exceptionTarget = Output(UInt(64.W))
    val flushTLB = Output(Bool())
    val privilege = Output(UInt(2.W))
    val satp = Output(UInt(64.W))
    val trapCommit = Output(Bool())
    val committedCause = Output(UInt(32.W))
  })
  val csr = Module(new ysyx_26030103_CSR(profile, XLEN = 64))
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
  csr.io.TrapTvalValid := true.B
  csr.io.Interrupt := io.interrupt
  csr.io.TimerInterrupt := false.B
  csr.io.TrapValid := io.trapValid
  csr.io.TrapCause := io.trapCause
  csr.io.MemTrap := io.memTrap
  csr.io.Mtime := 0.U
  csr.io.FPCommit := false.B
  csr.io.FPFlags := 0.U
  csr.io.FPStateDirtyCommit := false.B
  io.rdata := csr.io.CSR_rdata
  io.exceptionTaken := csr.io.ExceptionTaken
  io.exceptionTarget := csr.io.ExceptionTarget
  io.flushTLB := csr.io.FlushTLB
  io.privilege := csr.io.Privilege
  io.satp := csr.io.Satp
  io.trapCommit := csr.io.TrapCommit
  io.committedCause := csr.io.CommittedCause
}

class ysyx_26030103_RV64CSRTest extends AnyFlatSpec with Matchers {
  private val MSTATUS = 0x300
  private val MEDELEG = 0x302
  private val MEPC = 0x341
  private val STVEC = 0x105
  private val SEPC = 0x141
  private val STVAL = 0x143
  private val SATP = 0x180

  private def profile: ysyx_26030103_MMUProfile =
    ysyx_26030103_MMUProfile(
      enabled = true,
      xlen = 64,
      levels = 3,
      pteSizeBytes = 8,
      pteFormat = "riscv64",
      pteConfig = ysyx_26030103_PteConfig(pteBits = 64, ppnHigh = 53),
      tlbEnabled = true,
      tlbAsidBits = 16
    )

  private def bool(v: Bool): Boolean = v.peek().litValue == 1
  private def big(v: UInt): BigInt = v.peek().litValue

  private def setup(dut: ysyx_26030103_RV64CSRHarness): Unit = {
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
    dut.io.trapValid.poke(false.B)
    dut.io.trapCause.poke(0.U)
    dut.io.memTrap.poke(false.B)
    dut.io.interrupt.poke(false.B)
  }

  private def write(dut: ysyx_26030103_RV64CSRHarness, addr: Int, data: BigInt): Unit = {
    dut.io.csrAddr.poke(addr.U)
    dut.io.rs1.poke(1.U)
    dut.io.rs1Data.poke(data.U(64.W))
    dut.io.enable.poke(true.B)
    dut.io.isCsrrw.poke(true.B)
    dut.clock.step()
    dut.io.enable.poke(false.B)
    dut.io.isCsrrw.poke(false.B)
  }

  private def read(dut: ysyx_26030103_RV64CSRHarness, addr: Int): BigInt = {
    dut.io.csrAddr.poke(addr.U)
    dut.io.enable.poke(false.B)
    dut.clock.step()
    big(dut.io.rdata)
  }

  "CSR" should "支持 64 位 satp 的 MODE/ASID/PPN" in {
    simulate(new ysyx_26030103_RV64CSRHarness(profile)) { dut =>
      setup(dut)
      val satp = (BigInt(8) << 60) | (BigInt(0xabcd) << 44) | BigInt("80001", 16)
      write(dut, SATP, satp)
      big(dut.io.satp) should be(satp)
      read(dut, SATP) should be(satp)
      // 写入未实现的 Sv48 MODE=9：WARL 收敛到 Bare(0)，其余字段保留。
      val unsupported = (BigInt(9) << 60) | (BigInt(0xabcd) << 44) | BigInt("80001", 16)
      write(dut, SATP, unsupported)
      big(dut.io.satp) should be(unsupported & ~(BigInt(0xf) << 60))
      write(dut, SATP, (BigInt(8) << 60) | BigInt("80001", 16))
      dut.io.isSfenceVma.poke(true.B)
      dut.io.enable.poke(true.B)
      bool(dut.io.flushTLB) should be(true)
      dut.clock.step()
    }
  }

  it should "在 sstatus 中保留 SUM/MXR 字段供 Sv39 权限检查使用" in {
    simulate(new ysyx_26030103_RV64CSRHarness(profile)) { dut =>
      setup(dut)
      write(dut, 0x100, BigInt("000c0000", 16)) // SUM | MXR
      (read(dut, 0x100) & BigInt("000c0000", 16)) should be(BigInt("000c0000", 16))
    }
  }

  it should "支持 64 位 sepc 和 S 模式页错误 stval" in {
    simulate(new ysyx_26030103_RV64CSRHarness(profile)) { dut =>
      setup(dut)
      // MRET 进入 S。
      write(dut, MSTATUS, BigInt("00000800", 16))
      write(dut, MEPC, BigInt("0000000080001000", 16))
      dut.io.enable.poke(true.B)
      dut.io.isMret.poke(true.B)
      dut.clock.step()
      dut.io.enable.poke(false.B)
      dut.io.isMret.poke(false.B)
      big(dut.io.privilege) should be(BigInt(1))

      write(dut, MEDELEG, BigInt(1) << 13)
      write(dut, STVEC, BigInt("0000000080002000", 16))
      write(dut, SEPC, BigInt("0000000080001234", 16))
      read(dut, SEPC) should be(BigInt("0000000080001234", 16))

      dut.io.pc.poke(BigInt("0000000080001234", 16).U(64.W))
      dut.io.trapTval.poke(BigInt("0000000080003000", 16).U(64.W))
      dut.io.trapCause.poke(13.U)
      dut.io.trapValid.poke(true.B)
      dut.io.enable.poke(true.B)
      bool(dut.io.trapCommit) should be(true)
      big(dut.io.exceptionTarget) should be(BigInt("0000000080002000", 16))
      dut.clock.step()
      dut.io.trapValid.poke(false.B)
      dut.io.enable.poke(false.B)
      read(dut, SEPC) should be(BigInt("0000000080001234", 16))
      read(dut, STVAL) should be(BigInt("0000000080003000", 16))
    }
  }

  it should "支持 M+U（无 S）的 64 位 MRET/ecall 入口" in {
    val mPlusU = ysyx_26030103_MMUProfile(
      enabled = false,
      xlen = 64,
      pteSizeBytes = 8,
      pteFormat = "riscv64",
      pteConfig = ysyx_26030103_PteConfig(pteBits = 64, ppnHigh = 53),
      hasS = false,
      hasU = true
    )
    simulate(new ysyx_26030103_RV64CSRHarness(mPlusU)) { dut =>
      setup(dut)
      write(dut, 0x305, BigInt("0000000080002000", 16))
      write(dut, MSTATUS, 0)
      write(dut, MEPC, BigInt("0000000080001000", 16))
      dut.io.enable.poke(true.B)
      dut.io.isMret.poke(true.B)
      dut.clock.step()
      dut.io.enable.poke(false.B)
      dut.io.isMret.poke(false.B)
      big(dut.io.privilege) should be(BigInt(0))
      big(dut.io.exceptionTarget) should be(BigInt("0000000080001000", 16))

      dut.io.pc.poke(BigInt("0000000080001008", 16).U(64.W))
      dut.io.enable.poke(true.B)
      dut.io.isEcall.poke(true.B)
      bool(dut.io.trapCommit) should be(true)
      big(dut.io.committedCause) should be(BigInt(8))
      big(dut.io.exceptionTarget) should be(BigInt("0000000080002000", 16))
      dut.clock.step()
      dut.io.enable.poke(false.B)
      dut.io.isEcall.poke(false.B)
      big(dut.io.privilege) should be(BigInt(3))
      big(dut.io.exceptionTarget) should be(BigInt("0000000080001008", 16))
      read(dut, 0x341) should be(BigInt("0000000080001008", 16))
      read(dut, 0x342) should be(BigInt(8))
      ((read(dut, MSTATUS) >> 11) & 3) should be(BigInt(0))
      read(dut, 0x100) should be(BigInt(0))
      read(dut, 0x180) should be(BigInt(0))
    }
  }

  it should "把 32 位中断 cause 映射到 RV64 的 bit63" in {
    simulate(new ysyx_26030103_RV64CSRHarness(profile)) { dut =>
      setup(dut)
      // M -> S，并提前配置 S 外部中断委托/使能。
      write(dut, MSTATUS, BigInt("00000800", 16))
      write(dut, MEPC, BigInt("0000000080001000", 16))
      write(dut, 0x303, BigInt(1) << 9) // mideleg.SEIE
      write(dut, 0x304, BigInt(1) << 9) // mie.SEIE
      dut.io.enable.poke(true.B)
      dut.io.isMret.poke(true.B)
      dut.clock.step()
      dut.io.enable.poke(false.B)
      dut.io.isMret.poke(false.B)
      big(dut.io.privilege) should be(BigInt(1))

      write(dut, 0x100, BigInt(2)) // sstatus.SIE
      dut.io.pc.poke(BigInt("0000000080001000", 16).U(64.W))
      dut.io.enable.poke(true.B)
      dut.io.interrupt.poke(true.B)
      bool(dut.io.trapCommit) should be(true)
      big(dut.io.committedCause) should be(BigInt("80000009", 16))
      dut.clock.step()
      dut.io.interrupt.poke(false.B)
      dut.io.enable.poke(false.B)

      // RV64 架构 scause = 0x8000_0000_0000_0009，而不是
      // 0x0000_0000_8000_0009 或 0xffff_ffff_8000_0009。
      read(dut, 0x142) should be(BigInt("8000000000000009", 16))
    }
  }

  it should "RV64 misa 使用 MXL=2，并保留 S/U 与只读语义" in {
    simulate(new ysyx_26030103_RV64CSRHarness(profile)) { dut =>
      setup(dut)
      // MXL=2 位于 bit[63:62]；I=bit8，S=bit18，U=bit20。
      val expected = BigInt("8000000000140100", 16)
      read(dut, 0x301) should be(expected)
      write(dut, 0x301, 0)
      read(dut, 0x301) should be(expected)
    }
  }

  it should "RV64 下 identity CSR 零扩展且 mhartid/mimpid 固定 0" in {
    simulate(new ysyx_26030103_RV64CSRHarness(profile)) { dut =>
      setup(dut)
      read(dut, 0xf11) should be(BigInt("0000000079737978", 16))
      read(dut, 0xf12) should be(BigInt("00000000018d3017", 16))
      read(dut, 0xf13) should be(0)
      read(dut, 0xf14) should be(0)
      write(dut, 0xf14, BigInt("ffffffffffffffff", 16))
      read(dut, 0xf14) should be(0)
    }
  }
}
