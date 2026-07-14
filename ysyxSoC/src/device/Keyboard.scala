package ysyx

import chisel3._
import chisel3.util._

import freechips.rocketchip.amba.apb._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

class PS2IO extends Bundle {
  val clk = Input(Bool())
  val data = Input(Bool())
}

class PS2CtrlIO extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())
  val in = Flipped(
    new APBBundle(APBBundleParameters(addrBits = 32, dataBits = 32))
  )
  val ps2 = new PS2IO
}

class ps2_top_apb extends BlackBox {
  val io = IO(new PS2CtrlIO)
}

class ps2Chisel extends Module {
  val io = IO(new PS2CtrlIO)
  val apb = io.in

  val CLKSync = RegInit(VecInit(Seq.fill(3)(false.B)))
  CLKSync(0) := io.ps2.clk
  CLKSync(1) := CLKSync(0)
  CLKSync(2) := CLKSync(1)
  val Sampling = CLKSync(2) && !CLKSync(1)

  val Buffer = RegInit(0.U(10.W))
  val Count = RegInit(0.U(4.W))

  val FIFO = Reg(Vec(8, UInt(8.W)))
  val WPtr = RegInit(0.U(3.W))
  val RPtr = RegInit(0.U(3.W))
  val Ready = RegInit(false.B)
  val Overflow = RegInit(false.B)

  when(reset.asBool) {
    Count := 0.U
    WPtr := 0.U
    RPtr := 0.U
    Ready := false.B
    Overflow := false.B
  }.otherwise {
    when(Ready && apb.psel && apb.penable && !apb.pwrite) {
      RPtr := RPtr + 1.U
      when(WPtr === RPtr + 1.U) { Ready := false.B }
    }
    when(Sampling) {
      when(Count === 10.U) {
        when(!Buffer(0) && io.ps2.data && Buffer(9, 1).xorR) {
          FIFO(WPtr) := Buffer(8, 1)
          WPtr := WPtr + 1.U
          Ready := true.B
          Overflow := Overflow || (RPtr === WPtr + 1.U)
        }
        Count := 0.U
      }.otherwise {
        Buffer := Cat(io.ps2.data, Buffer(9, 1))
        Count := Count + 1.U
      }
    }
  }

  apb.prdata := Mux(Ready, FIFO(RPtr), 0.U)
  apb.pready := true.B
  apb.pslverr := false.B
}

class APBKeyboard(address: Seq[AddressSet])(implicit p: Parameters)
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
    val ps2_bundle = IO(new PS2IO)

    // val mps2 = Module(new ps2_top_apb)
    val mps2 = Module(new ps2Chisel)
    mps2.io.clock := clock
    mps2.io.reset := reset
    mps2.io.in <> in
    ps2_bundle <> mps2.io.ps2
  }
}
