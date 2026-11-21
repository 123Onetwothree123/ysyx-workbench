package ysyx

import chisel3._
import chisel3.util._

import freechips.rocketchip.amba.axi4._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

class MROMHelper extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val raddr = Input(UInt(32.W))
    val ren = Input(Bool())
    val rdata = Output(UInt(32.W))
  })
  setInline("MROMHelper.v",
    """module MROMHelper(
      |  input         clock,
      |  input [31:0] raddr,
      |  input        ren,
      |  output reg [31:0] rdata
      |);
      |import "DPI-C" function void mrom_read(input int raddr, output int rdata);
      |reg [31:0] dpi_data;
      |always @(*) mrom_read(raddr, dpi_data);
      |always @(posedge clock) if (ren) rdata <= dpi_data;
      |endmodule
    """.stripMargin)
}

class AXI4MROM(
    address: Seq[AddressSet],
    beatBytes: Int = Config.peripheralXlen("mrom") / 8
)(implicit p: Parameters) extends LazyModule {
  require(beatBytes == 4 || beatBytes == 8,
    s"AXI4MROM 只支持 4/8 字节 beat，收到 $beatBytes")
  val node = AXI4SlaveNode(Seq(AXI4SlavePortParameters(
    Seq(AXI4SlaveParameters(
        address       = address,
        executable    = true,
        supportsWrite = TransferSizes.none,
        supportsRead  = TransferSizes(1, beatBytes * 16),
        interleavedId = Some(0))
    ),
    beatBytes  = beatBytes)))

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val (in, _) = node.in(0)

    val Low = Module(new MROMHelper)
    val High = if (beatBytes == 8) Some(Module(new MROMHelper)) else None

    val stateIdle :: stateLoad :: stateActive :: Nil = Enum(3)
    val state = RegInit(stateIdle)
    val burstLength = RegInit(0.U(8.W))
    val beatCount = RegInit(0.U(8.W))
    val addressReg = RegInit(0.U(32.W))
    val sizeReg = RegInit(2.U(3.W))

    def wordBase(value: UInt): UInt =
      if (beatBytes == 8) (value >> 3) << 3 else (value >> 2) << 2

    val sizeBytes = 1.U(32.W) << sizeReg
    val alignedNext = (addressReg | (sizeBytes - 1.U)) + 1.U
    val nextAddress =
      Mux(beatCount === 0.U, alignedNext, addressReg + sizeBytes)
    val NextBase = wordBase(nextAddress)

    Low.io.clock := clock
    Low.io.raddr := Mux(in.ar.fire, wordBase(in.ar.bits.addr), NextBase)
    Low.io.ren := in.ar.fire ||
      ((state === stateActive) && in.r.ready && (beatCount =/= burstLength))
    High.foreach { m =>
      m.io.clock := clock
      m.io.raddr := Mux(in.ar.fire, wordBase(in.ar.bits.addr) + 4.U,
        NextBase + 4.U)
      m.io.ren := Low.io.ren
    }

    in.ar.ready := state === stateIdle
    when(in.ar.fire) {
      state := stateLoad
      burstLength := in.ar.bits.len
      beatCount := 0.U
      addressReg := in.ar.bits.addr
      sizeReg := in.ar.bits.size
    }
    when(state === stateLoad) {
      state := stateActive
    }

    in.r.bits.data := High
      .map(m => Cat(m.io.rdata, Low.io.rdata))
      .getOrElse(Low.io.rdata)
    in.r.bits.id := RegEnable(in.ar.bits.id, in.ar.fire)
    in.r.bits.resp := 0.U
    in.r.bits.last := (state === stateActive) && (beatCount === burstLength)
    in.r.valid := state === stateActive

    when(in.r.fire) {
      when(beatCount === burstLength) {
        state := stateIdle
      }.otherwise {
        state := stateLoad
        beatCount := beatCount + 1.U
        addressReg := nextAddress
      }
    }

    in.aw.ready := false.B
    in.w.ready := false.B
    in.b.valid := false.B
    assert(!in.ar.valid || in.ar.bits.size <= log2Ceil(beatBytes).U,
      "AXI4MROM: read size exceeds beat width")
  }
}
