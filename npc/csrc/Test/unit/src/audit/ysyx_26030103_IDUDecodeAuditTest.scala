package ysyx_26030103.audit

import chisel3._
import chisel3.util._
import chisel3.simulator.EphemeralSimulator._
import _root_.ysyx_26030103.config._
import _root_.ysyx_26030103.idu.ysyx_26030103_IDU
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** 只驱动译码级输入的 IDU 审计顶层。 */
class ysyx_26030103_IDUDecodeAuditHarness(config: ysyx_26030103_NPCConfig)
    extends Module {
  private val XLEN = config.XLEN
  val io = IO(new Bundle {
    val instruction = Input(UInt(32.W))
    val isCompressed = Input(Bool())
    val privilege = Input(UInt(2.W))
    val mstatus = Input(UInt(XLEN.W))
    val frm = Input(UInt(3.W))
    val mcounteren = Input(UInt(32.W))
    val scounteren = Input(UInt(32.W))
    val exceptionValid = Output(Bool())
    val exceptionCause = Output(UInt(4.W))
    val isFP = Output(Bool())
    val fpRm = Output(UInt(3.W))
    val isAtomic = Output(Bool())
    val isSret = Output(Bool())
    val isSfenceVma = Output(Bool())
  })

  val idu = Module(new ysyx_26030103_IDU(config))
  idu.io.in.valid := true.B
  idu.io.in.bits.Instruction := io.instruction
  idu.io.in.bits.IsCompressed := io.isCompressed
  idu.io.in.bits.pc := 0.U
  idu.io.in.bits.ExceptionValid := false.B
  idu.io.in.bits.ExceptionCause := 0.U
  idu.io.in.bits.AccessFaultResp := 0.U
  idu.io.in.bits.pred_taken := false.B
  idu.io.in.bits.pred_target := 0.U
  idu.io.out.ready := true.B
  idu.io.ReadDATA1 := 0.U
  idu.io.ReadDATA2 := 0.U
  idu.io.ReadFPR1 := 0.U
  idu.io.ReadFPR2 := 0.U
  idu.io.ReadFPR3 := 0.U
  idu.io.Frm := io.frm
  idu.io.ex_fp_valid := false.B
  idu.io.ex_fp_rd := 0.U
  idu.io.ex_fp_regWrite := false.B
  idu.io.ex_fp_fwd_ready := false.B
  idu.io.ex_fp_fwd_data := 0.U
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
  idu.io.ex_valid := false.B
  idu.io.ex_rd := 0.U
  idu.io.ex_regWrite := false.B
  idu.io.wb_valid := false.B
  idu.io.wb_rd := 0.U
  idu.io.wb_regWrite := false.B
  idu.io.ex_memop := false.B
  idu.io.ex_fwd_ready := false.B
  idu.io.ex_fwd_data := 0.U
  idu.io.ex_mdu_hidden_writes := 0.U
  idu.io.wb_fwd_data := 0.U
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
  idu.io.pipeline_mode := false.B
  idu.io.mmu_stall := false.B
  idu.io.Privilege := io.privilege
  idu.io.Mstatus := io.mstatus
  idu.io.Mcounteren := io.mcounteren
  idu.io.Scounteren := io.scounteren

  io.exceptionValid := idu.io.out.bits.ExceptionValid
  io.exceptionCause := idu.io.out.bits.ExceptionCause
  io.isFP := idu.io.out.bits.IsFP
  io.fpRm := idu.io.out.bits.FpRm
  io.isAtomic := idu.io.out.bits.IsAtomic
  io.isSret := idu.io.out.bits.IsSret
  io.isSfenceVma := idu.io.out.bits.IsSfenceVma
}

/** IDU 译码审计：保留编码、特权陷门与 F/D 状态门控。
  *
  * 这些用例只检查译码结果，不依赖流水线时序；期望值来自 RISC-V
  * 非特权/特权规范以及现有 NEMU REF 的相同编码规则。
  */
