package ysyx_26030103.idu

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.ysyx_26030103.config.ysyx_26030103_NPCConfig
import _root_.ysyx_26030103.config.ysyx_26030103_MMUProfile
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** 只保留译码所需端口的 IDU 测试顶层。 */
class ysyx_26030103_IDUPrivilegeHarness(config: ysyx_26030103_NPCConfig)
    extends Module {
  val io = IO(new Bundle {
    val instruction = Input(UInt(32.W))
    val privilege = Input(UInt(2.W))
    val mstatus = Input(UInt(32.W))
    val exceptionValid = Output(Bool())
    val exceptionCause = Output(UInt(4.W))
  })
  val idu = Module(new ysyx_26030103_IDU(config))
  idu.io.in.valid := true.B
  idu.io.in.bits.Instruction := io.instruction
  idu.io.in.bits.IsCompressed := false.B
  idu.io.in.bits.pc := 0.U
  idu.io.in.bits.ExceptionValid := false.B
  idu.io.in.bits.ExceptionCause := 0.U
  idu.io.in.bits.AccessFaultResp := 0.U
  idu.io.in.bits.pred_taken := false.B
  idu.io.in.bits.pred_target := 0.U
  idu.io.out.ready := true.B
  idu.io.ReadDATA1 := 0.U
  idu.io.ReadDATA2 := 0.U
  // 本测试只检查整数 CSR 译码；浮点读口/转发/舍入保持无效。
  idu.io.ReadFPR1 := 0.U
  idu.io.ReadFPR2 := 0.U
  idu.io.ReadFPR3 := 0.U
  idu.io.Frm := 0.U
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
  idu.io.pipeline_mode := true.B
  idu.io.mmu_stall := false.B
  idu.io.Privilege := io.privilege
  idu.io.Mstatus := io.mstatus
  idu.io.Mcounteren := "hffffffff".U
  idu.io.Scounteren := "hffffffff".U
  io.exceptionValid := idu.io.out.bits.ExceptionValid
  io.exceptionCause := idu.io.out.bits.ExceptionCause
}

/** HAS_U 对 IDU 特权检查的裁剪测试。 */
class ysyx_26030103_IDUPrivilegeTest extends AnyFlatSpec with Matchers {
  // csrrs x1, 0xc00, x0：读用户级 cycle CSR。
  private val CsrrsCycle = 0xc00020f3L
  // csrrs x1, 0x100, x0：读 S 级 sstatus。
  private val CsrrsSstatus = 0x100020f3L
  // csrrs x1, 0x300, x0：读 M 级 mstatus。
  private val CsrrsMstatus = 0x300020f3L
  private val Sret = 0x10200073L
  private val SfenceVma = 0x12000073L
  private val Wfi = 0x10500073L
  // csrrs x1, <addr>, x0：只读访问。
  private def csrrs(addr: Int): Long =
    ((addr.toLong << 20) | (2L << 12) | (1L << 7) | 0x73L).toLong
  // csrrw x0, <addr>, x0：带写意图的访问。
  private def csrrw(addr: Int): Long =
    ((addr.toLong << 20) | (1L << 12) | 0x73L).toLong

  private def config(hasU: Boolean, hasS: Boolean = true): ysyx_26030103_NPCConfig =
    ysyx_26030103_NPCConfig(MMU = ysyx_26030103_MMUProfile(
      hasS = hasS,
      hasU = hasU,
      csrEnabled = Set(0x100, 0x300, 0x301, 0xc00,
        0xf11, 0xf12, 0xf13, 0xf14)
    ))

  private def bool(v: Bool): Boolean = v.peek().litValue == 1

  private def setup(dut: ysyx_26030103_IDUPrivilegeHarness): Unit = {
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
    dut.io.mstatus.poke(0.U)
  }

  "IDU" should "HAS_U=y 时 M 模式可访问 U 级 CSR" in {
    simulate(new ysyx_26030103_IDUPrivilegeHarness(config(hasU = true))) { dut =>
      setup(dut)
      dut.io.instruction.poke(CsrrsCycle.U)
      dut.io.privilege.poke(3.U)
      bool(dut.io.exceptionValid) should be(false)
    }
  }

  it should "HAS_U=n 时 U 级 CSR 被判为非法指令" in {
    simulate(new ysyx_26030103_IDUPrivilegeHarness(config(hasU = false))) { dut =>
      setup(dut)
      dut.io.instruction.poke(CsrrsCycle.U)
      dut.io.privilege.poke(3.U)
      bool(dut.io.exceptionValid) should be(true)
      dut.io.exceptionCause.peek().litValue should be(2)
    }
  }

  it should "HAS_U=n 时把异常出现的 0 级请求按 S 处理" in {
    // HAS_U=y 时 U 级不能读 S 级 CSR。
    simulate(new ysyx_26030103_IDUPrivilegeHarness(config(hasU = true))) { dut =>
      setup(dut)
      dut.io.instruction.poke(CsrrsSstatus.U)
      dut.io.privilege.poke(0.U)
      bool(dut.io.exceptionValid) should be(true)
    }
    // HAS_U=n 时 0 级不存在，防御性地按 S 检查，因此该访问合法。
    simulate(new ysyx_26030103_IDUPrivilegeHarness(config(hasU = false))) { dut =>
      setup(dut)
      dut.io.instruction.poke(CsrrsSstatus.U)
      dut.io.privilege.poke(0.U)
      bool(dut.io.exceptionValid) should be(false)
    }
  }

