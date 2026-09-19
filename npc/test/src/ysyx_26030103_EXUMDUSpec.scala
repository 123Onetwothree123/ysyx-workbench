package ysyx_26030103.test

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import _root_.ysyx_26030103.common.ysyx_26030103_ALUFunction.NOP
import _root_.ysyx_26030103.common.ysyx_26030103_MDUOp.MUL
import _root_.ysyx_26030103.exu.ysyx_26030103_EXU

class ysyx_26030103_EXUMDUSpec
    extends AnyFlatSpec
    with Matchers
    with ChiselSim {

  private def driveMUL(dut: ysyx_26030103_EXU): Unit = {
    dut.io.in.bits.pc.poke("h80000000".U)
    dut.io.in.bits.snpc.poke("h80000004".U)
    dut.io.in.bits.ALUCtrl.poke(NOP)
    dut.io.in.bits.IsMDU.poke(true.B)
    dut.io.in.bits.MDUOp.poke(MUL)
    dut.io.in.bits.ALU_A.poke(7.U)
    dut.io.in.bits.ALU_B.poke(6.U)
    dut.io.in.bits.BranchA.poke(0.U)
    dut.io.in.bits.BranchB.poke(0.U)
    dut.io.in.bits.BranchFunct3.poke(0.U)
    dut.io.in.bits.IsBranch.poke(false.B)
    dut.io.in.bits.IsJal.poke(false.B)
    dut.io.in.bits.IsJalr.poke(false.B)
    dut.io.in.bits.Immediate.poke(0.U)
    dut.io.in.bits.Rd.poke(5.U)
    dut.io.in.bits.RegisterWrite.poke(true.B)
    dut.io.in.bits.WBSelect.poke(0.U)
    dut.io.in.bits.MemoryValid.poke(false.B)
    dut.io.in.bits.MemoryWrite.poke(false.B)
    dut.io.in.bits.WidthSelect.poke(2.U)
    dut.io.in.bits.LoadSigned.poke(false.B)
    dut.io.in.bits.StoreData.poke(0.U)
    dut.io.in.bits.IsCsrrw.poke(false.B)
    dut.io.in.bits.IsCsrrs.poke(false.B)
    dut.io.in.bits.IsEcall.poke(false.B)
    dut.io.in.bits.IsEbreak.poke(false.B)
    dut.io.in.bits.IsMret.poke(false.B)
    dut.io.in.bits.IsFence.poke(false.B)
    dut.io.in.bits.IsFenceI.poke(false.B)
    dut.io.in.bits.CSRAddress.poke(0.U)
    dut.io.in.bits.Rs1.poke(0.U)
    dut.io.in.bits.Rs1Data.poke(0.U)
    dut.io.in.bits.ALUCDIllegal.poke(false.B)
    dut.io.in.bits.ExceptionValid.poke(false.B)
    dut.io.in.bits.ExceptionCause.poke(0.U)
    dut.io.in.bits.pred_taken.poke(false.B)
    dut.io.in.bits.pred_target.poke(0.U)
  }

  "EXU" should "hold an MDU request until the result can commit" in {
    simulate(new ysyx_26030103_EXU) { dut =>
      driveMUL(dut)
      dut.io.in.valid.poke(true.B)
      dut.io.out.ready.poke(true.B)
      dut.io.Interrupt.poke(false.B)
      dut.io.MEMBusy.poke(false.B)
      dut.io.MemTrapCommit.poke(false.B)
      dut.io.MemTrapCause.poke(0.U)
      dut.io.MemTrapPC.poke(0.U)

      // 第一拍只发起请求，输入不能在此时作为 EXU 输出提交。
      dut.io.out.valid.expect(false.B)
      dut.clock.step()

      var cycles = 0
      while (!dut.io.out.valid.peek().litToBoolean && cycles < 128) {
        dut.clock.step()
        cycles += 1
      }
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.ALUResult.expect(42.U)
      dut.io.out.bits.Rd.expect(5.U)
      dut.io.out.bits.RegisterWrite.expect(true.B)
      dut.clock.step()
      dut.io.out.valid.expect(false.B)
    }
  }
}