class ysyx_26030103_IDUDecodeAuditTest
    extends AnyFlatSpec with Matchers {
  private def config(
      xlen: Int = 64,
      useA: Boolean = true,
      useF: Boolean = true,
      mmuEnabled: Boolean = false,
      hasS: Boolean = true,
      hasU: Boolean = true
  ): ysyx_26030103_NPCConfig =
    ysyx_26030103_NPCConfig(
      XLEN = xlen,
      AddressWidth = xlen,
      UseM = true,
      UseA = useA,
      UseC = false,
      UseF = useF,
      UseD = useF,
      FPUImpl = ysyx_26030103_FPUImpl.HardFloat,
      FPULen = 64,
      MMU = ysyx_26030103_MMUProfile(
        enabled = mmuEnabled,
        xlen = xlen,
        hasS = hasS,
        hasU = hasU,
        pteSizeBytes = if (xlen == 64) 8 else 4,
        pteFormat = if (xlen == 64) "riscv64" else "riscv32",
        pteConfig = ysyx_26030103_PteConfig(
          pteBits = if (xlen == 64) 64 else 32,
          ppnHigh = if (xlen == 64) 53 else 21
        )
      )
    )

  private def bool(v: Bool): Boolean = v.peek().litValue == 1
  private def big(v: UInt): BigInt = v.peek().litValue

  private def setup(
      dut: ysyx_26030103_IDUDecodeAuditHarness,
      mstatus: BigInt = 0,
      privilege: Int = 3,
      frm: Int = 0
  ): Unit = {
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
    dut.io.instruction.poke(0.U)
    dut.io.isCompressed.poke(false.B)
    dut.io.privilege.poke(privilege.U)
    dut.io.mstatus.poke(mstatus.U)
    dut.io.frm.poke(frm.U)
    dut.io.mcounteren.poke("hffffffff".U)
    dut.io.scounteren.poke("hffffffff".U)
  }

  private def lr(funct3: Int, rd: Int, rs1: Int, rs2: Int): BigInt =
    (BigInt(0x02) << 27) | (BigInt(rs2) << 20) | (BigInt(rs1) << 15) |
      (BigInt(funct3) << 12) | (BigInt(rd) << 7) | 0x2f

  private def csrrs(rd: Int, csr: Int): BigInt =
    (BigInt(csr) << 20) | (2 << 12) | (BigInt(rd) << 7) | 0x73

  private val Sret = BigInt(0x10200073L)
  private val SfenceVma = BigInt(0x12000073L)
  private val Wfi = BigInt(0x10500073L)
  private val FsDirty = BigInt(0x6000)

  private def faddS(rm: Int): BigInt =
    (1 << 20) | (1 << 15) | (BigInt(rm) << 12) | (2 << 7) | 0x53

  "IDU A extension" should "把 rs2 非零的 LR.W 判为非法指令" in {
    simulate(new ysyx_26030103_IDUDecodeAuditHarness(config())) { dut =>
      setup(dut)
      dut.io.instruction.poke(lr(funct3 = 2, rd = 1, rs1 = 2, rs2 = 0).U)
      bool(dut.io.exceptionValid) should be(false)

      // LR.W 的 rs2 字段是保留域，必须为 0（NEMU REF 的 lr_w 模式也
      // 明确写成 00010 ?? 00000）。
      dut.io.instruction.poke(lr(funct3 = 2, rd = 1, rs1 = 2, rs2 = 3).U)
      bool(dut.io.exceptionValid) should be(true)
      big(dut.io.exceptionCause) should be(BigInt(2))
    }
  }

  it should "把 rs2 非零的 LR.D 判为非法指令" in {
    simulate(new ysyx_26030103_IDUDecodeAuditHarness(config())) { dut =>
      setup(dut)
      dut.io.instruction.poke(lr(funct3 = 3, rd = 1, rs1 = 2, rs2 = 0).U)
      bool(dut.io.exceptionValid) should be(false)

      dut.io.instruction.poke(lr(funct3 = 3, rd = 1, rs1 = 2, rs2 = 4).U)
      bool(dut.io.exceptionValid) should be(true)
      big(dut.io.exceptionCause) should be(BigInt(2))
    }
  }

  "IDU S-mode traps" should "在 S 模式分别执行 TVM/TSR/TW 陷门" in {
    simulate(new ysyx_26030103_IDUDecodeAuditHarness(config())) { dut =>
      // mstatus.TVM(20)/TW(21)/TSR(22)。
      setup(dut, mstatus = BigInt(0x00100000), privilege = 1)
      dut.io.instruction.poke(SfenceVma.U)
      bool(dut.io.exceptionValid) should be(true)
      big(dut.io.exceptionCause) should be(BigInt(2))

      setup(dut, mstatus = BigInt(0x00400000), privilege = 1)
      dut.io.instruction.poke(Sret.U)
      bool(dut.io.exceptionValid) should be(true)
      big(dut.io.exceptionCause) should be(BigInt(2))

      setup(dut, mstatus = BigInt(0x00200000), privilege = 1)
      dut.io.instruction.poke(Wfi.U)
      bool(dut.io.exceptionValid) should be(true)
      big(dut.io.exceptionCause) should be(BigInt(2))

      // 同样的指令在 M 模式不受这些 S 模式陷门影响。
      setup(dut, mstatus = BigInt(0x00700000), privilege = 3)
      dut.io.instruction.poke(SfenceVma.U)
      bool(dut.io.exceptionValid) should be(false)
      dut.io.instruction.poke(Wfi.U)
      bool(dut.io.exceptionValid) should be(false)
      // SRET 在 M 模式是非法的（只能在 S 执行），但原因不是 TSR。
      dut.io.instruction.poke(Sret.U)
      bool(dut.io.exceptionValid) should be(true)
      big(dut.io.exceptionCause) should be(BigInt(2))
    }
  }

  "IDU F state" should "在 FS=Off 时拒绝浮点指令，FS!=Off 时放行" in {
    simulate(new ysyx_26030103_IDUDecodeAuditHarness(config())) { dut =>
      setup(dut, mstatus = 0, privilege = 3)
      dut.io.instruction.poke(faddS(0).U)
      bool(dut.io.exceptionValid) should be(true)
      big(dut.io.exceptionCause) should be(BigInt(2))

      setup(dut, mstatus = FsDirty, privilege = 3)
      dut.io.instruction.poke(faddS(0).U)
      bool(dut.io.exceptionValid) should be(false)
      bool(dut.io.isFP) should be(true)
    }
  }

  it should "拒绝保留的静态舍入模式和保留的动态 frm" in {
    simulate(new ysyx_26030103_IDUDecodeAuditHarness(config())) { dut =>
      setup(dut, mstatus = FsDirty, privilege = 3, frm = 0)
      // rm=5/6/7 是保留编码。
      dut.io.instruction.poke(faddS(5).U)
      bool(dut.io.exceptionValid) should be(true)
      big(dut.io.exceptionCause) should be(BigInt(2))

      // DYN 使用 CSR frm；frm=5 也是保留值。
      setup(dut, mstatus = FsDirty, privilege = 3, frm = 5)
      dut.io.instruction.poke(faddS(7).U)
      bool(dut.io.exceptionValid) should be(true)
      big(dut.io.exceptionCause) should be(BigInt(2))
    }
  }

  it should "把 FS=Off 时的 fflags/frm/fcsr 访问判为非法指令" in {
    simulate(new ysyx_26030103_IDUDecodeAuditHarness(config())) { dut =>
      setup(dut, mstatus = 0, privilege = 3)
      for (csr <- Seq(0x001, 0x002, 0x003)) {
        dut.io.instruction.poke(csrrs(1, csr).U)
        bool(dut.io.exceptionValid) should be(true)
        big(dut.io.exceptionCause) should be(BigInt(2))
      }

      setup(dut, mstatus = FsDirty, privilege = 3)
      for (csr <- Seq(0x001, 0x002, 0x003)) {
        dut.io.instruction.poke(csrrs(1, csr).U)
        bool(dut.io.exceptionValid) should be(false)
      }
    }
  }
}