  it should "HAS_S=n/HAS_U=y 时 U 按真实特权级检查 CSR" in {
    simulate(new ysyx_26030103_IDUPrivilegeHarness(
      config(hasU = true, hasS = false)
    )) { dut =>
      setup(dut)

      // M 模式仍可访问 M 级 CSR。
      dut.io.instruction.poke(CsrrsMstatus.U)
      dut.io.privilege.poke(3.U)
      bool(dut.io.exceptionValid) should be(false)

      // U 模式访问 M 级 CSR 是非法指令（cause=2）。
      dut.io.privilege.poke(0.U)
      bool(dut.io.exceptionValid) should be(true)
      dut.io.exceptionCause.peek().litValue should be(2)

      // mcounteren 全开时 U 可以读用户计数器。
      dut.io.instruction.poke(CsrrsCycle.U)
      bool(dut.io.exceptionValid) should be(false)

      // S CSR 已从实现集合裁剪，U/M 访问都非法。
      dut.io.instruction.poke(CsrrsSstatus.U)
      bool(dut.io.exceptionValid) should be(true)
      dut.io.privilege.poke(3.U)
      bool(dut.io.exceptionValid) should be(true)

      // MRET 只能在 M 执行；U 执行 MRET 是非法指令，不能借机提权。
      dut.io.instruction.poke(0x30200073L.U)
      bool(dut.io.exceptionValid) should be(false)
      dut.io.privilege.poke(0.U)
      bool(dut.io.exceptionValid) should be(true)
      dut.io.exceptionCause.peek().litValue should be(2)
    }
  }

  it should "mhartid/mimpid 仅 M 模式可读，写意图与低特权级访问非法" in {
    simulate(new ysyx_26030103_IDUPrivilegeHarness(config(hasU = true))) { dut =>
      setup(dut)

      // M 模式读 identity CSR 合法。
      for (addr <- Seq(0xf11, 0xf12, 0xf13, 0xf14)) {
        dut.io.instruction.poke(csrrs(addr).U)
        dut.io.privilege.poke(3.U)
        bool(dut.io.exceptionValid) should be(false)
      }

      // 地址位 [9:8]=3 表示 M 级 CSR，S/U 访问必须产生非法指令。
      for (addr <- Seq(0xf13, 0xf14); priv <- Seq(1, 0)) {
        dut.io.instruction.poke(csrrs(addr).U)
        dut.io.privilege.poke(priv.U)
        bool(dut.io.exceptionValid) should be(true)
        dut.io.exceptionCause.peek().litValue should be(2)
      }

      // mhartid/mimpid/misa 均为只读；CSRRW（即使源为 x0）也非法。
      for (addr <- Seq(0x301, 0xf13, 0xf14)) {
        dut.io.instruction.poke(csrrw(addr).U)
        dut.io.privilege.poke(3.U)
        bool(dut.io.exceptionValid) should be(true)
        dut.io.exceptionCause.peek().litValue should be(2)
      }

      // misa 只读但 M 模式读取合法。
      dut.io.instruction.poke(csrrs(0x301).U)
      bool(dut.io.exceptionValid) should be(false)
    }
  }

  it should "identity CSR 即使未列入 CSR_ENABLED 也保持可访问" in {
    val base = config(hasU = true)
    val trimmed = base.copy(MMU = base.MMU.copy(csrEnabled = Set(0x300)))
    simulate(new ysyx_26030103_IDUPrivilegeHarness(trimmed)) { dut =>
      setup(dut)
      dut.io.privilege.poke(3.U)
      for (addr <- Seq(0xf11, 0xf12, 0xf13, 0xf14)) {
        dut.io.instruction.poke(csrrs(addr).U)
        bool(dut.io.exceptionValid) should be(false)
      }
    }
  }

  it should "HAS_S=n 时 SRET/SFENCE.VMA/S 级 CSR 都是非法指令" in {
    simulate(new ysyx_26030103_IDUPrivilegeHarness(
      config(hasU = false, hasS = false)
    )) { dut =>
      setup(dut)
      dut.io.privilege.poke(3.U)

      for (insn <- Seq(Sret, SfenceVma, CsrrsSstatus)) {
        dut.io.instruction.poke(insn.U)
        bool(dut.io.exceptionValid) should be(true)
        dut.io.exceptionCause.peek().litValue should be(2)
      }

      // M 级 CSR 和 WFI 仍合法。
      dut.io.instruction.poke(CsrrsMstatus.U)
      bool(dut.io.exceptionValid) should be(false)
      dut.io.instruction.poke(Wfi.U)
      bool(dut.io.exceptionValid) should be(false)
    }
  }
}
