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

class GPIOCtrlIO(val dataBits: Int = 32) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Reset())
  val in = Flipped(
    new APBBundle(APBBundleParameters(addrBits = 32, dataBits = dataBits))
  )
  val gpio = new GPIOIO
}

class gpio_top_apb extends BlackBox {
  val io = IO(new GPIOCtrlIO)
}

class gpioChisel(val dataBits: Int = 32) extends Module {
  require(dataBits == 32 || dataBits == 64, s"GPIO APB 宽度必须是 32 或 64，当前值 $dataBits")
  val io = IO(new GPIOCtrlIO(dataBits))
  val apb = io.in
  val LEDRegisters = RegInit(0.U(16.W)) // 16位数据, 分别驱动16个LED灯
  val SegmentRegisters = RegInit(0.U(32.W))
  // 低 2 位是字节 lane, 3:2 才是寄存器偏移。sb/sh 落在 offset1 时仍写 LED/Segment 对应字节
  val offset = apb.paddr(3, 2)
  val access = apb.psel && apb.penable // apb访问拍
  val WriteEnable = access && apb.pwrite
  when(WriteEnable) {
    switch(offset) {
      is(0.U) {
        for (i <- 0 until 2) {
          when(apb.pstrb(i)) {
            LEDRegisters(8 * i + 7, 8 * i) := apb.pwdata(8 * i + 7, 8 * i)
          }
        }
      }
      is(2.U) {
        for (i <- 0 until 4) {
          when(apb.pstrb(i)) {
            SegmentRegisters(8 * i + 7, 8 * i) := apb.pwdata(8 * i + 7, 8 * i)
          }
        }
      }
    }
  }
  val readWord = MuxLookup(offset, 0.U(32.W))(
    Seq(
      0.U -> LEDRegisters.pad(32),
      1.U -> io.gpio.in.pad(32),
      2.U -> SegmentRegisters
    )
  )
  apb.prdata := (if (dataBits == 64) {
    Mux(apb.paddr(2), (readWord.pad(64) << 32)(63, 0), readWord.pad(64))
  } else {
    readWord
  })
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

class APBGPIO(
    address: Seq[AddressSet],
    val xlen: Int = Config.peripheralXlen("gpio")
)(implicit p: Parameters)
    extends LazyModule {
  private val busBytes = APBConfig.apbBytes
  private val coreBytes = APBConfig.checkXlen(xlen, "gpio") / 8

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
    val gpio_bundle = IO(new GPIOIO)

    val mgpio = Module(new gpio_top_apb)
    mgpio.io.clock := clock
    mgpio.io.reset := reset
    if (busBytes == coreBytes) {
      mgpio.io.in <> in
    } else {
      val bridge = Module(new APBWidthConverter(
        in.paddr.getWidth, busBytes * 8, coreBytes * 8))
      bridge.io.clock := clock
      bridge.io.reset := reset
      bridge.io.in <> in
      mgpio.io.in <> bridge.io.out
    }
    gpio_bundle <> mgpio.io.gpio
  }
}
