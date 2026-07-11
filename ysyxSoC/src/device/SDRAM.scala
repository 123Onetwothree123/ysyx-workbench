package ysyx

import chisel3._
import chisel3.util._
import chisel3.experimental.Analog

import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.amba.apb._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

class SDRAMIO extends Bundle {
  val clk = Output(Bool())
  val cke = Output(Bool())
  val cs = Output(Bool())
  val ras = Output(Bool())
  val cas = Output(Bool())
  val we = Output(Bool())
  val a = Output(UInt(13.W))
  val ba = Output(UInt(2.W))
  val dqm = Output(UInt(2.W))
  val dq = Analog(16.W)
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
    val sdram = new SDRAMIO
  })
}

class sdram_top_apb extends BlackBox {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())
    val in =
      Flipped(new APBBundle(APBBundleParameters(addrBits = 32, dataBits = 32)))
    val sdram = new SDRAMIO
  })
}

class sdram extends BlackBox {
  val io = IO(Flipped(new SDRAMIO))
}

class sdramChisel extends RawModule {
  val io = IO(Flipped(new SDRAMIO))
  val output = Wire(UInt(16.W))
  val en = Wire(Bool())
  val input = TriStateInBuf(io.dq, output, en)

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
    val ROWBuffer = Reg(Vec(512, UInt(16.W))) // 行缓冲
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
    val ActiveMemoryBank = Reg(UInt(2.W)) // memory bank中文存储体
    val ActiveROWAddress_In_A_MemoryBank = Reg(UInt(13.W))
    val ActiveColumnAddress_In_A_MemoryBank = Reg(UInt(9.W))
    val BurstCounter = Reg(UInt(8.W)) // SDRAM真的牛逼，居然所有读写操作都是属于突发情况
    // precharge 的提交延迟一拍，避开最后一次写的读改写竞争
    val PrechargePending = RegInit(false.B)
    val PrechargeBank = Reg(UInt(2.W))
    val PrechargeRow = Reg(UInt(13.W))
    output := 0.U
    en := false.B
    switch(state) {
      is(state_idle) {
        when(Command_ACTIVE) {
          ActiveMemoryBank := io.ba
          ActiveROWAddress_In_A_MemoryBank := io.a
          state := state_active
        }
        when(Command_LOAD_MODE_REGISTER) {
          ModeRegister := io.a
          state := state_idle
        }
        when(Command_READ) {
          ActiveColumnAddress_In_A_MemoryBank := io.a(8, 0)
          BurstCounter := 0.U
          state := state_read
        }
        when(Command_WRITE) {
          ActiveColumnAddress_In_A_MemoryBank := io.a(8, 0)
          // beat0 就在 WRITE 命令这一拍的 dq 上，立即按 dqm 写入 col0
          val col0 = io.a(8, 0)
          val old0 = ROWBuffer(col0)
          ROWBuffer(col0) := Cat(
            Mux(!io.dqm(1), input(15, 8), old0(15, 8)),
            Mux(!io.dqm(0), input(7, 0), old0(7, 0))
          )
          BurstCounter := 1.U
          when(MR_Write_Burst_Mode || MR_Burst_Length === 1.U) {
            state := state_idle
          }.otherwise {
            state := state_write_data
          }
        }
        when(Command_PRECHAREG) {
          // 延迟一拍提交：先记下要提交的 bank/row，下一拍 ROWBuffer 稳定后再写回
          PrechargePending := true.B
          PrechargeBank := ActiveMemoryBank
          PrechargeRow := ActiveROWAddress_In_A_MemoryBank
          state := state_idle
        }
        when(Command_AUTO_REFRESH) {
          state := state_idle
        }
      }
      is(state_active) {
        ROWBuffer := memory(0)(ActiveROWAddress_In_A_MemoryBank)
        when(ActiveMemoryBank === 1.U) {
          ROWBuffer := memory(1)(ActiveROWAddress_In_A_MemoryBank)
        }
        when(ActiveMemoryBank === 2.U) {
          ROWBuffer := memory(2)(ActiveROWAddress_In_A_MemoryBank)
        }
        when(ActiveMemoryBank === 3.U) {
          ROWBuffer := memory(3)(ActiveROWAddress_In_A_MemoryBank)
        }
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
        output := ROWBuffer(ActiveColumnAddress_In_A_MemoryBank + BurstCounter)
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
        val col = ActiveColumnAddress_In_A_MemoryBank + BurstCounter
        val old = ROWBuffer(col)
        ROWBuffer(col) := Cat(
          Mux(!io.dqm(1), input(15, 8), old(15, 8)),
          Mux(!io.dqm(0), input(7, 0), old(7, 0))
        )
        when(MR_Write_Burst_Mode || BurstCounter === MR_Burst_Length - 1.U) {
          state := state_idle // single write只要一拍
        }.otherwise {
          BurstCounter := BurstCounter + 1.U
        }
      }
    }
    // precharge 延迟提交：上一拍收到 PRECHARGE，这一拍 ROWBuffer 已稳定，写回存储阵列
    when(PrechargePending) {
      when(PrechargeBank === 0.U) { memory(0)(PrechargeRow) := ROWBuffer }
      when(PrechargeBank === 1.U) { memory(1)(PrechargeRow) := ROWBuffer }
      when(PrechargeBank === 2.U) { memory(2)(PrechargeRow) := ROWBuffer }
      when(PrechargeBank === 3.U) { memory(3)(PrechargeRow) := ROWBuffer }
      PrechargePending := false.B
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
    val sdram_bundle = IO(new SDRAMIO)

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
    val sdram_bundle = IO(new SDRAMIO)

    val msdram = Module(new sdram_top_apb)
    msdram.io.clock := clock
    msdram.io.reset := reset.asBool
    msdram.io.in <> in
    sdram_bundle <> msdram.io.sdram
  }
}
