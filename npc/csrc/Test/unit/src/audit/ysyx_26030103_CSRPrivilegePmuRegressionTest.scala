package ysyx_26030103.audit

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.ysyx_26030103.common._
import _root_.ysyx_26030103.config._
import _root_.ysyx_26030103.exu.ysyx_26030103_CSR
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** CSR 特权级/PMU 回归：
  *   - minstret 写入指令自身退休不得再自增；
  *   - independent sstatus 的 FS/SD 必须与 mstatus/FP dirty 一致；
  *   - SRET 被中断抢占时 trap-entry 状态优先；
  *   - sip/mip 的 SSIP 是同一个 pending 位；
  *   - RV64 只为实际实现的 S/U 特权级报告 SXL/UXL；
  *   - mip.LCOFI 写 0 可清除 Sscofpmf overflow；
  *   - S/U 模式读取 scountovf 受 mcounteren 门控。
  */
class ysyx_26030103_CSRPrivilegePmuHarness(
    val xlen: Int,
    val profile: ysyx_26030103_MMUProfile,
    val useF: Boolean = false,
    val pmuEnable: Boolean = false,
    val pmuSscofpmf: Boolean = true
) extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())
    val retireCommit = Input(Bool())
    val minstretRetireCommit = Input(Bool())
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
    val rs1Data = Input(UInt(xlen.W))
    val pc = Input(UInt(xlen.W))
    val trapTval = Input(UInt(xlen.W))
    val trapTvalValid = Input(Bool())
    val trapValid = Input(Bool())
    val trapCause = Input(UInt(32.W))
    val memTrap = Input(Bool())
    val interrupt = Input(Bool())
    val timerInterrupt = Input(Bool())
    val mtime = Input(UInt(64.W))
    val fpStateDirtyCommit = Input(Bool())
    val pmuEvents = Input(UInt(ysyx_26030103_PMUEventId.BusWidth.W))
    val rdata = Output(UInt(xlen.W))
    val privilege = Output(UInt(2.W))
    val trapCommit = Output(Bool())
    val committedCause = Output(UInt(32.W))
    val minstretWriteCommit = Output(Bool())
  })

  val csr = Module(new ysyx_26030103_CSR(
    profile,
    XLEN = xlen,
    UseF = useF,
    UseD = useF,
    PMUEnable = pmuEnable,
    PMUZihpm = true,
    PMUSscofpmf = pmuSscofpmf
  ))
  csr.io.clk := clock
  csr.io.rst := reset
  csr.io.Enable := io.enable
  csr.io.MinstretRetireCommit := io.minstretRetireCommit
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
  csr.io.FPStateDirtyCommit := io.fpStateDirtyCommit
  csr.io.PMUEvents := io.pmuEvents

  io.rdata := csr.io.CSR_rdata
  io.privilege := csr.io.Privilege
  io.trapCommit := csr.io.TrapCommit
  io.committedCause := csr.io.CommittedCause
  io.minstretWriteCommit := csr.io.MinstretWriteCommit
}

