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

class PS2CtrlIO(val dataBits: Int = 32) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())
  val in = Flipped(
    new APBBundle(APBBundleParameters(addrBits = 32, dataBits = dataBits))
  )
  val ps2 = new PS2IO
}

class ps2_top_apb extends BlackBox {
  val io = IO(new PS2CtrlIO)
}

class ps2Chisel(val dataBits: Int = 32) extends Module {
  require(dataBits == 32 || dataBits == 64, s"PS2 APB 宽度必须是 32 或 64，当前值 $dataBits")
  val io = IO(new PS2CtrlIO(dataBits))
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

  val readByte = Mux(Ready, FIFO(RPtr), 0.U(8.W))
  apb.prdata := (if (dataBits == 64) {
    Mux(apb.paddr(2), (readByte.pad(64) << 32)(63, 0), readByte.pad(64))
  } else {
    readByte.pad(32)
  })
  apb.pready := true.B
  apb.pslverr := false.B
}

class APBKeyboard(
    address: Seq[AddressSet],
    val xlen: Int = Config.peripheralXlen("keyboard")
)(implicit p: Parameters)
    extends LazyModule {
  private val busBytes = APBConfig.apbBytes
  private val coreBytes = APBConfig.checkXlen(xlen, "keyboard") / 8

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
        beatBytes = busBytes
      )
    )
  )

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val (in, _) = node.in(0)
    val ps2_bundle = IO(new PS2IO)

    // val mps2 = Module(new ps2_top_apb)
    val mps2 = Module(new ps2Chisel(coreBytes * 8))
    mps2.io.clock := clock
    mps2.io.reset := reset
    if (busBytes == coreBytes) {
      mps2.io.in <> in
    } else {
      val bridge = Module(new APBWidthConverter(
        in.paddr.getWidth, busBytes * 8, coreBytes * 8))
      bridge.io.clock := clock
      bridge.io.reset := reset
      bridge.io.in <> in
      mps2.io.in <> bridge.io.out
    }
    ps2_bundle <> mps2.io.ps2
  }
}
