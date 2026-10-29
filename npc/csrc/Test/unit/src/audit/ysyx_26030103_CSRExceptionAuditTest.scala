package ysyx_26030103.audit

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.ysyx_26030103.config._
import _root_.ysyx_26030103.exu.ysyx_26030103_CSR
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** 异常/中断矩阵与 minstret 审计顶层。
  *
  * 只例化 CSR 单元，所有 trap 入口都由显式输入驱动；这样可以直接
  * 检查 mcause/mepc/mtval、委托和 MRET/SRET 的架构语义，不依赖
  * MMU/LSU 的时序。
  */
class ysyx_26030103_CSRExceptionAuditHarness(
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
    val exceptionTaken = Output(Bool())
    val exceptionTarget = Output(UInt(64.W))
    val trapCommit = Output(Bool())
    val committedCause = Output(UInt(32.W))
    val privilege = Output(UInt(2.W))
    val stateMstatus = Output(UInt(64.W))
    val stateMepc = Output(UInt(64.W))
    val stateMcause = Output(UInt(64.W))
    val stateTval = Output(UInt(64.W))
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
  io.exceptionTaken := csr.io.ExceptionTaken
  io.exceptionTarget := csr.io.ExceptionTarget
  io.trapCommit := csr.io.TrapCommit
  io.committedCause := csr.io.CommittedCause
  io.privilege := csr.io.Privilege
  io.stateMstatus := csr.io.StateMstatus
  io.stateMepc := csr.io.StateMepc
  io.stateMcause := csr.io.StateMcause
  io.stateTval := csr.io.StateTval
}