class ysyx_26030103_CSRPrivilegePmuRegressionTest
    extends AnyFlatSpec with Matchers {
  private val MSTATUS = 0x300
  private val MIE = 0x304
  private val MIP = 0x344
  private val MEPC = 0x341
  private val MIDELEG = 0x303
  private val MCOUNTEREN = 0x306
  private val SSTATUS = 0x100
  private val SIP = 0x144
  private val MINSTRET = 0xb02
  private val MHPMEVENT3 = 0x323
  private val MHPMCOUNTER3 = 0xb03
  private val MHPMCOUNTER3H = 0xb83
  private val SCOUNTOVF = 0xda0

  private def profile64(
      hasS: Boolean = true,
      hasU: Boolean = true,
      viewMode: String = "bound"
  ): ysyx_26030103_MMUProfile =
    ysyx_26030103_MMUProfile(
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
      csrViewMode = viewMode
    )

  private def profile32(
      viewMode: String = "bound"
  ): ysyx_26030103_MMUProfile =
    ysyx_26030103_MMUProfile(
      enabled = false,
      xlen = 32,
      hasS = true,
      hasU = true,
      csrViewMode = viewMode
    )

  private def bool(v: Bool): Boolean = v.peek().litValue == 1
  private def big(v: UInt): BigInt = v.peek().litValue

  private def setup(dut: ysyx_26030103_CSRPrivilegePmuHarness): Unit = {
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
    dut.io.enable.poke(false.B)
    dut.io.retireCommit.poke(false.B)
    dut.io.minstretRetireCommit.poke(false.B)
    dut.io.isCsrrw.poke(false.B)
    dut.io.isCsrrs.poke(false.B)
    dut.io.isCsrrc.poke(false.B)
    dut.io.isEcall.poke(false.B)
    dut.io.isEbreak.poke(false.B)
    dut.io.isMret.poke(false.B)
    dut.io.isSret.poke(false.B)
    dut.io.isSfenceVma.poke(false.B)
    dut.io.csrAddr.poke(0.U(12.W))
    dut.io.rs1.poke(0.U(5.W))
    dut.io.rs1Data.poke(0.U(dut.xlen.W))
    dut.io.pc.poke(0.U(dut.xlen.W))
    dut.io.trapTval.poke(0.U(dut.xlen.W))
    dut.io.trapTvalValid.poke(false.B)
    dut.io.trapValid.poke(false.B)
    dut.io.trapCause.poke(0.U(32.W))
    dut.io.memTrap.poke(false.B)
    dut.io.interrupt.poke(false.B)
    dut.io.timerInterrupt.poke(false.B)
    dut.io.mtime.poke(0.U(64.W))
    dut.io.fpStateDirtyCommit.poke(false.B)
    dut.io.pmuEvents.poke(0.U)
  }

  private def read(
      dut: ysyx_26030103_CSRPrivilegePmuHarness,
      addr: Int
  ): BigInt = {
    dut.io.csrAddr.poke(addr.U(12.W))
    dut.io.rdata.peek().litValue
  }

  private def write(
      dut: ysyx_26030103_CSRPrivilegePmuHarness,
      addr: Int,
      data: BigInt
  ): Unit = {
    dut.io.csrAddr.poke(addr.U(12.W))
    dut.io.rs1.poke(1.U(5.W))
    dut.io.rs1Data.poke(data.U(dut.xlen.W))
    dut.io.enable.poke(true.B)
    dut.io.isCsrrw.poke(true.B)
    dut.clock.step()
    dut.io.enable.poke(false.B)
    dut.io.isCsrrw.poke(false.B)
  }

  private def mret(dut: ysyx_26030103_CSRPrivilegePmuHarness): Unit = {
    dut.io.enable.poke(true.B)
    dut.io.isMret.poke(true.B)
    dut.clock.step()
    dut.io.enable.poke(false.B)
    dut.io.isMret.poke(false.B)
  }

  private def enterS(
      dut: ysyx_26030103_CSRPrivilegePmuHarness,
      epc: BigInt = BigInt("80001000", 16)
  ): Unit = {
    write(dut, MSTATUS, BigInt(0x800))
    write(dut, MEPC, epc)
    mret(dut)
    big(dut.io.privilege) should be(BigInt(1))
  }

  private def overflowHpm3(
      dut: ysyx_26030103_CSRPrivilegePmuHarness
  ): Unit = {
    write(dut, MHPMEVENT3, BigInt(ysyx_26030103_PMUEventId.Instructions))
    if (dut.xlen == 64) {
      write(dut, MHPMCOUNTER3, (BigInt(1) << 64) - 1)
    } else {
      write(dut, MHPMCOUNTER3, BigInt("ffffffff", 16))
      write(dut, MHPMCOUNTER3H, BigInt("ffffffff", 16))
    }
    dut.io.pmuEvents.poke((BigInt(1) << ysyx_26030103_PMUEventId.Instructions)
      .U(ysyx_26030103_PMUEventId.BusWidth.W))
    dut.clock.step()
    dut.io.pmuEvents.poke(0.U)
  }

  "minstret" should "不把写计数器指令自身的退休再计入" in {
    simulate(new ysyx_26030103_CSRPrivilegePmuHarness(
      64, profile64(), useF = false)) { dut =>
      setup(dut)
      write(dut, MINSTRET, BigInt(0x100))
      read(dut, MINSTRET) should be(BigInt(0x100))

      // 写 minstret 的指令自身退休：RetireCommit 有脉冲，但过滤事件为 0。
      dut.io.retireCommit.poke(true.B)
      dut.io.minstretRetireCommit.poke(false.B)
      dut.clock.step()
      dut.io.retireCommit.poke(false.B)
      read(dut, MINSTRET) should be(BigInt(0x100))

      // 下一条普通指令退休才自增。
      dut.io.retireCommit.poke(true.B)
      dut.io.minstretRetireCommit.poke(true.B)
      dut.clock.step()
      dut.io.retireCommit.poke(false.B)
      dut.io.minstretRetireCommit.poke(false.B)
      read(dut, MINSTRET) should be(BigInt(0x101))
    }
  }

  it should "在提交拍给出写 minstret 标记" in {
    simulate(new ysyx_26030103_CSRPrivilegePmuHarness(
      64, profile64(), useF = false)) { dut =>
      setup(dut)
      dut.io.csrAddr.poke(MINSTRET.U(12.W))
      dut.io.rs1.poke(1.U(5.W))
      dut.io.rs1Data.poke(0x100.U(64.W))
      dut.io.enable.poke(true.B)
      dut.io.isCsrrw.poke(true.B)
      bool(dut.io.minstretWriteCommit) should be(true)
      dut.clock.step()
      dut.io.enable.poke(false.B)
      dut.io.isCsrrw.poke(false.B)
      read(dut, MINSTRET) should be(BigInt(0x100))

      // 普通 CSR 写不产生 minstret 标记。
      dut.io.csrAddr.poke(MSTATUS.U(12.W))
      dut.io.rs1Data.poke(0.U(64.W))
      dut.io.enable.poke(true.B)
      dut.io.isCsrrw.poke(true.B)
      bool(dut.io.minstretWriteCommit) should be(false)
      dut.clock.step()
      dut.io.enable.poke(false.B)
      dut.io.isCsrrw.poke(false.B)
    }
  }

  "independent sstatus" should "让 FS/SD 跟随 mstatus 与 FP dirty" in {
    simulate(new ysyx_26030103_CSRPrivilegePmuHarness(
      64, profile64(viewMode = "independent"), useF = true)) { dut =>
      setup(dut)
      write(dut, MSTATUS, BigInt(0x6000)) // FS=Dirty
      val mstatusFs = read(dut, MSTATUS)
      ((mstatusFs >> 13) & 3) should be(BigInt(3))
      val sstatusFs = read(dut, SSTATUS)
      ((sstatusFs >> 13) & 3) should be(BigInt(3))
      ((sstatusFs >> 63) & 1) should be(BigInt(1)) // SD

      // sstatus 写 FS=0 也必须清 mstatus.FS。
      write(dut, SSTATUS, BigInt(0))
      ((read(dut, MSTATUS) >> 13) & 3) should be(BigInt(0))
      ((read(dut, SSTATUS) >> 13) & 3) should be(BigInt(0))

      // FP 指令在 WBU 退休置 Dirty 后，sstatus.FS/SD 同步可见。
      dut.io.fpStateDirtyCommit.poke(true.B)
      dut.clock.step()
      dut.io.fpStateDirtyCommit.poke(false.B)
      val dirtySstatus = read(dut, SSTATUS)
      ((dirtySstatus >> 13) & 3) should be(BigInt(3))
      ((dirtySstatus >> 63) & 1) should be(BigInt(1))
    }
  }

  "independent SRET" should "被中断抢占时保留 trap-entry 的 mstatus" in {
    simulate(new ysyx_26030103_CSRPrivilegePmuHarness(
      64, profile64(viewMode = "independent"), useF = false)) { dut =>
      setup(dut)
      enterS(dut)
      // MIE=1, MPP=S, MPRV=1；随后 SRET 与 M timer 中断同拍。
      write(dut, MSTATUS, BigInt("00020808", 16))
      write(dut, MIE, BigInt(0x80)) // MTIE
      dut.io.timerInterrupt.poke(true.B)
      dut.io.enable.poke(true.B)
      dut.io.isSret.poke(true.B)
      bool(dut.io.trapCommit) should be(true)
      big(dut.io.committedCause) should be(BigInt("80000007", 16))
      dut.clock.step()
      dut.io.enable.poke(false.B)
      dut.io.isSret.poke(false.B)
      dut.io.timerInterrupt.poke(false.B)

      val mstatus = read(dut, MSTATUS)
      // trap 入口不应被 SRET 的“清 MPRV”覆盖：MPRV 保持 1，
      // MIE 保存到 MPIE 后清 0。
      ((mstatus >> 17) & 1) should be(BigInt(1))
      ((mstatus >> 3) & 1) should be(BigInt(0))
      ((mstatus >> 7) & 1) should be(BigInt(1))
      ((mstatus >> 11) & 3) should be(BigInt(1))
    }
  }

  "independent sip/mip" should "共享同一个 SSIP pending 位" in {
    simulate(new ysyx_26030103_CSRPrivilegePmuHarness(
      64, profile64(viewMode = "independent"), useF = false)) { dut =>
      setup(dut)
      // 未委托时写 mip.SSIP，sip 必须立即看到同一位。
      write(dut, MIP, BigInt(0x2))
      ((read(dut, MIP) >> 1) & 1) should be(BigInt(1))
      ((read(dut, SIP) >> 1) & 1) should be(BigInt(1))

      // sip 写 0 必须清除 mip.SSIP。
      write(dut, SIP, BigInt(0))
      ((read(dut, MIP) >> 1) & 1) should be(BigInt(0))
      ((read(dut, SIP) >> 1) & 1) should be(BigInt(0))

      // sip 写 1 必须出现在 mip 中（mideleg 未委托）。
      write(dut, MIDELEG, BigInt(0))
      write(dut, SIP, BigInt(0x2))
      ((read(dut, MIP) >> 1) & 1) should be(BigInt(1))
      ((read(dut, SIP) >> 1) & 1) should be(BigInt(1))
    }
  }

  "RV64 mstatus/sstatus" should "只为已实现的 S/U 报告 SXL/UXL" in {
    val cases = Seq(
      ((true, true), BigInt(0xa), BigInt(0x2)),
      ((true, false), BigInt(0x8), BigInt(0x0)),
      ((false, false), BigInt(0x0), BigInt(0x0)),
      ((false, true), BigInt(0x2), BigInt(0x0))
    )
    for (((hasS, hasU), expectedMstatus, expectedSstatus) <- cases) {
      val p = profile64(hasS = hasS, hasU = hasU)
      simulate(new ysyx_26030103_CSRPrivilegePmuHarness(
        64, p, useF = false)) { dut =>
        setup(dut)
        (read(dut, MSTATUS) >> 32) should be(expectedMstatus)
        (read(dut, SSTATUS) >> 32) should be(expectedSstatus)
      }
    }
  }

  it should "即使 F 打开，未实现 U 时 sstatus.UXL/SD 高半区仍正确" in {
    simulate(new ysyx_26030103_CSRPrivilegePmuHarness(
      64, profile64(hasS = true, hasU = false), useF = true)) { dut =>
      setup(dut)
      (read(dut, MSTATUS) >> 32) should be(BigInt(0x8))
      // sstatus 只有 UXL（不存在，读 0）和 FS/SD；FS=0 时高 32 位为 0。
      (read(dut, SSTATUS) >> 32) should be(BigInt(0))
    }
  }

  "mip.LCOFI" should "在软件写 0 时清除 Sscofpmf overflow" in {
    simulate(new ysyx_26030103_CSRPrivilegePmuHarness(
      64, profile64(), useF = false, pmuEnable = true)) { dut =>
      setup(dut)
      overflowHpm3(dut)
      ((read(dut, MIP) >> 13) & 1) should be(BigInt(1))
      ((read(dut, SCOUNTOVF) >> 3) & 1) should be(BigInt(1))

      write(dut, MIP, BigInt(0))
      ((read(dut, MIP) >> 13) & 1) should be(BigInt(0))
      ((read(dut, SCOUNTOVF) >> 3) & 1) should be(BigInt(0))
      ((read(dut, MHPMEVENT3) >> 63) & 1) should be(BigInt(0))
    }
  }

  "RV32 scountovf" should "在 S 模式受 mcounteren 门控并保留 M 模式全视图" in {
    // mcounteren[3]=1：S 模式仍看到 hpmcounter3 的 overflow 位。
    simulate(new ysyx_26030103_CSRPrivilegePmuHarness(
      32, profile32(), useF = false, pmuEnable = true)) { dut =>
      setup(dut)
      overflowHpm3(dut)
      ((read(dut, SCOUNTOVF) >> 3) & 1) should be(BigInt(1))
      write(dut, MCOUNTEREN, BigInt(0x8))
      enterS(dut)
      ((read(dut, SCOUNTOVF) >> 3) & 1) should be(BigInt(1))
    }

    // mcounteren[3]=0：S 模式读 scountovf 必须为 0，M 模式仍是全视图。
    simulate(new ysyx_26030103_CSRPrivilegePmuHarness(
      32, profile32(), useF = false, pmuEnable = true)) { dut =>
      setup(dut)
      overflowHpm3(dut)
      write(dut, MCOUNTEREN, BigInt(0))
      ((read(dut, SCOUNTOVF) >> 3) & 1) should be(BigInt(1))
      enterS(dut)
      ((read(dut, SCOUNTOVF) >> 3) & 1) should be(BigInt(0))
    }
  }
}
