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
  idu.io.in.bits.pc := 0.U
  idu.io.in.bits.ExceptionValid := false.B
  idu.io.in.bits.ExceptionCause := 0.U
  idu.io.in.bits.AccessFaultResp := 0.U
  idu.io.in.bits.pred_taken := false.B
  idu.io.in.bits.pred_target := 0.U
  idu.io.out.ready := true.B
  idu.io.ReadDATA1 := 0.U
  idu.io.ReadDATA2 := 0.U
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
  io.exceptionValid := idu.io.out.bits.ExceptionValid
  io.exceptionCause := idu.io.out.bits.ExceptionCause
}

/** HAS_U 对 IDU 特权检查的裁剪测试。 */
class ysyx_26030103_IDUPrivilegeTest extends AnyFlatSpec with Matchers {
  // csrrs x1, 0xc00, x0：读用户级 cycle CSR。
  private val CsrrsCycle = 0xc00020f3L
  // csrrs x1, 0x100, x0：读 S 级 sstatus。
  private val CsrrsSstatus = 0x100020f3L

  private def config(hasU: Boolean): ysyx_26030103_NPCConfig =
    ysyx_26030103_NPCConfig(MMU = ysyx_26030103_MMUProfile(
      hasU = hasU,
      csrEnabled = Set(0x100, 0x300, 0xc00)
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
}