class ysyx_26030103_CSRExceptionAuditTest
    extends AnyFlatSpec with Matchers {
  private val MSTATUS = 0x300
  private val MEDELEG = 0x302
  private val MIDELEG = 0x303
  private val MIE = 0x304
  private val MTVEC = 0x305
  private val MCOUNTEREN = 0x306
  private val MCOUNTINHIBIT = 0x320
  private val MEPC = 0x341
  private val MCAUSE = 0x342
  private val MTVAL = 0x343
  private val MINSTRET = 0xb02
  private val SSTATUS = 0x100
  private val SIE = 0x104
  private val STVEC = 0x105
  private val SEPC = 0x141
  private val SCAUSE = 0x142
  private val STVAL = 0x143
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

  private def bool(v: Bool): Boolean = v.peek().litValue == 1
  private def big(v: UInt): BigInt = v.peek().litValue

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

  private def peek(
      dut: ysyx_26030103_CSRExceptionAuditHarness,
      addr: Int
  ): BigInt = {
    dut.io.csrAddr.poke(addr.U)
    dut.io.enable.poke(false.B)
    dut.io.rdata.peek().litValue
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

  private def retirePulses(
      dut: ysyx_26030103_CSRExceptionAuditHarness,
      cycles: Int
  ): Unit = {
    dut.io.retireCommit.poke(true.B)
    for (_ <- 0 until cycles) { dut.clock.step() }
    dut.io.retireCommit.poke(false.B)
  }

  private def checkTrap(
      dut: ysyx_26030103_CSRExceptionAuditHarness,
      cause: Int,
      pc: BigInt,
      tval: BigInt,
      target: BigInt,
      label: String
  ): Unit = {
    withClue(label + " trapCommit: ") { bool(dut.io.trapCommit) should be(true) }
    withClue(label + " cause: ") {
      big(dut.io.committedCause) should be(BigInt(cause.toLong & 0xffffffffL))
    }
    withClue(label + " target: ") {
      big(dut.io.exceptionTarget) should be(target)
    }
    withClue(label + " mepc next-state: ") {
      big(dut.io.stateMepc) should be(pc & ~BigInt(3))
    }
    withClue(label + " mtval next-state: ") {
      big(dut.io.stateTval) should be(tval)
    }
  }

  private def takeTrap(
      dut: ysyx_26030103_CSRExceptionAuditHarness,
      cause: Int,
      pc: BigInt,
      tval: BigInt
  ): Unit = {
    dut.io.pc.poke(pc.U(64.W))
    dut.io.trapTval.poke(tval.U(64.W))
    dut.io.trapTvalValid.poke(true.B)
    dut.io.trapCause.poke(cause.U(32.W))
    dut.io.trapValid.poke(true.B)
    dut.io.enable.poke(true.B)
    dut.clock.step()
    dut.io.enable.poke(false.B)
    dut.io.trapValid.poke(false.B)
  }

  private def injectTrap(
      dut: ysyx_26030103_CSRExceptionAuditHarness,
      cause: Int,
      pc: BigInt,
      tval: BigInt,
      target: BigInt,
      label: String
  ): Unit = {
    dut.io.pc.poke(pc.U(64.W))
    dut.io.trapTval.poke(tval.U(64.W))
    dut.io.trapTvalValid.poke(true.B)
    dut.io.trapCause.poke(cause.U(32.W))
    dut.io.trapValid.poke(true.B)
    dut.io.enable.poke(true.B)
    checkTrap(dut, cause, pc, tval, target, label)
    dut.clock.step()
    dut.io.enable.poke(false.B)
    dut.io.trapValid.poke(false.B)
  }

  private def enterS(dut: ysyx_26030103_CSRExceptionAuditHarness,
      pc: BigInt, sstatusBits: BigInt = 0): Unit = {
    write(dut, MSTATUS, BigInt(0x800) | sstatusBits) // MPP=S
    write(dut, MEPC, pc)
    dut.io.enable.poke(true.B)
    dut.io.isMret.poke(true.B)
    bool(dut.io.exceptionTaken) should be(true)
    big(dut.io.exceptionTarget) should be(pc)
    dut.clock.step()
    dut.io.isMret.poke(false.B)
    dut.io.enable.poke(false.B)
    big(dut.io.privilege) should be(BigInt(1))
  }

  private def enterU(dut: ysyx_26030103_CSRExceptionAuditHarness,
      pc: BigInt): Unit = {
    write(dut, MSTATUS, 0) // MPP=U
    write(dut, MEPC, pc)
    dut.io.enable.poke(true.B)
    dut.io.isMret.poke(true.B)
    bool(dut.io.exceptionTaken) should be(true)
    big(dut.io.exceptionTarget) should be(pc)
    dut.clock.step()
    dut.io.isMret.poke(false.B)
    dut.io.enable.poke(false.B)
    big(dut.io.privilege) should be(BigInt(0))
  }

  private def ecall(dut: ysyx_26030103_CSRExceptionAuditHarness,
      pc: BigInt): Unit = {
    dut.io.pc.poke(pc.U(64.W))
    dut.io.enable.poke(true.B)
    dut.io.isEcall.poke(true.B)
    dut.clock.step()
    dut.io.isEcall.poke(false.B)
    dut.io.enable.poke(false.B)
  }

  "CSR exception matrix" should "记录 ecall/ebreak/非法指令的 mcause/mepc/mtval" in {
    simulate(new ysyx_26030103_CSRExceptionAuditHarness(profile)) { dut =>
      setup(dut)
      write(dut, MTVEC, BigInt("0000000080002000", 16))

      // ECALL from M: cause=11, mtval=0。
      val ecallPc = BigInt("0000000080001234", 16)
      dut.io.pc.poke(ecallPc.U)
      dut.io.enable.poke(true.B)
      dut.io.isEcall.poke(true.B)
      checkTrap(dut, 11, ecallPc, 0, BigInt("0000000080002000", 16), "M ecall")
      dut.clock.step()
      dut.io.isEcall.poke(false.B)
      dut.io.enable.poke(false.B)
      peek(dut, MCAUSE) should be(BigInt(11))
      peek(dut, MEPC) should be(ecallPc)
      peek(dut, MTVAL) should be(BigInt(0))

      // EBREAK: cause=3，mtval 记录故障 PC。
      setup(dut)
      write(dut, MTVEC, BigInt("0000000080002000", 16))
      val ebreakPc = BigInt("0000000080001240", 16)
      dut.io.pc.poke(ebreakPc.U)
      dut.io.enable.poke(true.B)
      dut.io.isEbreak.poke(true.B)
      checkTrap(dut, 3, ebreakPc, ebreakPc,
        BigInt("0000000080002000", 16), "M ebreak")
      dut.clock.step()
      dut.io.isEbreak.poke(false.B)
      dut.io.enable.poke(false.B)
      peek(dut, MCAUSE) should be(BigInt(3))
      peek(dut, MEPC) should be(ebreakPc)
      peek(dut, MTVAL) should be(ebreakPc)
    }
  }

  it should "把非法指令与取指 access fault 的 tval 原样写入 mtval" in {
    simulate(new ysyx_26030103_CSRExceptionAuditHarness(profile)) { dut =>
      setup(dut)
      write(dut, MTVEC, BigInt("0000000080002000", 16))
      val illegalPc = BigInt("0000000080001300", 16)
      val illegalInsn = BigInt("0000000000000000", 16)
      injectTrap(dut, 2, illegalPc, illegalInsn,
        BigInt("0000000080002000", 16), "illegal")
      peek(dut, MCAUSE) should be(BigInt(2))
      peek(dut, MEPC) should be(illegalPc)
      peek(dut, MTVAL) should be(illegalInsn)

      val faultPc = BigInt("0000000080001400", 16)
      injectTrap(dut, 1, faultPc, faultPc,
        BigInt("0000000080002000", 16), "fetch access fault")
      peek(dut, MCAUSE) should be(BigInt(1))
      peek(dut, MEPC) should be(faultPc)
      peek(dut, MTVAL) should be(faultPc)
    }
  }

  it should "按 cause 4/5/6/7 记录 misaligned/access fault 的 mtval" in {
    simulate(new ysyx_26030103_CSRExceptionAuditHarness(profile)) { dut =>
      setup(dut)
      write(dut, MTVEC, BigInt("0000000080002000", 16))
      // 4=load address misaligned, 5=load access fault,
      // 6=store/AMO address misaligned, 7=store/AMO access fault。
      for (cause <- Seq(4, 5, 6, 7)) {
        val pc = BigInt("0000000080003000", 16) + cause * 16
        val tval = BigInt("0000000080004000", 16) + cause * 16
        injectTrap(dut, cause, pc, tval,
          BigInt("0000000080002000", 16), s"cause $cause")
        peek(dut, MCAUSE) should be(BigInt(cause))
        peek(dut, MEPC) should be(pc)
        peek(dut, MTVAL) should be(tval)
      }
    }
  }

  "CSR delegation" should "把 S 模式的委托异常送入 stvec 并可由 SRET 返回" in {
    simulate(new ysyx_26030103_CSRExceptionAuditHarness(profile)) { dut =>
      setup(dut)
      write(dut, MTVEC, BigInt("0000000080002000", 16))
      write(dut, STVEC, BigInt("0000000080004000", 16))
      // 委托 load access fault(5) 与 illegal(2)。
      write(dut, MEDELEG, (BigInt(1) << 5) | (BigInt(1) << 2))
      // 进入 S 时 sstatus.SIE=1，便于检查 trap 的 SPP/SPIE/SIE。
      enterS(dut, BigInt("0000000080005000", 16), sstatusBits = BigInt(0x2))
      // 委托异常：写 sepc/scause/stval，mepc/mcause/mtval 不变。
      val faultPc = BigInt("0000000080005010", 16)
      val faultTval = BigInt("00000000dead0000", 16)
      dut.io.pc.poke(faultPc.U)
      dut.io.trapTval.poke(faultTval.U)
      dut.io.trapTvalValid.poke(true.B)
      dut.io.trapCause.poke(5.U)
      dut.io.trapValid.poke(true.B)
      dut.io.enable.poke(true.B)
      bool(dut.io.trapCommit) should be(true)
      big(dut.io.committedCause) should be(BigInt(5))
      big(dut.io.exceptionTarget) should be(BigInt("0000000080004000", 16))
      dut.clock.step()
      dut.io.trapValid.poke(false.B)
      dut.io.enable.poke(false.B)
      peek(dut, SCAUSE) should be(BigInt(5))
      peek(dut, SEPC) should be(faultPc)
      peek(dut, STVAL) should be(faultTval)
      // MRET 设置的 mepc 仍在；M 入口未被这次委托异常改写。
      peek(dut, MEPC) should be(BigInt("0000000080005000", 16))
      peek(dut, MCAUSE) should be(BigInt(0))
      ((peek(dut, MSTATUS) >> 8) & 1) should be(BigInt(1)) // SPP=S
      ((peek(dut, MSTATUS) >> 1) & 1) should be(BigInt(0)) // SIE=0
      ((peek(dut, MSTATUS) >> 5) & 1) should be(BigInt(1)) // SPIE=old SIE

      // SRET 返回 S 模式的 sepc。
      dut.io.enable.poke(true.B)
      dut.io.isSret.poke(true.B)
      bool(dut.io.exceptionTaken) should be(true)
      big(dut.io.exceptionTarget) should be(faultPc)
      dut.clock.step()
      dut.io.isSret.poke(false.B)
      dut.io.enable.poke(false.B)
      big(dut.io.privilege) should be(BigInt(1))
      ((peek(dut, MSTATUS) >> 8) & 1) should be(BigInt(0)) // SPP=U
      ((peek(dut, MSTATUS) >> 1) & 1) should be(BigInt(1)) // SIE=SPIE

      // 未委托的 instruction-address-misaligned(4) 必须回到 M。
      val misPc = BigInt("0000000080005020", 16)
      injectTrap(dut, 4, misPc, misPc,
        BigInt("0000000080002000", 16), "non-delegated misaligned")
      peek(dut, MCAUSE) should be(BigInt(4))
      peek(dut, MEPC) should be(misPc)
      peek(dut, SCAUSE) should be(BigInt(5))
      ((peek(dut, MSTATUS) >> 11) & 3) should be(BigInt(1)) // MPP=S
    }
  }

  it should "把 U 模式 ecall 按 cause=8 进入 M，并支持委托到 S" in {
    simulate(new ysyx_26030103_CSRExceptionAuditHarness(profile)) { dut =>
      setup(dut)
      write(dut, MTVEC, BigInt("0000000080002000", 16))
      enterU(dut, BigInt("0000000080006000", 16))
      val ecallPc = BigInt("0000000080006010", 16)
      ecall(dut, ecallPc)
      peek(dut, MCAUSE) should be(BigInt(8))
      peek(dut, MEPC) should be(ecallPc)
      peek(dut, MTVAL) should be(BigInt(0))
      ((peek(dut, MSTATUS) >> 11) & 3) should be(BigInt(0)) // MPP=U
      big(dut.io.privilege) should be(BigInt(3))
      // 仍在 M 模式时配置 S 委托，再 MRET 返回 U。
      write(dut, STVEC, BigInt("0000000080004000", 16))
      write(dut, MEDELEG, BigInt(1) << 8)
      dut.io.pc.poke(ecallPc.U)
      dut.io.enable.poke(true.B)
      dut.io.isMret.poke(true.B)
      big(dut.io.exceptionTarget) should be(ecallPc)
      dut.clock.step()
      dut.io.isMret.poke(false.B)
      dut.io.enable.poke(false.B)
      big(dut.io.privilege) should be(BigInt(0))

      // 委托 U ecall(cause=8) 到 S，SRET 后返回 U。
      val ecallPc2 = BigInt("0000000080006020", 16)
      dut.io.pc.poke(ecallPc2.U)
      dut.io.enable.poke(true.B)
      dut.io.isEcall.poke(true.B)
      bool(dut.io.trapCommit) should be(true)
      big(dut.io.committedCause) should be(BigInt(8))
      big(dut.io.exceptionTarget) should be(BigInt("0000000080004000", 16))
      dut.clock.step()
      dut.io.isEcall.poke(false.B)
      dut.io.enable.poke(false.B)
      peek(dut, SCAUSE) should be(BigInt(8))
      peek(dut, SEPC) should be(ecallPc2)
      peek(dut, STVAL) should be(BigInt(0))
      ((peek(dut, MSTATUS) >> 8) & 1) should be(BigInt(0)) // SPP=U
      dut.io.enable.poke(true.B)
      dut.io.isSret.poke(true.B)
      big(dut.io.exceptionTarget) should be(ecallPc2)
      dut.clock.step()
      dut.io.isSret.poke(false.B)
      dut.io.enable.poke(false.B)
      big(dut.io.privilege) should be(BigInt(0))
    }
  }

  "CSR interrupt matrix" should "覆盖 M timer、M external、S external 与 S 模式下的 M timer" in {
    // M timer: cause=0x80000007，直接进 mtvec。
    simulate(new ysyx_26030103_CSRExceptionAuditHarness(profile)) { dut =>
      setup(dut)
      write(dut, MTVEC, BigInt("0000000080002000", 16))
      write(dut, MSTATUS, BigInt(0x8)) // MIE
      write(dut, MIE, BigInt(0x80)) // MTIE
      val pc = BigInt("0000000080007000", 16)
      dut.io.pc.poke(pc.U)
      dut.io.timerInterrupt.poke(true.B)
      dut.io.enable.poke(true.B)
      checkTrap(dut, 0x80000007, pc, 0,
        BigInt("0000000080002000", 16), "M timer")
      dut.clock.step()
      dut.io.timerInterrupt.poke(false.B)
      dut.io.enable.poke(false.B)
      peek(dut, MCAUSE) should be(BigInt("8000000000000007", 16))
      peek(dut, MEPC) should be(pc)
      peek(dut, MTVAL) should be(BigInt(0))
    }

    // M external: 使用 hasSExternalIrq=false 的 profile，io.Interrupt 挂到
    // MEIP(bit11)，cause=0x8000000b。
    simulate(new ysyx_26030103_CSRExceptionAuditHarness(
      profile.copy(hasSExternalIrq = false))) { dut =>
      setup(dut)
      write(dut, MTVEC, BigInt("0000000080002000", 16))
      write(dut, MSTATUS, BigInt(0x8))
      write(dut, MIE, BigInt(0x800)) // MEIE
      val pc = BigInt("0000000080007100", 16)
      dut.io.pc.poke(pc.U)
      dut.io.interrupt.poke(true.B)
      dut.io.enable.poke(true.B)
      checkTrap(dut, 0x8000000b, pc, 0,
        BigInt("0000000080002000", 16), "M external")
      dut.clock.step()
      dut.io.interrupt.poke(false.B)
      dut.io.enable.poke(false.B)
      peek(dut, MCAUSE) should be(BigInt("800000000000000b", 16))
    }

    // M 模式下未委托的 S external（mip.SEIP/mie.SEIE，mideleg=0）
    // 仍由 M 处理，但 cause 必须是 9（SEI），不能被误报成 MSI(3)。
    simulate(new ysyx_26030103_CSRExceptionAuditHarness(profile)) { dut =>
      setup(dut)
      write(dut, MTVEC, BigInt("0000000080002000", 16))
      write(dut, MSTATUS, BigInt(0x8))
      write(dut, MIE, BigInt(0x200)) // SEIE
      write(dut, MIDELEG, 0)
      val pc = BigInt("0000000080007150", 16)
      dut.io.pc.poke(pc.U)
      dut.io.interrupt.poke(true.B)
      dut.io.enable.poke(true.B)
      checkTrap(dut, 0x80000009, pc, 0,
        BigInt("0000000080002000", 16), "M-mode non-delegated SEI")
      dut.clock.step()
      dut.io.interrupt.poke(false.B)
      dut.io.enable.poke(false.B)
      peek(dut, MCAUSE) should be(BigInt("8000000000000009", 16))
    }

    // S external: mideleg.SEIE + mie.SEIE + sstatus.SIE，进 stvec。
    simulate(new ysyx_26030103_CSRExceptionAuditHarness(profile)) { dut =>
      setup(dut)
      write(dut, MTVEC, BigInt("0000000080002000", 16))
      write(dut, STVEC, BigInt("0000000080004000", 16))
      write(dut, MIDELEG, BigInt(0x200))
      write(dut, MIE, BigInt(0x200))
      enterS(dut, BigInt("0000000080007200", 16), sstatusBits = BigInt(0x2))
      val pc = BigInt("0000000080007210", 16)
      dut.io.pc.poke(pc.U)
      dut.io.interrupt.poke(true.B)
      dut.io.enable.poke(true.B)
      bool(dut.io.trapCommit) should be(true)
      big(dut.io.committedCause) should be(BigInt("80000009", 16))
      big(dut.io.exceptionTarget) should be(BigInt("0000000080004000", 16))
      dut.clock.step()
      dut.io.interrupt.poke(false.B)
      dut.io.enable.poke(false.B)
      peek(dut, SCAUSE) should be(BigInt("8000000000000009", 16))
      peek(dut, SEPC) should be(pc)
      peek(dut, STVAL) should be(BigInt(0))
    }

    // mideleg 中对应 M 级中断的 bit3/7/11 是只读零；软件误写后不能
    // 把 M timer 从 MIntPending 中排除掉（NEMU REF 也不使用 mideleg
    // 屏蔽 M 级中断）。
    simulate(new ysyx_26030103_CSRExceptionAuditHarness(profile)) { dut =>
      setup(dut)
      write(dut, MTVEC, BigInt("0000000080002000", 16))
      write(dut, MSTATUS, BigInt(0x8))
      write(dut, MIE, BigInt(0x80))
      write(dut, MIDELEG, BigInt(0x80)) // 误写 MTIE 委托位
      val pc = BigInt("0000000080007400", 16)
      dut.io.pc.poke(pc.U)
      dut.io.timerInterrupt.poke(true.B)
      dut.io.enable.poke(true.B)
      checkTrap(dut, 0x80000007, pc, 0,
        BigInt("0000000080002000", 16), "mideleg M bit")
      dut.clock.step()
      dut.io.timerInterrupt.poke(false.B)
      dut.io.enable.poke(false.B)
      peek(dut, MCAUSE) should be(BigInt("8000000000000007", 16))
    }

    // 同时 pending 的 S 级 SSIP/STIP：规范优先级 SSI(1) > STI(5)。
    // NEMU REF 也按 bit1 先于 bit5 处理。
    simulate(new ysyx_26030103_CSRExceptionAuditHarness(profile)) { dut =>
      setup(dut)
      write(dut, MTVEC, BigInt("0000000080002000", 16))
      write(dut, STVEC, BigInt("0000000080004000", 16))
      write(dut, MIDELEG, BigInt(0x22))
      write(dut, MIE, BigInt(0x22))
      write(dut, MIP, BigInt(0x22)) // SSIP + STIP
      enterS(dut, BigInt("0000000080007250", 16), sstatusBits = BigInt(0x2))
      val pc = BigInt("0000000080007260", 16)
      dut.io.pc.poke(pc.U)
      dut.io.enable.poke(true.B)
      bool(dut.io.trapCommit) should be(true)
      big(dut.io.committedCause) should be(BigInt("80000001", 16))
      big(dut.io.exceptionTarget) should be(BigInt("0000000080004000", 16))
      dut.clock.step()
      dut.io.enable.poke(false.B)
      peek(dut, SCAUSE) should be(BigInt("8000000000000001", 16))
      peek(dut, SEPC) should be(pc)
    }

    // S 模式下未委托的 M timer 必须直接进入 M，且不受 MIE=0 影响。
    simulate(new ysyx_26030103_CSRExceptionAuditHarness(profile)) { dut =>
      setup(dut)
      write(dut, MTVEC, BigInt("0000000080002000", 16))
      write(dut, MIE, BigInt(0x80))
      enterS(dut, BigInt("0000000080007300", 16))
      val pc = BigInt("0000000080007310", 16)
      dut.io.pc.poke(pc.U)
      dut.io.timerInterrupt.poke(true.B)
      dut.io.enable.poke(true.B)
      checkTrap(dut, 0x80000007, pc, 0,
        BigInt("0000000080002000", 16), "S-mode M timer")
      dut.clock.step()
      dut.io.timerInterrupt.poke(false.B)
      dut.io.enable.poke(false.B)
      peek(dut, MCAUSE) should be(BigInt("8000000000000007", 16))
      peek(dut, MEPC) should be(pc)
    }
  }

  "minstret" should "只统计退休事件，异常/中断不计数，写口优先" in {
    simulate(new ysyx_26030103_CSRExceptionAuditHarness(profile)) { dut =>
      setup(dut)
      write(dut, MINSTRET, 0)
      peek(dut, MINSTRET) should be(BigInt(0))

      // 普通 ALU/load/store/MDU 在顶层都会以 RetireCommit 脉冲出现；
      // 这里用 5 个退休事件代表它们。
      retirePulses(dut, 5)
      peek(dut, MINSTRET) should be(BigInt(5))

      // 同步异常/中断只提交 trap，不退休指令。
      dut.io.pc.poke(BigInt("0000000080008000", 16).U)
      dut.io.enable.poke(true.B)
      dut.io.isEcall.poke(true.B)
      dut.clock.step()
      dut.io.isEcall.poke(false.B)
      dut.io.enable.poke(false.B)
      peek(dut, MINSTRET) should be(BigInt(5))

      write(dut, MSTATUS, BigInt(0x8))
      write(dut, MIE, BigInt(0x80))
      dut.io.pc.poke(BigInt("0000000080008010", 16).U)
      dut.io.timerInterrupt.poke(true.B)
      dut.io.enable.poke(true.B)
      dut.clock.step()
      dut.io.timerInterrupt.poke(false.B)
      dut.io.enable.poke(false.B)
      peek(dut, MINSTRET) should be(BigInt(5))

      // 写 minstret 后从写入值继续自增；写口优先于同拍退休。
      write(dut, MINSTRET, BigInt(0x100))
      peek(dut, MINSTRET) should be(BigInt(0x100))
      retirePulses(dut, 1)
      peek(dut, MINSTRET) should be(BigInt(0x101))

      // mcountinhibit.IR 停止计数，写 0 后恢复。
      write(dut, MCOUNTINHIBIT, BigInt(4))
      retirePulses(dut, 3)
      peek(dut, MINSTRET) should be(BigInt(0x101))
      write(dut, MCOUNTINHIBIT, 0)
      retirePulses(dut, 1)
      peek(dut, MINSTRET) should be(BigInt(0x102))
    }
  }
}
