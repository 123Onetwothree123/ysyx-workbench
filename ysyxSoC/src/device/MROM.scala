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

class AXI4MROM(address: Seq[AddressSet])(implicit p: Parameters) extends LazyModule {
  val beatBytes = 4
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

    val mrom = Module(new MROMHelper)

    // MROMHelper is a synchronous one-cycle read.  Keep a load state between
    // beats so its registered output is never overwritten while RREADY is
    // low.  In particular, only launch the next memory read after the current
    // R beat has actually handshaken.
    val stateIdle :: stateLoad :: stateActive :: Nil = Enum(3)
    val state = RegInit(stateIdle)

    val burst_len  = RegInit(0.U(8.W))
    val beat_cnt   = RegInit(0.U(8.W))
    val addr_reg   = RegInit(0.U(32.W))

    val next_addr = addr_reg + 4.U

    mrom.io.clock := clock
    mrom.io.raddr := Mux(in.ar.fire, in.ar.bits.addr, next_addr)
    mrom.io.ren := in.ar.fire ||
      ((state === stateActive) && in.r.ready &&
        (beat_cnt =/= burst_len))

    in.ar.ready := (state === stateIdle)
    when(in.ar.fire) {
      state      := stateLoad
      burst_len  := in.ar.bits.len
      beat_cnt   := 0.U
      addr_reg   := in.ar.bits.addr
    }

    when(state === stateLoad) {
      state := stateActive
    }

    in.r.bits.data := mrom.io.rdata
    in.r.bits.id   := RegEnable(in.ar.bits.id, in.ar.fire)
    in.r.bits.resp := 0.U
    in.r.bits.last := (state === stateActive) && (beat_cnt === burst_len)
    in.r.valid := (state === stateActive)

    when(in.r.fire) {
      when(beat_cnt === burst_len) {
        state := stateIdle
      }.otherwise {
        state := stateLoad
        beat_cnt := beat_cnt + 1.U
        addr_reg := next_addr
      }
    }

    in.aw.ready := false.B
    in. w.ready := false.B
    in. b.valid := false.B
  }
}
