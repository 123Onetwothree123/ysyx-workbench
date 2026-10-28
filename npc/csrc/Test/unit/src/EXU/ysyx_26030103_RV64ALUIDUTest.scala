package ysyx_26030103.exu

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import _root_.ysyx_26030103.common.ysyx_26030103_ALUFunction._
import _root_.ysyx_26030103.config._

/** RV64I 数据通路定向测试：ALU 字运算/64 位运算、GPR、IDU 译码。 */
class ysyx_26030103_RV64ALUIDUTest extends AnyFlatSpec with Matchers {
  private def u64(value: BigInt): BigInt = value & ((BigInt(1) << 64) - 1)

  private def alu(ctrl: UInt, a: BigInt, b: BigInt): BigInt = {
    var result = BigInt(0)
    simulate(ysyx_26030103_ALU(ysyx_26030103_NPCConfig(
      XLEN = 64,
      AddressWidth = 64
    ))) { dut =>
      dut.io.A.poke(a.U(64.W))
      dut.io.B.poke(b.U(64.W))
      dut.io.ALUCtrl.poke(ctrl)
      result = dut.io.result.peek().litValue
    }
    result
  }

  "RV64 ALU" should "正确执行 64 位加减/逻辑/移位/比较" in {
    alu(ADD, BigInt("ffffffffffffffff", 16), 1) should be(0)
    alu(ADD, BigInt("0000000100000000", 16), BigInt("0000000200000000", 16)) should
      be(BigInt("0000000300000000", 16))
    alu(SUB, 0, 1) should be(BigInt("ffffffffffffffff", 16))
    alu(SLL, 1, 40) should be(BigInt("0000010000000000", 16))
    alu(SRL, BigInt("8000000000000000", 16), 4) should
      be(BigInt("0800000000000000", 16))
    alu(SRA, BigInt("8000000000000000", 16), 4) should
      be(BigInt("f800000000000000", 16))
    alu(SLT, BigInt("ffffffffffffffff", 16), 1) should be(1)
    alu(SLTU, BigInt("ffffffffffffffff", 16), 1) should be(0)
    alu(XOR, BigInt("ffff0000ffff0000", 16), BigInt("00ff00ff00ff00ff", 16)) should
      be(BigInt("ff0000ffff0000ff", 16))
  }

  it should "按 RV64 语义符号扩展 W 型结果" in {
    alu(ADDW, BigInt("000000007fffffff", 16), 1) should
      be(BigInt("ffffffff80000000", 16))
    alu(ADDW, 1, 2) should be(3)
    alu(SUBW, BigInt("0000000080000000", 16), 1) should
      be(BigInt("000000007fffffff", 16))
    alu(SLLW, BigInt("0000000040000001", 16), 1) should
      be(BigInt("ffffffff80000002", 16))
    alu(SRLW, BigInt("0000000080000000", 16), 1) should be(0x40000000L)
    alu(SRAW, BigInt("0000000080000000", 16), 1) should
      be(BigInt("ffffffffc0000000", 16))
    // W 型移位只看 shamt[4:0]。
    alu(SLLW, 1, 33) should be(2)
  }

  "RV64 GPR" should "保存完整 64 位寄存器值" in {
    simulate(new _root_.ysyx_26030103.common.ysyx_26030103_GPR(64)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.WriteEN.poke(false.B)
      dut.io.WriteSELECT.poke(0.U)
      dut.io.Read1SELECT.poke(0.U)
      dut.io.Read2SELECT.poke(0.U)
      dut.io.DebugRaddr.poke(0.U)
      dut.clock.step()
      dut.io.WriteEN.poke(true.B)
      dut.io.WriteSELECT.poke(5.U)
      dut.io.wdata.poke(BigInt("0123456789abcdef", 16).U(64.W))
      dut.clock.step()
      dut.io.WriteEN.poke(false.B)
      dut.io.Read1SELECT.poke(5.U)
      dut.io.DebugRaddr.poke(5.U)
      dut.io.ReadDATA1.peek().litValue should be(BigInt("0123456789abcdef", 16))
      dut.io.DebugRdata.peek().litValue should be(BigInt("0123456789abcdef", 16))
    }
  }

  private class IduHarness(xlen: Int) extends Module {
    val io = IO(new Bundle {
      val instruction = Input(UInt(32.W))
      val pc = Input(UInt(xlen.W))
      val exceptionValid = Output(Bool())
      val aluCtrl = Output(UInt(CtrlWidth.W))
      val memoryValid = Output(Bool())
      val memoryWrite = Output(Bool())
      val widthSelect = Output(UInt(2.W))
      val loadSigned = Output(Bool())
      val isMdu = Output(Bool())
      val mduOp = Output(UInt(_root_.ysyx_26030103.common.ysyx_26030103_MDUOp.Width.W))
      val aluA = Output(UInt(xlen.W))
      val aluB = Output(UInt(xlen.W))
      val immediate = Output(UInt(xlen.W))
    })
    val config = _root_.ysyx_26030103.config.ysyx_26030103_NPCConfig(
      XLEN = xlen,
      AddressWidth = xlen,
      MMU = _root_.ysyx_26030103.config.ysyx_26030103_MMUProfile(
        hasS = false,
        hasU = false
      )
    )
    val idu = Module(new _root_.ysyx_26030103.idu.ysyx_26030103_IDU(config))
    idu.io.in.valid := true.B
    idu.io.in.bits.Instruction := io.instruction
    idu.io.in.bits.IsCompressed := false.B
    idu.io.in.bits.pc := io.pc
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
    idu.io.Privilege := 3.U
    idu.io.Mstatus := 0.U
    idu.io.Mcounteren := "hffffffff".U
    idu.io.Scounteren := "hffffffff".U
    io.exceptionValid := idu.io.out.bits.ExceptionValid
    io.aluCtrl := idu.io.out.bits.ALUCtrl
    io.memoryValid := idu.io.out.bits.MemoryValid
    io.memoryWrite := idu.io.out.bits.MemoryWrite
    io.widthSelect := idu.io.out.bits.WidthSelect
    io.loadSigned := idu.io.out.bits.LoadSigned
    io.isMdu := idu.io.out.bits.IsMDU
    io.mduOp := idu.io.out.bits.MDUOp
    io.aluA := idu.io.out.bits.ALU_A
    io.aluB := idu.io.out.bits.ALU_B
    io.immediate := idu.io.out.bits.Immediate
  }

