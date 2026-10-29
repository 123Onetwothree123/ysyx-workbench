package ysyx_26030103.audit

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.ysyx_26030103.common._
import _root_.ysyx_26030103.config._
import _root_.ysyx_26030103.exu.ysyx_26030103_EXU
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** EXU 级 trap 审计：instruction-address-misaligned 的 mtval 和
  * SFENCE.VMA 提交时的年轻取指冲刷。
  *
  * 直接实例化 EXU，只驱动指令级输入，不经过 SoC/IDU。
  */
class ysyx_26030103_ExuTrapAuditTest extends AnyFlatSpec with Matchers {
  private def config: ysyx_26030103_NPCConfig =
    ysyx_26030103_NPCConfig(
      XLEN = 64,
      AddressWidth = 64,
      UseM = true,
      UseA = true,
      // 关闭 C，保证 IALIGN=32，JALR 目标 bit1=1 会触发 misaligned。
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

  private def setup(dut: ysyx_26030103_EXU): Unit = {
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
    dut.io.in.valid.poke(false.B)
    dut.io.in.bits.poke(0.U.asTypeOf(new ysyx_26030103_IDUMessage(64)))
    dut.io.out.ready.poke(false.B)
    dut.io.Interrupt.poke(false.B)
    dut.io.TimerInterrupt.poke(false.B)
    dut.io.IdleIrqPC.poke(0.U(64.W))
    dut.io.MEMBusy.poke(false.B)
    dut.io.PipelineBusy.poke(false.B)
    dut.io.MemTrapCommit.poke(false.B)
    dut.io.MemTrapCause.poke(0.U(32.W))
    dut.io.MemTrapPC.poke(0.U(64.W))
    dut.io.MemTrapTval.poke(0.U(64.W))
    dut.io.Mtime.poke(0.U(64.W))
    dut.io.FPStateDirtyCommit.poke(false.B)
  }

  "EXU trap" should "JALR 目标 bit1=1 时 CSRStateTval 记录目标地址" in {
    simulate(new ysyx_26030103_EXU(config)) { dut =>
      setup(dut)
      dut.io.in.bits.pc.poke(BigInt("0000000080000000", 16).U(64.W))
      dut.io.in.bits.snpc.poke(BigInt("0000000080000004", 16).U(64.W))
      dut.io.in.bits.ALUCtrl.poke(ysyx_26030103_ALUFunction.ADD)
      dut.io.in.bits.ALU_A.poke(BigInt("0000000000001000", 16).U(64.W))
      dut.io.in.bits.ALU_B.poke(2.U(64.W))
      dut.io.in.bits.IsJalr.poke(true.B)
      dut.io.in.bits.Rd.poke(1.U(5.W))
      dut.io.in.bits.RegisterWrite.poke(true.B)
      dut.io.in.bits.WBSelect.poke(2.U(2.W))
      dut.io.in.bits.IsBranch.poke(false.B)
      dut.io.in.bits.IsJal.poke(false.B)
      dut.io.in.bits.Immediate.poke(0.U(64.W))
      dut.io.in.valid.poke(true.B)
      dut.io.out.ready.poke(true.B)

      bool(dut.io.in.ready) should be(true)
      bool(dut.io.TrapCommit) should be(true)
      bool(dut.io.ExceptionTaken) should be(true)
      big(dut.io.TrapCause) should be(BigInt(0))
      big(dut.io.TrapPC) should be(BigInt("0000000080000000", 16))
      // JALR 目标 0x1002 的 bit1=1，mtval 必须是目标地址而不是 PC。
      big(dut.io.CSRStateTval) should be(BigInt("0000000000001002", 16))

      // 再检查寄存器实际写入值：EXU 的 CSR 读口对应 0x343(mtval)。
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.io.in.bits.CSRAddress.poke(0x343.U(12.W))
      big(dut.io.out.bits.CSRReadData) should be(
        BigInt("0000000000001002", 16))
    }
  }

  it should "SFENCE.VMA 提交时 FlushIF/Redirect 到 snpc" in {
    simulate(new ysyx_26030103_EXU(config)) { dut =>
      setup(dut)
      dut.io.in.bits.pc.poke(BigInt("0000000080000040", 16).U(64.W))
      dut.io.in.bits.snpc.poke(BigInt("0000000080000044", 16).U(64.W))
      dut.io.in.bits.Instruction.poke("h12000073".U(32.W))
      dut.io.in.bits.IsSfenceVma.poke(true.B)
      dut.io.in.bits.RegisterWrite.poke(false.B)
      dut.io.in.bits.MemoryValid.poke(false.B)
      dut.io.in.bits.IsBranch.poke(false.B)
      dut.io.in.bits.IsJal.poke(false.B)
      dut.io.in.bits.IsJalr.poke(false.B)
      dut.io.in.bits.IsFence.poke(false.B)
      dut.io.in.bits.IsFenceI.poke(false.B)
      dut.io.in.bits.ALUCtrl.poke(ysyx_26030103_ALUFunction.ADD)
      dut.io.in.bits.ALU_A.poke(0.U(64.W))
      dut.io.in.bits.ALU_B.poke(0.U(64.W))
      dut.io.in.valid.poke(true.B)
      dut.io.out.ready.poke(true.B)

      bool(dut.io.in.ready) should be(true)
      bool(dut.io.CSRCommit) should be(true)
      bool(dut.io.CSRFlushTLB) should be(true)
      // SFENCE 不是分支/跳转，Redirect 只能来自新增的 SFENCE 条件。
      bool(dut.io.Redirect) should be(true)
      bool(dut.io.FlushIF) should be(true)
      big(dut.io.RedirectTarget) should be(
        BigInt("0000000080000044", 16))
    }
  }
}
