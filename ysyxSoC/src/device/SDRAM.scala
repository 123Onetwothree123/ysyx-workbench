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
    val Command_NO_OPERATION = Wire(Bool())
    val Command_ACTIVE = Wire(Bool())
    val Command_READ = Wire(Bool())
    val Command_WRITE = Wire(Bool())
    val Command_BURST_TERMINATE = Wire(Bool())
    val Command_PRECHAREG = Wire(Bool())
    val Command_AUTO_REFRESH = Wire(Bool())
    val Command_LOAD_MODE_REGISTER = Wire(Bool())

    Command_NO_OPERATION := io.cs || (io.ras && io.cas && io.we)
    Command_ACTIVE := (~io.cs) && (~io.ras) && io.cas && io.we
    Command_READ := (~io.cs) && io.ras && (~io.cas) && io.we
    Command_WRITE := (~io.cs) && io.ras && (~io.cas) && (~io.we)
    Command_BURST_TERMINATE := (~io.cs) && io.ras && io.cas && (~io.we)
    Command_PRECHAREG := (~io.cs) && (~io.ras) && io.cas && (~io.we)
    Command_AUTO_REFRESH := (~io.cs) && (~io.ras) && (~io.cas) && io.we
    Command_LOAD_MODE_REGISTER := (~io.cs) && (~io.ras) && (~io.cas) && (~io.we)
    val memory = Seq.fill(4)(Mem(8192, Vec(512, UInt(16.W))))
    // 真实 SDRAM 有 4 个 bank，每个 bank 各自独立保持一个打开的行
    val ROWBuffer = Reg(Vec(4, Vec(512, UInt(16.W)))) // 每个 bank 各自的行缓冲
    val ActiveRow = Reg(Vec(4, UInt(13.W)))           // 每个 bank 各自的激活行
    val ActiveRowNext = Reg(Vec(4, UInt(13.W)))       // ACTIVE 时暂存新行号，供 state_active 读 memory 用
    val ModeRegister = RegInit(0x20.U(13.W))
    val MR_Burst_Length = MuxLookup(ModeRegister(2, 0), 1.U)(
      Seq(0.U -> 1.U, 1.U -> 2.U, 2.U -> 4.U, 3.U -> 8.U, 7.U -> 256.U)
    )
    val MR_CAS_Latency = ModeRegister(6, 4)
    val MR_Burst_Type = ModeRegister(3)
    val MR_Write_Burst_Mode = ModeRegister(9)
    val state_idle :: state_active :: state_read :: state_read_data :: state_write :: state_write_data :: Nil =
      Enum(6)
    val state = RegInit(state_idle)
    val CmdBank = Reg(UInt(2.W))  // 当前 ACTIVE/READ/WRITE 命令针对的 bank
    val CmdCol = Reg(UInt(9.W))   // 当前列地址
    val BurstCounter = Reg(UInt(8.W)) // SDRAM真的牛逼，居然所有读写操作都是属于突发情况
    // 写直接落进存储阵列（不依赖 precharge 提交）
    val WriteEnable = WireDefault(false.B)
    val WriteBank = WireDefault(0.U(2.W))
    val WriteColumn = WireDefault(0.U(9.W))
    output := 0.U
    en := false.B
    switch(state) {
      is(state_idle) {
      when(Command_ACTIVE) {
        ActiveRow(io.ba) := io.a
        ActiveRowNext(io.ba) := io.a
        CmdBank := io.ba
        state := state_active
      }
        when(Command_LOAD_MODE_REGISTER) {
          ModeRegister := io.a
          state := state_idle
        }
        when(Command_READ) {
          CmdBank := io.ba
          CmdCol := io.a(8, 0)
          BurstCounter := 0.U
          state := state_read
        }
        when(Command_WRITE) {
          CmdBank := io.ba
          CmdCol := io.a(8, 0)
          // beat0 就在 WRITE 命令这一拍的 dq 上
          WriteEnable := true.B
          WriteBank := io.ba
          WriteColumn := io.a(8, 0)
          BurstCounter := 1.U
          when(MR_Write_Burst_Mode || MR_Burst_Length === 1.U) {
            state := state_idle
          }.otherwise {
            state := state_write_data
          }
        }
        when(Command_PRECHAREG) {
          // 写已直接落盘，PRECHARGE 无需提交，实现成 NOP
          state := state_idle
        }
        when(Command_AUTO_REFRESH) {
          state := state_idle
        }
      }
      is(state_active) {
        // 载入命令所指 bank 的行缓冲
        when(CmdBank === 0.U) { ROWBuffer(0) := memory(0)(ActiveRowNext(0)) }
        when(CmdBank === 1.U) { ROWBuffer(1) := memory(1)(ActiveRowNext(1)) }
        when(CmdBank === 2.U) { ROWBuffer(2) := memory(2)(ActiveRowNext(2)) }
        when(CmdBank === 3.U) { ROWBuffer(3) := memory(3)(ActiveRowNext(3)) }
        state := state_idle
      }
      is(state_read) {
        when(BurstCounter < (MR_CAS_Latency - 2.U)) {
          BurstCounter := BurstCounter + 1.U
        }.otherwise {
          BurstCounter := 0.U
          state := state_read_data
        }
      }
      is(state_read_data) {
        en := true.B
        output := ROWBuffer(CmdBank)(CmdCol + BurstCounter)
        when(BurstCounter === (MR_Burst_Length - 1.U)) {
          state := state_idle
        }.otherwise {
          BurstCounter := BurstCounter + 1.U
        }
      }
      is(state_write) {
        state := state_write_data
      }
      is(state_write_data) {
        WriteEnable := true.B
        WriteBank := CmdBank
        WriteColumn := CmdCol + BurstCounter
        when(MR_Write_Burst_Mode || BurstCounter === MR_Burst_Length - 1.U) {
          state := state_idle // single write只要一拍
        }.otherwise {
          BurstCounter := BurstCounter + 1.U
        }
      }
    }
    // 写落盘：同时更新对应 bank 的行缓冲(供开行读)和存储阵列(持久化)，按 dqm 做字节掩码
    when(WriteEnable) {
      val OldWord = ROWBuffer(WriteBank)(WriteColumn)
      val NewWord = Cat(
        Mux(!io.dqm(1), input(15, 8), OldWord(15, 8)),
        Mux(!io.dqm(0), input(7, 0), OldWord(7, 0))
      )
      ROWBuffer(WriteBank)(WriteColumn) := NewWord
      val WriteData = VecInit(Seq.fill(512)(NewWord))
      val WriteMask = (0 until 512).map(i => i.U === WriteColumn)
      when(WriteBank === 0.U) { memory(0).write(ActiveRow(0), WriteData, WriteMask) }
      when(WriteBank === 1.U) { memory(1).write(ActiveRow(1), WriteData, WriteMask) }
      when(WriteBank === 2.U) { memory(2).write(ActiveRow(2), WriteData, WriteMask) }
      when(WriteBank === 3.U) { memory(3).write(ActiveRow(3), WriteData, WriteMask) }
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
