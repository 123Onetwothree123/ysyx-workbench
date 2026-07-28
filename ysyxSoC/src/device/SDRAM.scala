package ysyx

import chisel3._
import chisel3.util._
import chisel3.experimental.Analog

import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.amba.apb._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

class SDRAMIO(DataWidth: Int = 16) extends Bundle {
  val clk = Output(Bool())
  val cke = Output(Bool())
  val cs = Output(Bool())
  val ras = Output(Bool())
  val cas = Output(Bool())
  val we = Output(Bool())
  val a = Output(UInt(13.W))
  val ba = Output(UInt(2.W))
  val dqm = Output(UInt((DataWidth / 8).W))
  // 位扩展：dq 拆成 DataWidth/16 根 16 位 Analog，每根接一个 16 位颗粒
  val dq = Vec(DataWidth / 16, Analog(16.W))
}

class sdram_top_axi extends BlackBox {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())
    val in = Flipped(
      new AXI4Bundle(
        AXI4BundleParameters(addrBits = 32, dataBits = 32, idBits = 4)
      )
    )
    val sdram = new SDRAMIO(32)
  })
}

class sdram_top_apb extends BlackBox {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())
    val in =
      Flipped(new APBBundle(APBBundleParameters(addrBits = 32, dataBits = 32)))
    val sdram = new SDRAMIO(32)
  })
}

class sdram extends BlackBox {
  val io = IO(Flipped(new SDRAMIO))
}

class sdramChisel extends RawModule {
  val io = IO(Flipped(new SDRAMIO))
  val output = Wire(UInt(16.W))
  val en = Wire(Bool())
  val input = TriStateInBuf(io.dq(0), output, en)

