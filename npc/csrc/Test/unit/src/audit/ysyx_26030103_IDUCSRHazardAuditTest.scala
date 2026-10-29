package ysyx_26030103.audit

import chisel3._
import chisel3.util._
import chisel3.simulator.EphemeralSimulator._
import _root_.ysyx_26030103.common._
import _root_.ysyx_26030103.config._
import _root_.ysyx_26030103.exu.ysyx_26030103_EXU
import _root_.ysyx_26030103.idu.ysyx_26030103_IDU
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable.ArrayBuffer

/** IDU + EXU 组合测试顶层：保留真实的两级流水寄存器。
  *
  * IFU -> IDU 与 IDU -> EXU 都用与顶层相同的 ysyx_26030103_StageConnect，
  * 因此“CSR 指令在 EXU 提交的同一拍，IDU 正在译码下一条指令”的时序
  * 与真实流水一致。IDU 的 Mstatus/Frm/Privilege/counteren 直接来自 EXU
  * 内 CSR 单元的实时输出，和顶层连线一致。
  */
class ysyx_26030103_IDUCSRHazardHarness(config: ysyx_26030103_NPCConfig)
    extends Module {
  private val XLEN = config.XLEN
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ysyx_26030103_IFUMessage(XLEN)))
    val out = Decoupled(new ysyx_26030103_EXUMessage(XLEN))
    val gprRead = Input(UInt(XLEN.W))
    val fpr1 = Input(UInt(64.W))
    val fpr2 = Input(UInt(64.W))
    val fpr3 = Input(UInt(64.W))
    val interrupt = Input(Bool())
    val timerInterrupt = Input(Bool())
    val exuInValid = Output(Bool())
    val exuInBits = Output(new ysyx_26030103_IDUMessage(XLEN))
    val trapCommit = Output(Bool())
    val trapPC = Output(UInt(XLEN.W))
    val trapCause = Output(UInt(32.W))
    val trapTarget = Output(UInt(XLEN.W))
    val exceptionTaken = Output(Bool())
    val privilege = Output(UInt(2.W))
    val csrFrm = Output(UInt(3.W))
    val csrFcsr = Output(UInt(8.W))
    val currentMstatus = Output(UInt(XLEN.W))
    val retireCommit = Output(Bool())
  })

  val idu = Module(new ysyx_26030103_IDU(config))
  val exu = Module(new ysyx_26030103_EXU(config))
  val flush = exu.io.FlushIF

  // 与顶层一致：IFU->IDU 被任何重定向/异常冲刷；IDU->EXU 只保留
  // 当前 EXU 项（flushCurrent=false，和顶层的 lsu.FlushIDEX 缺省一致）。
  ysyx_26030103_StageConnect(io.in, idu.io.in, flush)
  ysyx_26030103_StageConnect(idu.io.out, exu.io.in, flush, false.B)

  // IDU 的整数寄存器读口在本测试中返回同一常量，足以覆盖 CSR 指令。
  idu.io.ReadDATA1 := io.gprRead
  idu.io.ReadDATA2 := io.gprRead
  idu.io.ReadFPR1 := io.fpr1
  idu.io.ReadFPR2 := io.fpr2
  idu.io.ReadFPR3 := io.fpr3

  // 关闭未使用的转发/冒险端口。
  idu.io.ex_valid := exu.io.HazardValid
  idu.io.ex_rd := exu.io.HazardRd
  idu.io.ex_regWrite := exu.io.HazardRegWrite
  idu.io.ex_fwd_ready := exu.io.FwdReady
  idu.io.ex_fwd_data := exu.io.FwdData
  idu.io.ex_mdu_hidden_writes := exu.io.HazardMDUHiddenWrites
  idu.io.ex_memop := false.B
  idu.io.me_valid := false.B
  idu.io.me_rd := 0.U
  idu.io.me_regWrite := false.B
  idu.io.me_memop := false.B
  idu.io.me_fwd_ready := false.B
  idu.io.me_fwd_data := 0.U
  idu.io.me2_valid := false.B
  idu.io.me2_rd := 0.U
  idu.io.me2_regWrite := false.B
  idu.io.me2_memop := false.B
  idu.io.me2_fwd_ready := false.B
  idu.io.me2_fwd_data := 0.U
  idu.io.wb_valid := false.B
  idu.io.wb_rd := 0.U
  idu.io.wb_regWrite := false.B
  idu.io.wb_fwd_data := 0.U
  idu.io.ex_fp_valid := exu.io.HazardFPValid
  idu.io.ex_fp_rd := exu.io.HazardFPRd
  idu.io.ex_fp_regWrite := exu.io.HazardFPRegWrite
  idu.io.ex_fp_fwd_ready := exu.io.FwdFPReady
  idu.io.ex_fp_fwd_data := exu.io.FwdFPData
  idu.io.me_fp_valid := false.B
  idu.io.me_fp_rd := 0.U
  idu.io.me_fp_regWrite := false.B
  idu.io.me_fp_fwd_ready := false.B
  idu.io.me_fp_fwd_data := 0.U
  idu.io.me2_fp_valid := false.B
  idu.io.me2_fp_rd := 0.U
  idu.io.me2_fp_regWrite := false.B
  idu.io.me2_fp_fwd_ready := false.B
  idu.io.me2_fp_fwd_data := 0.U
  idu.io.wb_fp_valid := false.B
  idu.io.wb_fp_rd := 0.U
  idu.io.wb_fp_regWrite := false.B
  idu.io.wb_fp_fwd_data := 0.U
  idu.io.pipeline_mode := true.B
  idu.io.mmu_stall := false.B

  // CSR 状态回送译码级（与顶层连线一致）。
  idu.io.Privilege := exu.io.CSRPrivilege
  idu.io.Mstatus := exu.io.CSRCurrentMstatus
  idu.io.Frm := exu.io.CSRFrm
  idu.io.Mcounteren := exu.io.Mcounteren
  idu.io.Scounteren := exu.io.Scounteren

  // EXU 的其余环境输入。
  exu.io.Interrupt := io.interrupt
  exu.io.TimerInterrupt := io.timerInterrupt
  exu.io.IdleIrqPC := 0.U
  exu.io.MEMBusy := false.B
  exu.io.PipelineBusy := false.B
  exu.io.MemTrapCommit := false.B
  exu.io.MemTrapCause := 0.U
  exu.io.MemTrapPC := 0.U
  exu.io.MemTrapTval := 0.U
  exu.io.Mtime := 0.U
  exu.io.FPStateDirtyCommit := false.B
  // 用一个寄存器模拟顶层 WBU 阶段的退休事件（寄存器隔开 EXU 提交）。
  val retirePipe = RegNext(io.out.fire && io.out.bits.Retire, false.B)
  exu.io.RetireCommit := retirePipe

  io.out <> exu.io.out
  io.exuInValid := exu.io.in.valid
  io.exuInBits := exu.io.in.bits
  io.trapCommit := exu.io.TrapCommit
  io.trapPC := exu.io.TrapPC
  io.trapCause := exu.io.TrapCause
  io.trapTarget := exu.io.TrapTarget
  io.exceptionTaken := exu.io.ExceptionTaken
  io.privilege := exu.io.CSRPrivilege
  io.csrFrm := exu.io.CSRFrm
  io.csrFcsr := exu.io.CSRFcsr
  io.currentMstatus := exu.io.CSRCurrentMstatus
  io.retireCommit := retirePipe
}

