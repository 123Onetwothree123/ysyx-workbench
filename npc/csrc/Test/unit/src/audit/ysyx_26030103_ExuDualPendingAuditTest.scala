package ysyx_26030103.audit

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.ysyx_26030103.common._
import _root_.ysyx_26030103.config._
import _root_.ysyx_26030103.exu.ysyx_26030103_EXU
import _root_.ysyx_26030103.fpu._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** EXU MDU/FPU 双 pending 审计。
  *
  * MDU 队列与 FPU 各自保存一条在途指令，但 EXU 只有一个输出槽位。
  * 两者必须互斥：MDU 未完成时 FPU 不得发射，FPU 未完成时 MDU 不得
  * 入队。否则 `io.out.valid` 选择 MDU 结果，而 `ActiveInst` 被
  * `FpPending` 覆盖，PC/Rd/写回数据会来自不同指令。
  */
class ysyx_26030103_ExuDualPendingAuditTest
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

  private val One = BigInt("3ff0000000000000", 16) // 1.0 (D)
  private val Two = BigInt("4000000000000000", 16) // 2.0 (D)
  private val Three = BigInt("4008000000000000", 16) // 3.0 (D)

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

  private def setMul(
      dut: ysyx_26030103_EXU,
      pc: BigInt,
      lhs: BigInt = 6,
      rhs: BigInt = 7,
      rd: Int = 5
  ): Unit = {
    dut.io.in.bits.poke(0.U.asTypeOf(new ysyx_26030103_IDUMessage(64)))
    dut.io.in.bits.pc.poke(pc.U(64.W))
    dut.io.in.bits.snpc.poke((pc + 4).U(64.W))
    dut.io.in.bits.Instruction.poke(0x02f302b3L.U(32.W)) // mul x5,x6,x7
    dut.io.in.bits.IsMDU.poke(true.B)
    dut.io.in.bits.MDUOp.poke(ysyx_26030103_MDUOp.MUL)
    dut.io.in.bits.ALU_A.poke(lhs.U(64.W))
    dut.io.in.bits.ALU_B.poke(rhs.U(64.W))
    dut.io.in.bits.Rd.poke(rd.U(5.W))
    dut.io.in.bits.RegisterWrite.poke(true.B)
    dut.io.in.bits.WBSelect.poke(0.U(2.W))
  }

  private def setFadd(
      dut: ysyx_26030103_EXU,
      pc: BigInt,
      rd: Int = 1
  ): Unit = {
    dut.io.in.bits.poke(0.U.asTypeOf(new ysyx_26030103_IDUMessage(64)))
    dut.io.in.bits.pc.poke(pc.U(64.W))
    dut.io.in.bits.snpc.poke((pc + 4).U(64.W))
    dut.io.in.bits.Instruction.poke(0x0020f0d3L.U(32.W)) // fadd.d f1,f1,f2
    dut.io.in.bits.IsFP.poke(true.B)
    dut.io.in.bits.IsFPU.poke(true.B)
    dut.io.in.bits.FpOp.poke(ysyx_26030103_FpuOp.FAdd)
    dut.io.in.bits.FpFmt.poke(ysyx_26030103_FpuFmt.D)
    dut.io.in.bits.FpSrcFmt.poke(ysyx_26030103_FpuFmt.D)
    dut.io.in.bits.FpRm.poke(ysyx_26030103_FpuRm.RNE)
    dut.io.in.bits.FpRs1.poke(One.U(64.W))
    dut.io.in.bits.FpRs2.poke(Two.U(64.W))
    dut.io.in.bits.Rd.poke(rd.U(5.W))
    dut.io.in.bits.FPRegisterWrite.poke(true.B)
    dut.io.in.bits.WBSelect.poke(0.U(2.W))
  }

  "EXU dual pending" should "在 MDU 未完成时拒绝发射 FPU" in {
    simulate(new ysyx_26030103_EXU(config)) { dut =>
      setup(dut)
      dut.io.out.ready.poke(true.B)

      // 1. 发射一条 MUL；ShiftAdd 后端需要多拍，PendingMDU 会保持。
      setMul(dut, BigInt("0000000080000000", 16))
      dut.io.in.valid.poke(true.B)
      bool(dut.io.in.ready) should be(true)
      dut.clock.step()

      // 2. MDU 在途时把 FADD 放到输入口。FPU 必须被阻塞。
      setFadd(dut, BigInt("0000000080000010", 16))
      dut.io.in.valid.poke(true.B)
      withClue("MDU pending but EXU accepted an FPU instruction: ") {
        bool(dut.io.in.ready) should be(false)
      }

      // 3. MUL 完成后 FADD 必须能继续发射，不能把 FPU 永久堵死。
      //    等待期间 MUL 结果可能已经退休，必须同时采样，不能漏掉。
      var mulRetired = false
      var faddRetired = false
      var waitCycles = 0
      while (!bool(dut.io.in.ready) && waitCycles < 300) {
        if (bool(dut.io.out.valid) && bool(dut.io.out.ready) &&
            bool(dut.io.out.bits.RegisterWrite) &&
            big(dut.io.out.bits.Rd) == 5) {
          big(dut.io.out.bits.ALUResult) should be(BigInt(42))
          mulRetired = true
        }
        dut.clock.step()
        waitCycles += 1
      }
      withClue(s"FPU stayed blocked for $waitCycles cycles: ") {
        bool(dut.io.in.ready) should be(true)
      }
      dut.clock.step() // 接受 FADD
      dut.io.in.valid.poke(false.B)

      var cycles = 0
      while ((!mulRetired || !faddRetired) && cycles < 300) {
        if (bool(dut.io.out.valid) && bool(dut.io.out.ready)) {
          if (bool(dut.io.out.bits.RegisterWrite) &&
              big(dut.io.out.bits.Rd) == 5) {
            big(dut.io.out.bits.ALUResult) should be(BigInt(42))
            mulRetired = true
          }
          if (bool(dut.io.out.bits.FPRegisterWrite) &&
              big(dut.io.out.bits.Rd) == 1) {
            big(dut.io.out.bits.FPWriteData) should be(Three)
            faddRetired = true
          }
        }
        dut.clock.step()
        cycles += 1
      }
      mulRetired should be(true)
      faddRetired should be(true)
    }
  }

  it should "在 FpPending 期间不得把同一条 MDU 指令重复入队" in {
    simulate(new ysyx_26030103_EXU(config)) { dut =>
      setup(dut)
      // 先让 FADD 完成计算但用 out.ready=0 保持 FpPending，制造稳定的
      // “FPU 在途”窗口。
      dut.io.out.ready.poke(false.B)
      setFadd(dut, BigInt("0000000080000100", 16))
      dut.io.in.valid.poke(true.B)
      bool(dut.io.in.ready) should be(true)
      dut.clock.step() // FADD 被接受，FpPending=1

      // FpPending 期间持续把同一条 MUL 放在输入口。
      setMul(dut, BigInt("0000000080000110", 16))
      dut.io.in.valid.poke(true.B)
      bool(dut.io.in.ready) should be(false)
      for (_ <- 0 until 4) {
        withClue("MDU enqueued a request while FpPending=1: ") {
          bool(dut.io.PerfMDUReq) should be(false)
        }
        dut.clock.step()
      }

      // 放开输出后，FADD 先退休；MUL 只能入队/退休一次。MUL 握手后
      // 必须撤销 valid，否则按 Decoupled 语义会被当作下一条新指令。
      dut.io.out.ready.poke(true.B)
      var mduRetires = 0
      var faddRetires = 0
      for (_ <- 0 until 300) {
        if (bool(dut.io.out.valid) && bool(dut.io.out.ready)) {
          if (big(dut.io.out.bits.ALUResult) == BigInt(42)) {
            mduRetires += 1
          }
          if (bool(dut.io.out.bits.FPRegisterWrite) &&
              big(dut.io.out.bits.Rd) == 1 &&
              big(dut.io.out.bits.FPWriteData) == Three) {
            faddRetires += 1
          }
        }
        val inputFire = bool(dut.io.in.valid) && bool(dut.io.in.ready)
        dut.clock.step()
        if (inputFire) { dut.io.in.valid.poke(false.B) }
      }
      withClue(s"MUL retired $mduRetires times: ") {
        mduRetires should be(1)
      }
      withClue(s"FADD retired $faddRetires times: ") {
        faddRetires should be(1)
      }
    }
  }
}
