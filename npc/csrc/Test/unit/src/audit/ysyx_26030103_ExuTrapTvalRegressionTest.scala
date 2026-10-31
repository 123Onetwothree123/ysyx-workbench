package ysyx_26030103.audit

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.ysyx_26030103.common._
import _root_.ysyx_26030103.config._
import _root_.ysyx_26030103.exu.ysyx_26030103_EXU
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** EXU trap tval 回归。
  *
  * 取指异常（instruction access fault, cause=1）随指令标记进入 EXU 时，
  * 该指令可能恰好是 load/store 形状（MemoryValid=true）。此时 mtval 的
  * 语义仍是故障 PC，而不是 ALU 计算出的访存地址。
  *
  * 当前 ActiveTrapTval 先看 MemoryValid 再回退 pc，会把 ALU 结果写进
  * CSRStateTval；本测试用 LW 形状 + ExceptionCause=1 复现该错误。
  */
class ysyx_26030103_ExuTrapTvalRegressionTest
    extends AnyFlatSpec with Matchers {
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
  private def big(value: UInt): BigInt   = value.peek().litValue

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
    dut.io.RetireCommit.poke(false.B)
    dut.io.MEMBusy.poke(false.B)
    dut.io.PipelineBusy.poke(false.B)
    dut.io.MemTrapCommit.poke(false.B)
    dut.io.MemTrapCause.poke(0.U(32.W))
    dut.io.MemTrapPC.poke(0.U(64.W))
    dut.io.MemTrapTval.poke(0.U(64.W))
    dut.io.Mtime.poke(0.U(64.W))
    dut.io.FPStateDirtyCommit.poke(false.B)
  }

  "EXU trap tval" should "取指 access fault 落在 load 形状指令上时 mtval 使用故障 PC" in {
    simulate(new ysyx_26030103_EXU(config)) { dut =>
      setup(dut)
      dut.io.in.bits.poke(0.U.asTypeOf(new ysyx_26030103_IDUMessage(64)))
      dut.io.in.bits.pc.poke(BigInt("0000000080000000", 16).U(64.W))
      dut.io.in.bits.snpc.poke(BigInt("0000000080000004", 16).U(64.W))
      // LW x0, 0(x0)：指令形状带 MemoryValid，但上游已标记取指异常。
      dut.io.in.bits.Instruction.poke("h00002003".U(32.W))
      dut.io.in.bits.IsCompressed.poke(false.B)
      dut.io.in.bits.ExceptionValid.poke(true.B)
      dut.io.in.bits.ExceptionCause.poke(1.U(4.W)) // instruction access fault
      dut.io.in.bits.MemoryValid.poke(true.B)
      dut.io.in.bits.MemoryWrite.poke(false.B)
      dut.io.in.bits.WidthSelect.poke(2.U(2.W))
      dut.io.in.bits.LoadSigned.poke(true.B)
      dut.io.in.bits.ALUCtrl.poke(ysyx_26030103_ALUFunction.ADD)
      dut.io.in.bits.ALU_A.poke(BigInt("0000000080000010", 16).U(64.W))
      dut.io.in.bits.ALU_B.poke(0.U(64.W))
      dut.io.in.bits.Rd.poke(0.U(5.W))
      dut.io.in.bits.RegisterWrite.poke(false.B)
      dut.io.in.bits.IsBranch.poke(false.B)
      dut.io.in.bits.IsJal.poke(false.B)
      dut.io.in.bits.IsJalr.poke(false.B)
      dut.io.in.valid.poke(true.B)
      dut.io.out.ready.poke(true.B)

      bool(dut.io.in.ready) should be(true)
      bool(dut.io.TrapCommit) should be(true)
      big(dut.io.TrapCause) should be(BigInt(1))
      big(dut.io.TrapPC) should be(BigInt("0000000080000000", 16))
      // 关键断言：mtval 是故障 PC，不是 ALU 计算出的 0x80000010。
      big(dut.io.CSRStateTval) should be(BigInt("0000000080000000", 16))
    }
  }
}