object ysyx_26030103_IDUCSRHazardInsn {
  def csrrwi(csr: Int, imm: Int): BigInt =
    (BigInt(csr) << 20) | (BigInt(imm) << 15) | (BigInt(5) << 12) | 0x73
  def csrrwX0(csr: Int, rs1: Int): BigInt =
    (BigInt(csr) << 20) | (BigInt(rs1) << 19) | (1 << 12) | 0x73
  def csrrs(rd: Int, csr: Int): BigInt =
    (BigInt(csr) << 20) | (2 << 12) | (BigInt(rd) << 7) | 0x73
  val Nop: BigInt = 0x00000013L
  val Ecall: BigInt = 0x00000073L
  val Ebreak: BigInt = 0x00100073L
  val Mret: BigInt = 0x30200073L
  val Sret: BigInt = 0x10200073L
  // fadd.s f2, f1, f1, rm=<rm>
  def faddS(rm: Int): BigInt =
    (1 << 20) | (1 << 15) | (BigInt(rm) << 12) | (2 << 7) | 0x53
}

/** CSR 写后紧邻指令的译码冒险审计。
  *
  * 规范要求后面的指令观察到前面 CSR 写的新值；如果 IDU 在 CSR 写提交
  * 的同一拍仍用旧 mstatus/frm 译码，会漏报/误报非法指令或使用旧舍入模式。
  */