  withClockAndReset(io.clk.asClock, false.B.asAsyncReset) {
    // 命令解码: SDRAM的命令总线每一拍都有效, 新命令(如流水式READ)可以打断正在进行的突发,
    // 因此任何状态下都要解码命令, 不能只在外层idle状态解码
    val Command_NOP = io.cs || (io.ras && io.cas && io.we)
    val Command_ACTIVE = !io.cs && !io.ras && io.cas && io.we
    val Command_READ = !io.cs && io.ras && !io.cas && io.we
    val Command_WRITE = !io.cs && io.ras && !io.cas && !io.we
    val Command_TERMINATE = !io.cs && io.ras && io.cas && !io.we
    val Command_PRECHARGE = !io.cs && !io.ras && io.cas && !io.we
    val Command_REFRESH = !io.cs && !io.ras && !io.cas && io.we
    val Command_LOAD_MR = !io.cs && !io.ras && !io.cas && !io.we

    // 存储阵列: 4个bank, 每bank 8192行x512列x16bit, 展平成一维(Cat(行,列)寻址).
    // 写直接落存储阵列, 不需要行缓冲和precharge写回(官方允许PRECHARGE/REFRESH实现为NOP),
    // 也避免了整行重构带来的仿真性能问题
    val memory = Seq.fill(4)(Mem(8192 * 512, UInt(16.W)))
    val ActiveRow = Reg(Vec(4, UInt(13.W))) // 每个bank各自打开的行
    val ModeRegister = RegInit(0x20.U(13.W))
    val MR_Burst_Length = MuxLookup(ModeRegister(2, 0), 1.U)(
      Seq(0.U -> 1.U, 1.U -> 2.U, 2.U -> 4.U, 3.U -> 8.U, 7.U -> 256.U)
    )
    val MR_CAS_Latency = ModeRegister(6, 4)

    when(Command_ACTIVE) { ActiveRow(io.ba) := io.a }
    when(Command_LOAD_MR) { ModeRegister := io.a }
    // PRECHARGE/REFRESH与电气特性相关, 按官方要求实现为NOP

    // ---- 读: 任何一拍都可接READ(读可以打断读), 经CAS延迟流水线后驱动DQ ----
    val PipeValid = RegInit(VecInit(Seq.fill(3)(false.B)))
    val PipeBank = Reg(Vec(3, UInt(2.W)))
    val PipeCol = Reg(Vec(3, UInt(9.W)))
    PipeValid(0) := Command_READ
    PipeBank(0) := io.ba
    PipeCol(0) := io.a(8, 0)
    for (i <- 1 until 3) {
      PipeValid(i) := PipeValid(i - 1)
      PipeBank(i) := PipeBank(i - 1)
      PipeCol(i) := PipeCol(i - 1)
    }
    val pipeFire = MuxLookup(MR_CAS_Latency, PipeValid(1))(
      Seq(1.U -> PipeValid(0), 2.U -> PipeValid(1), 3.U -> PipeValid(2))
    )
    val pipeBank = MuxLookup(MR_CAS_Latency, PipeBank(1))(
      Seq(1.U -> PipeBank(0), 2.U -> PipeBank(1), 3.U -> PipeBank(2))
    )
    val pipeCol = MuxLookup(MR_CAS_Latency, PipeCol(1))(
      Seq(1.U -> PipeCol(0), 2.U -> PipeCol(1), 3.U -> PipeCol(2))
    )

    // 突发输出引擎: pipeFire当拍出第一个数据, 之后按突发长度连续出
    val RdLeft = RegInit(0.U(9.W))
    val RdBank = Reg(UInt(2.W))
    val RdCol = Reg(UInt(9.W))
    val driving = RdLeft =/= 0.U
    val outBank = Mux(pipeFire, pipeBank, RdBank)
    val outCol = Mux(pipeFire, pipeCol, RdCol)
    val rdDataVec =
      (0 until 4).map(b => memory(b).read(Cat(ActiveRow(b), outCol)))
    en := pipeFire || driving
    output := rdDataVec(outBank)
    when(pipeFire) {
      RdLeft := MR_Burst_Length - 1.U
      RdBank := pipeBank
      RdCol := pipeCol + 1.U
    }.elsewhen(driving) {
      RdLeft := RdLeft - 1.U
      RdCol := RdCol + 1.U
    }
    when(Command_TERMINATE) {
      RdLeft := 0.U
      PipeValid.foreach(_ := false.B)
    }

    // ---- 写: 命令与第一拍数据同拍出现, 后续拍连续, 可被新命令打断 ----
    val WrLeft = RegInit(0.U(9.W))
    val WrBank = Reg(UInt(2.W))
    val WrCol = Reg(UInt(9.W))
    val wrFire = WireDefault(false.B)
    val wrBank = WireDefault(0.U(2.W))
    val wrCol = WireDefault(0.U(9.W))
    when(Command_WRITE) {
      wrFire := true.B
      wrBank := io.ba
      wrCol := io.a(8, 0)
      WrLeft := MR_Burst_Length - 1.U
      WrBank := io.ba
      WrCol := io.a(8, 0) + 1.U
    }.elsewhen(WrLeft =/= 0.U) {
      wrFire := true.B
      wrBank := WrBank
      wrCol := WrCol
      WrLeft := WrLeft - 1.U
      WrCol := WrCol + 1.U
    }
    when(Command_READ || Command_TERMINATE || Command_PRECHARGE) {
      WrLeft := 0.U
    }

    // 写落盘: 按dqm做字节掩码(读改写), 直接写存储阵列
    when(wrFire) {
      val waddr = (0 until 4).map(b => Cat(ActiveRow(b), wrCol))
      val oldVec = (0 until 4).map(b => memory(b).read(waddr(b)))
      val OldWord = oldVec(wrBank)
      val NewWord = Cat(
        Mux(!io.dqm(1), input(15, 8), OldWord(15, 8)),
        Mux(!io.dqm(0), input(7, 0), OldWord(7, 0))
      )
      when(wrBank === 0.U) { memory(0).write(waddr(0), NewWord) }
      when(wrBank === 1.U) { memory(1).write(waddr(1), NewWord) }
      when(wrBank === 2.U) { memory(2).write(waddr(2), NewWord) }
      when(wrBank === 3.U) { memory(3).write(waddr(3), NewWord) }
    }
  }
}

class AXI4SDRAM(address: Seq[AddressSet])(implicit p: Parameters)
    extends LazyModule {
  val beatBytes = 4
  val node = AXI4SlaveNode(
    Seq(
      AXI4SlavePortParameters(
        Seq(
          AXI4SlaveParameters(
            address = address,
            executable = true,
            supportsWrite = TransferSizes(1, beatBytes),
            supportsRead = TransferSizes(1, beatBytes),
            interleavedId = Some(0)
          )
        ),
        beatBytes = beatBytes
      )
    )
  )

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val (in, _) = node.in(0)
    val sdram_bundle = IO(new SDRAMIO(32))

    val msdram = Module(new sdram_top_axi)
    msdram.io.clock := clock
    msdram.io.reset := reset.asBool
    msdram.io.in <> in
    sdram_bundle <> msdram.io.sdram
  }
}

class APBSDRAM(address: Seq[AddressSet])(implicit p: Parameters)
    extends LazyModule {
  val node = APBSlaveNode(
    Seq(
      APBSlavePortParameters(
        Seq(
          APBSlaveParameters(
            address = address,
            executable = true,
            supportsRead = true,
            supportsWrite = true
          )
        ),
        beatBytes = 4
      )
    )
  )

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val (in, _) = node.in(0)
    val sdram_bundle = IO(new SDRAMIO(32))

    val msdram = Module(new sdram_top_apb)
    msdram.io.clock := clock
    msdram.io.reset := reset.asBool
    msdram.io.in <> in
    sdram_bundle <> msdram.io.sdram
  }
}
