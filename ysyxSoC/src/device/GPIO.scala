package ysyx

import chisel3._
import chisel3.util._

import freechips.rocketchip.amba.apb._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

class GPIOIO extends Bundle {
  val out = Output(UInt(16.W))
  val in = Input(UInt(16.W))
  val seg = Output(Vec(8, UInt(8.W)))
}

class GPIOCtrlIO extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Reset())
  val in = Flipped(
    new APBBundle(APBBundleParameters(addrBits = 32, dataBits = 32))
  )
  val gpio = new GPIOIO
}

class gpio_top_apb extends BlackBox {
  val io = IO(new GPIOCtrlIO)
}

class gpioChisel extends Module {
  val io = IO(new GPIOCtrlIO)
  val apb = io.in
  val LEDRegisters = RegInit(0.U(16.W)) // 16位数据, 分别驱动16个LED灯
  val SegmentRegisters = RegInit(0.U(32.W))
  val offset = apb.paddr(3, 0)
  val access = apb.psel && apb.penable // apb访问拍
  val WriteEnable = access && apb.pwrite
  when(WriteEnable) {
    switch(offset) {
      is("h0".U) {
        LEDRegisters := apb.pwdata(15, 0)
      }
      is("h8".U) {
        SegmentRegisters := apb.pwdata
      }
    }
  }
  apb.prdata := MuxLookup(offset, 0.U)(
    Seq(
      "h0".U -> LEDRegisters,
      "h4".U -> io.gpio.in,
      "h8".U -> SegmentRegisters
    )
  )
  apb.pready := true.B
  apb.pslverr := false.B
  // 驱动引脚
  io.gpio.out := LEDRegisters
  def seg7(hex: UInt): UInt = MuxLookup(hex, "hff".U(8.W))(
    Seq(
      "h0".U -> "h03".U,
      "h1".U -> "h9f".U,
      "h2".U -> "h25".U,
      "h3".U -> "h0d".U,
      "h4".U -> "h99".U,
      "h5".U -> "h49".U,
      "h6".U -> "h41".U,
      "h7".U -> "h1f".U,
      "h8".U -> "h01".U,
      "h9".U -> "h09".U,
      "ha".U -> "h11".U,
      "hb".U -> "hc1".U,
      "hc".U -> "h63".U,
      "hd".U -> "h85".U,
      "he".U -> "h61".U,
      "hf".U -> "h71".U
    )
  )
  for (i <- 0 until 8) {
    io.gpio.seg(i) := seg7(SegmentRegisters(4 * i + 3, 4 * i))
  }
}

class APBGPIO(address: Seq[AddressSet])(implicit p: Parameters)
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
    val gpio_bundle = IO(new GPIOIO)

    // val mgpio = Module(new gpio_top_apb)
    val mgpio = Module(new gpioChisel)
    mgpio.io.clock := clock
    mgpio.io.reset := reset
    mgpio.io.in <> in
    gpio_bundle <> mgpio.io.gpio
  }
}
