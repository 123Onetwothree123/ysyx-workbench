package ysyx_26030103.idu

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.ysyx_26030103.config.ysyx_26030103_NPCConfig
import _root_.ysyx_26030103.common.ysyx_26030103_AtomicOp
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** 只暴露 A 扩展译码结果的 IDU 测试顶层。 */
class ysyx_26030103_IDUAtomicHarness(config: ysyx_26030103_NPCConfig)
    extends Module {
  val io = IO(new Bundle {
    val instruction = Input(UInt(32.W))
    val valid = Output(Bool())
    val exceptionValid = Output(Bool())
    val memoryValid = Output(Bool())
    val memoryWrite = Output(Bool())
    val isAtomic = Output(Bool())
    val atomicOp = Output(UInt(5.W))
    val widthSelect = Output(UInt(2.W))
    val registerWrite = Output(Bool())
    val wbSelect = Output(UInt(2.W))
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
  idu.io.pipeline_mode := false.B
  idu.io.mmu_stall := false.B
  idu.io.Privilege := 3.U
  idu.io.Mstatus := 0.U
  idu.io.Mcounteren := "hffffffff".U
  idu.io.Scounteren := "hffffffff".U
  io.valid := idu.io.out.valid
  io.exceptionValid := idu.io.out.bits.ExceptionValid
  io.memoryValid := idu.io.out.bits.MemoryValid
  io.memoryWrite := idu.io.out.bits.MemoryWrite
  io.isAtomic := idu.io.out.bits.IsAtomic
  io.atomicOp := idu.io.out.bits.AtomicOp
  io.widthSelect := idu.io.out.bits.WidthSelect
  io.registerWrite := idu.io.out.bits.RegisterWrite
  io.wbSelect := idu.io.out.bits.WBSelect
}

/** 只暴露 misa 读口的 CSR 测试顶层。 */
class ysyx_26030103_AtomicMisaHarness(useA: Boolean) extends Module {
  val io = IO(new Bundle {
    val addr = Input(UInt(12.W))
    val rdata = Output(UInt(32.W))
  })
  val csr = Module(new _root_.ysyx_26030103.exu.ysyx_26030103_CSR(
    ysyx_26030103_NPCConfig().MMU, XLEN = 32, UseA = useA))
  csr.io.clk := clock
  csr.io.rst := reset
  csr.io.Enable := false.B
  csr.io.IsCsrrw := false.B
  csr.io.IsCsrrs := false.B
  csr.io.IsCsrrc := false.B
  csr.io.IsEcall := false.B
  csr.io.IsEbreak := false.B
  csr.io.IsMret := false.B
  csr.io.IsSret := false.B
  csr.io.IsSfenceVma := false.B
  csr.io.CSRAddress := io.addr
  csr.io.rs1 := 0.U
  csr.io.Rs1Data := 0.U
  csr.io.pc := 0.U
  csr.io.TrapTval := 0.U
  csr.io.Interrupt := false.B
  csr.io.TimerInterrupt := false.B
  csr.io.TrapValid := false.B
  csr.io.TrapCause := 0.U
  csr.io.MemTrap := false.B
  csr.io.Mtime := 0.U
  io.rdata := csr.io.CSR_rdata
}

/** A 扩展译码与 misa.A 定向测试。 */
class ysyx_26030103_IDUAtomicTest extends AnyFlatSpec with Matchers {
  private def bool(value: Bool): Boolean = value.peek().litValue == 1
  private def long(value: UInt): Long = value.peek().litValue.toLong

  private def amo(
      funct5: Int,
      rd: Int,
      rs1: Int,
      rs2: Int,
      funct3: Int,
      aq: Boolean = false,
      rl: Boolean = false
  ): BigInt = {
    val funct7 = (funct5 << 2) | (if (aq) 2 else 0) | (if (rl) 1 else 0)
    (BigInt(funct7) << 25) | (BigInt(rs2) << 20) | (BigInt(rs1) << 15) |
      (BigInt(funct3) << 12) | (BigInt(rd) << 7) | BigInt(0x2f)
  }

  private def runDecode(
      config: ysyx_26030103_NPCConfig,
      instruction: BigInt
  )(check: ysyx_26030103_IDUAtomicHarness => Unit): Unit = {
    simulate(new ysyx_26030103_IDUAtomicHarness(config)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.instruction.poke(instruction.U(32.W))
      dut.clock.step()
      check(dut)
    }
  }

  "IDU A extension" should "正确译码 LR.W/SC.W 与 aq/rl 标志" in {
    val rv32 = ysyx_26030103_NPCConfig(XLEN = 32, UseA = true)
    runDecode(rv32, amo(0x02, rd = 5, rs1 = 6, rs2 = 0, funct3 = 2)) { dut =>
      bool(dut.io.exceptionValid) should be(false)
      bool(dut.io.memoryValid) should be(true)
      bool(dut.io.memoryWrite) should be(false)
      bool(dut.io.isAtomic) should be(true)
      long(dut.io.atomicOp) should be(0x02L)
      long(dut.io.widthSelect) should be(2L)
      bool(dut.io.registerWrite) should be(true)
      long(dut.io.wbSelect) should be(1L)
    }
    runDecode(rv32,
      amo(0x03, rd = 7, rs1 = 8, rs2 = 9, funct3 = 2, aq = true, rl = true)) {
      dut =>
        bool(dut.io.exceptionValid) should be(false)
        bool(dut.io.memoryWrite) should be(true)
        bool(dut.io.isAtomic) should be(true)
        long(dut.io.atomicOp) should be(0x03L)
    }
  }

  it should "译码 xv6 常见的 amoswap.w.aq/amoadd.w" in {
    val rv32 = ysyx_26030103_NPCConfig(XLEN = 32, UseA = true)
    runDecode(rv32,
      amo(0x01, rd = 10, rs1 = 11, rs2 = 12, funct3 = 2, aq = true)) { dut =>
        bool(dut.io.exceptionValid) should be(false)
        bool(dut.io.isAtomic) should be(true)
        long(dut.io.atomicOp) should be(0x01L)
        long(dut.io.widthSelect) should be(2L)
    }
    runDecode(rv32, amo(0x00, rd = 10, rs1 = 11, rs2 = 12, funct3 = 2)) {
      dut =>
        bool(dut.io.exceptionValid) should be(false)
        long(dut.io.atomicOp) should be(0x00L)
    }
  }

  it should "RV64 支持 .D，RV32 拒绝 .D" in {
    val rv64 =
      ysyx_26030103_NPCConfig(XLEN = 64, AddressWidth = 64, UseA = true)
    runDecode(rv64, amo(0x00, rd = 5, rs1 = 6, rs2 = 7, funct3 = 3)) { dut =>
      bool(dut.io.exceptionValid) should be(false)
      bool(dut.io.isAtomic) should be(true)
      long(dut.io.widthSelect) should be(3L)
    }
    val rv32 = ysyx_26030103_NPCConfig(XLEN = 32, UseA = true)
    runDecode(rv32, amo(0x00, rd = 5, rs1 = 6, rs2 = 7, funct3 = 3)) { dut =>
      bool(dut.io.exceptionValid) should be(true)
      bool(dut.io.isAtomic) should be(false)
    }
  }

  it should "RISCV_A=n 时 AMO 为非法指令" in {
    val rv32 = ysyx_26030103_NPCConfig(XLEN = 32, UseA = false)
    runDecode(rv32, amo(0x00, rd = 5, rs1 = 6, rs2 = 7, funct3 = 2)) { dut =>
      bool(dut.io.exceptionValid) should be(true)
      bool(dut.io.isAtomic) should be(false)
    }
  }

  it should "拒绝保留的 funct5 编码" in {
    val rv32 = ysyx_26030103_NPCConfig(XLEN = 32, UseA = true)
    runDecode(rv32, amo(0x05, rd = 5, rs1 = 6, rs2 = 7, funct3 = 2)) { dut =>
      bool(dut.io.exceptionValid) should be(true)
      bool(dut.io.isAtomic) should be(false)
    }
  }

  "CSR misa" should "随 RISCV_A 设置/清除 A 位" in {
    for ((useA, expected) <- Seq(false -> 0L, true -> 1L)) {
      simulate(new ysyx_26030103_AtomicMisaHarness(useA)) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step(2)
        dut.reset.poke(false.B)
        dut.io.addr.poke("h301".U(12.W))
        dut.clock.step()
        val value = dut.io.rdata.peek().litValue
        (value & 0x1).toLong should be(expected)
        ((value >> 8) & 0x1).toLong should be(1L) // I 位
      }
    }
  }
}