class ysyx_26030103_IDUCSRHazardAuditTest
    extends AnyFlatSpec with Matchers {
  import ysyx_26030103_IDUCSRHazardInsn._

  private val MSTATUS = 0x300
  private val FRM = 0x002
  private val FCSR = 0x003
  private val MINSTRET = 0xb02

  private val OneF = BigInt("ffffffff3f800000", 16)
  private val TwoF = BigInt("ffffffff40000000", 16)

  private def config: ysyx_26030103_NPCConfig =
    ysyx_26030103_NPCConfig(
      XLEN = 64,
      AddressWidth = 64,
      UseM = true,
      UseA = true,
      UseC = false,
      UseF = true,
      UseD = true,
      FPUImpl = ysyx_26030103_FPUImpl.HardFloat,
      FPULen = 64,
      MMU = ysyx_26030103_MMUProfile(
        enabled = false,
        xlen = 64,
        hasS = true,
        hasU = true,
        pteSizeBytes = 8,
        pteFormat = "riscv64",
        pteConfig = ysyx_26030103_PteConfig(pteBits = 64, ppnHigh = 53)
      )
    )

  private def bool(value: Bool): Boolean = value.peek().litValue == 1
  private def big(value: UInt): BigInt = value.peek().litValue

  private final case class RetireEvent(
      pc: BigInt,
      instruction: BigInt,
      retire: Boolean
  )
  private final case class TrapEvent(pc: BigInt, cause: BigInt, target: BigInt)

  /** 小驱动：按真实 Decoupled 语义逐条送入指令，并在每个时钟沿前采样
    * EXU 输出/陷阱事件（避免漏拍）。
    */
  private final class Driver(dut: ysyx_26030103_IDUCSRHazardHarness) {
    val retires = ArrayBuffer.empty[RetireEvent]
    val traps = ArrayBuffer.empty[TrapEvent]
    private var exuFpRmSnapshots = ArrayBuffer.empty[(BigInt, Int)]

    def setup(): Unit = {
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.in.valid.poke(false.B)
      dut.io.in.bits.poke(0.U.asTypeOf(new ysyx_26030103_IFUMessage(64)))
      dut.io.out.ready.poke(true.B)
      dut.io.gprRead.poke(0.U)
      dut.io.fpr1.poke(OneF.U(64.W))
      dut.io.fpr2.poke(TwoF.U(64.W))
      dut.io.fpr3.poke(TwoF.U(64.W))
      dut.io.interrupt.poke(false.B)
      dut.io.timerInterrupt.poke(false.B)
    }

    def sample(): Unit = {
      if (bool(dut.io.out.valid) && bool(dut.io.out.ready)) {
        retires += RetireEvent(
          big(dut.io.out.bits.pc),
          big(dut.io.out.bits.Instruction),
          bool(dut.io.out.bits.Retire)
        )
      }
      if (bool(dut.io.trapCommit)) {
        traps += TrapEvent(
          big(dut.io.trapPC),
          big(dut.io.trapCause),
          big(dut.io.trapTarget)
        )
      }
      if (bool(dut.io.exuInValid) && bool(dut.io.exuInBits.IsFPU)) {
        exuFpRmSnapshots +=
          ((big(dut.io.exuInBits.Instruction), big(dut.io.exuInBits.FpRm).toInt))
      }
    }

    def step(n: Int = 1): Unit = {
      for (_ <- 0 until n) {
        sample()
        dut.clock.step()
      }
    }

    def feed(instruction: BigInt): Unit = {
      dut.io.in.bits.Instruction.poke(instruction.U(32.W))
      dut.io.in.bits.IsCompressed.poke(false.B)
      dut.io.in.valid.poke(true.B)
      var guard = 0
      while (!bool(dut.io.in.ready) && guard < 400) {
        step()
        guard += 1
      }
      withClue(s"IDU input never became ready while feeding 0x${instruction.toString(16)}: ") {
        guard should be < 400
      }
      step()
      dut.io.in.valid.poke(false.B)
    }

    def feedAt(pc: BigInt, instruction: BigInt): Unit = {
      dut.io.in.bits.pc.poke(pc.U(64.W))
      feed(instruction)
    }

    def feedNops(n: Int): Unit = {
      for (_ <- 0 until n) { feedAt(0, Nop) }
    }

    def waitCycles(n: Int): Unit = step(n)

    def fpRmsFor(instruction: BigInt): Seq[Int] =
      exuFpRmSnapshots.filter(_._1 == instruction).map(_._2).toSeq
  }

  "CSR -> 紧随浮点指令" should "csrw mstatus 清 FS 后 fadd.s 必须是非法指令" in {
    simulate(new ysyx_26030103_IDUCSRHazardHarness(config)) { dut =>
      val d = new Driver(dut)
      d.setup()
      // 先打开 mstatus.FS（FS=3），并等待写值真正落到 CSR。
      // CSRRWI 的 5 位立即数只能写 CSR 低 5 位，无法到达 FS[14:13]，
      // 因此这里用 csrrw 写 GPR x1（测试 harness 的读口常量）。
      dut.io.gprRead.poke(BigInt(0x6000).U)
      d.feedAt(0x80000000L, csrrwX0(MSTATUS, 1))
      d.step(2)
      dut.io.gprRead.poke(0.U)
      d.feedNops(4)
      big(dut.io.currentMstatus) & BigInt(0x6000) should be(BigInt(0x6000))
      // 此前的 fadd 应该合法（确认 FS 打开有效且测试环境正确）。
      val legalPc = BigInt("0000000080000100", 16)
      d.feedAt(legalPc, faddS(0))
      d.feedNops(3)
      d.traps.filter(_.pc == legalPc) should be(empty)

      // 清 FS 后紧接 fadd.s：第二条必须 cause=2 illegal instruction。
      val csrPc = BigInt("0000000080000200", 16)
      val faddPc = BigInt("0000000080000204", 16)
      d.feedAt(csrPc, csrrwi(MSTATUS, 0))
      d.feedAt(faddPc, faddS(0))
      d.feedNops(6)

      withClue(
        s"FS=0 后的 fadd.s 未触发非法指令；traps=${d.traps.mkString(",")} "
      ) {
        d.traps.exists(t => t.pc == faddPc && t.cause == BigInt(2)) should be(true)
      }
      // 该 fadd 不得退休/产生浮点写回。
      d.retires.exists(r => r.pc == faddPc && r.retire) should be(false)
    }
  }

  it should "csrw mstatus 打开 FS 后 fadd.s 不应再误报非法指令" in {
    simulate(new ysyx_26030103_IDUCSRHazardHarness(config)) { dut =>
      val d = new Driver(dut)
      d.setup()
      // 复位后 FS=0，任何 F 指令非法。
      val beforePc = BigInt("0000000080000300", 16)
      d.feedAt(beforePc, faddS(0))
      d.feedNops(3)
      d.traps.exists(t => t.pc == beforePc && t.cause == BigInt(2)) should be(true)

      // 写 FS=3 后紧接 fadd.s：必须合法。
      val csrPc = BigInt("0000000080000310", 16)
      val faddPc = BigInt("0000000080000314", 16)
      // 保持 gprRead 覆盖 csrw 与紧邻 fadd 两拍，确保 csrw 的源操作数
      // 在 IDU 译码当拍仍然有效，同时不插入额外空拍破坏相邻关系。
      dut.io.gprRead.poke(BigInt(0x6000).U)
      d.feedAt(csrPc, csrrwX0(MSTATUS, 1))
      d.feedAt(faddPc, faddS(0))
      d.feedNops(8)
      dut.io.gprRead.poke(0.U)
      withClue(
        s"FS=3 后的 fadd.s 被误报非法；traps=${d.traps.mkString(",")} "
      ) {
        d.traps.exists(t => t.pc == faddPc) should be(false)
      }
      d.retires.exists(r => r.pc == faddPc && r.retire) should be(true)
    }
  }

  it should "csrw frm 后紧接 DYN 浮点指令必须使用新 frm" in {
    simulate(new ysyx_26030103_IDUCSRHazardHarness(config)) { dut =>
      val d = new Driver(dut)
      d.setup()
      // 打开 FS 并确认复位 frm=0。
      dut.io.gprRead.poke(BigInt(0x6000).U)
      d.feedAt(0x80000000L, csrrwX0(MSTATUS, 1))
      d.step(2)
      dut.io.gprRead.poke(0.U)
      d.feedNops(4)
      big(dut.io.csrFrm) should be(BigInt(0))

      // 写 frm=1(RTZ)，紧接 rm=DYN 的 fadd.s。
      val csrPc = BigInt("0000000080000400", 16)
      d.feedAt(csrPc, csrrwi(FRM, 1))
      val dynPc = BigInt("0000000080000404", 16)
      d.feedAt(dynPc, faddS(7))
      d.feedNops(6)

      big(dut.io.csrFrm) should be(BigInt(1))
      val rms = d.fpRmsFor(faddS(7))
      withClue(s"fadd 进入 EXU 的 FpRm 快照=$rms（期望 1，即新 frm）： ") {
        rms should contain(1)
      }
      rms should not contain 0
    }
  }

  it should "csrw fcsr 的 frm 字段同样对紧邻 DYN 指令生效" in {
    simulate(new ysyx_26030103_IDUCSRHazardHarness(config)) { dut =>
      val d = new Driver(dut)
      d.setup()
      dut.io.gprRead.poke(BigInt(0x6000).U)
      d.feedAt(0x80000000L, csrrwX0(MSTATUS, 1))
      d.step(2)
      dut.io.gprRead.poke(0.U)
      d.feedNops(4)
      big(dut.io.csrFrm) should be(BigInt(0))
      // fcsr = {frm=2, fflags=0} = 0x40；CSRRWI 立即数只有 5 位，
      // 0x40 必须通过 csrrw 从 GPR 写入。保持 gprRead 覆盖紧邻的
      // DYN fadd 译码拍，避免测试驱动本身破坏相邻关系。
      dut.io.gprRead.poke(BigInt(0x40).U)
      d.feedAt(0x80000010L, csrrwX0(FCSR, 1))
      val dynPc = BigInt("0000000080000014", 16)
      d.feedAt(dynPc, faddS(7))
      d.feedNops(6)
      dut.io.gprRead.poke(0.U)
      big(dut.io.csrFrm) should be(BigInt(2))
      val rms = d.fpRmsFor(faddS(7))
      // 复位 frm=0，csrw fcsr 写入 frm=2 后紧邻的 DYN 必须用 2。
      withClue(s"FCSR 写后 fadd 的 FpRm 快照=$rms： ") {
        rms should contain(2)
      }
      rms should not contain 0
    }
  }

  "minstret 端到端" should "普通 ALU 退休逐条计数，CSR 读回可见" in {
    simulate(new ysyx_26030103_IDUCSRHazardHarness(config)) { dut =>
      val d = new Driver(dut)
      d.setup()
      // minstret = 0，然后 8 条 addi + csrr x5, minstret。
      d.feedAt(0x80000100L, csrrwi(MINSTRET, 0))
      d.feedNops(4)
      val addi = 0x00128293L // addi x5,x5,1
      for (i <- 0 until 8) { d.feedAt((0x80000110L + i * 4), addi) }
      val readPc = BigInt("0000000080000140", 16)
      d.feedAt(readPc, csrrs(5, MINSTRET))
      d.step(6)
      val reads = d.retires.filter(_.pc == readPc)
      withClue(s"minstret 读回指令没有退休: ${d.retires.mkString(",")}") {
        reads.size should be(1)
      }
      // 读 CSR 时，8 条 addi + 1 条 csrrwi 自身已退休，共 9。
      // （csrr 自己尚未到 WBU 退休沿。）
      val values = dut.io.out.bits.CSRReadData // 组合口在最后一拍可能已变
      // 用显式 CSR 读快照：重新执行一次 csrr 前先排空，再按每拍采样。
      // 这里直接检查 EXU 输出中该 csrr 的 CSRReadData 采样值。
      d.waitCycles(6)
      // 通过再次读取确认计数已稳定且 >= 9。
      val readPc2 = BigInt("0000000080000150", 16)
      d.feedAt(readPc2, csrrs(5, MINSTRET))
      var observed = Option.empty[BigInt]
      var guard = 0
      while (observed.isEmpty && guard < 40) {
        if (bool(dut.io.out.valid) && bool(dut.io.out.ready) &&
            big(dut.io.out.bits.pc) == readPc2) {
          observed = Some(big(dut.io.out.bits.CSRReadData))
        }
        d.step()
        guard += 1
      }
      withClue("没有观察到 minstret 的 CSR 读回值: ") {
        observed should not be empty
      }
      // csrrwi(minstret,0) 自身退休 +1，随后 4 条 nop + 8 条 addi
      // + 第一条 csrr 各退休 +1：第二条 csrr 读到的值为 14。
      observed.get should be(BigInt(14))
    }
  }
}