  private def rType(funct7: Int, rs2: Int, rs1: Int, funct3: Int, rd: Int,
      opcode: Int): BigInt =
    (BigInt(funct7) << 25) | (BigInt(rs2) << 20) | (BigInt(rs1) << 15) |
      (BigInt(funct3) << 12) | (BigInt(rd) << 7) | BigInt(opcode)

  private def iType(imm: Int, rs1: Int, funct3: Int, rd: Int, opcode: Int): BigInt =
    ((BigInt(imm) & 0xfff) << 20) | (BigInt(rs1) << 15) |
      (BigInt(funct3) << 12) | (BigInt(rd) << 7) | BigInt(opcode)

  private def decode(xlen: Int, insn: BigInt, pc: BigInt = BigInt("80000000", 16)): IduHarness = {
    val harness = Module(new IduHarness(xlen))
    harness
  }

  private def runDecode(xlen: Int, insn: BigInt)(
      check: IduHarness => Unit): Unit = {
    simulate(new IduHarness(xlen)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.instruction.poke(insn.U(32.W))
      dut.io.pc.poke(BigInt("80000000", 16).U(xlen.W))
      dut.clock.step()
      check(dut)
    }
  }

  "RV64 IDU" should "译码 OP-32 与 RV64 访存指令" in {
    runDecode(64, iType(1, 2, 0, 1, 0x1b)) { dut =>
      dut.io.exceptionValid.peek().litValue should be(0)
      dut.io.aluCtrl.peek().litValue should be(ADDW.litValue)
    }
    // slliw rd,rs1,1: OP-IMM-32, funct3=001, funct7=0000000, shamt=1
    runDecode(64, iType(1, 2, 1, 1, 0x1b)) { dut =>
      dut.io.aluCtrl.peek().litValue should be(SLLW.litValue)
      dut.io.immediate.peek().litValue should be(1)
    }
    runDecode(64, rType(0x00, 3, 2, 0, 1, 0x3b)) { dut =>
      dut.io.aluCtrl.peek().litValue should be(ADDW.litValue)
    }
    runDecode(64, rType(0x20, 3, 2, 0, 1, 0x3b)) { dut =>
      dut.io.aluCtrl.peek().litValue should be(SUBW.litValue)
    }
    runDecode(64, rType(0x00, 3, 2, 1, 1, 0x3b)) { dut =>
      dut.io.aluCtrl.peek().litValue should be(SLLW.litValue)
    }
    runDecode(64, rType(0x00, 3, 2, 5, 1, 0x3b)) { dut =>
      dut.io.aluCtrl.peek().litValue should be(SRLW.litValue)
    }
    runDecode(64, rType(0x20, 3, 2, 5, 1, 0x3b)) { dut =>
      dut.io.aluCtrl.peek().litValue should be(SRAW.litValue)
    }
    runDecode(64, iType(8, 2, 3, 1, 0x03)) { dut =>
      dut.io.memoryValid.peek().litValue should be(1)
      dut.io.memoryWrite.peek().litValue should be(0)
      dut.io.widthSelect.peek().litValue should be(3)
    }
    runDecode(64, iType(8, 2, 6, 1, 0x03)) { dut =>
      dut.io.widthSelect.peek().litValue should be(2)
      dut.io.loadSigned.peek().litValue should be(0)
    }
    runDecode(64, iType(0, 3, 3, 2, 0x23)) { dut =>
      dut.io.memoryValid.peek().litValue should be(1)
      dut.io.memoryWrite.peek().litValue should be(1)
      dut.io.widthSelect.peek().litValue should be(3)
    }
  }

  it should "在 XLEN=32 下仍把 RV64 专属指令判为非法" in {
    runDecode(32, iType(1, 2, 0, 1, 0x1b)) { dut =>
      dut.io.exceptionValid.peek().litValue should be(1)
    }
    runDecode(32, rType(0x00, 3, 2, 0, 1, 0x3b)) { dut =>
      dut.io.exceptionValid.peek().litValue should be(1)
    }
    runDecode(32, iType(8, 2, 3, 1, 0x03)) { dut =>
      dut.io.exceptionValid.peek().litValue should be(1)
    }
    runDecode(32, iType(8, 2, 6, 1, 0x03)) { dut =>
      dut.io.exceptionValid.peek().litValue should be(1)
    }
  }

  it should "RV64 移位立即数允许 shamt[5]" in {
    // slli rd,rs1,33: opcode 0x13, funct3=1, imm=0x021
    runDecode(64, iType(33, 2, 1, 1, 0x13)) { dut =>
      dut.io.exceptionValid.peek().litValue should be(0)
      dut.io.aluCtrl.peek().litValue should be(SLL.litValue)
    }
    // RV32 下同一编码非法。
    runDecode(32, iType(33, 2, 1, 1, 0x13)) { dut =>
      dut.io.exceptionValid.peek().litValue should be(1)
    }
  }
}
