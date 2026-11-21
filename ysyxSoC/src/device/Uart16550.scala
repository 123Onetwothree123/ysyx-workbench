package ysyx

import chisel3._
import chisel3.util._

import freechips.rocketchip.amba.apb._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

class UARTIO extends Bundle {
  val rx = Input(Bool())
  val tx = Output(Bool())
}

class uart_top_apb extends BlackBox {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Reset())
    val in = Flipped(new APBBundle(APBBundleParameters(addrBits = 32, dataBits = 32)))
    val uart = new UARTIO
    // opencores 16550 的 int_o：RX/THRE 等中断的电平汇总，接 PLIC。
    val interrupt = Output(Bool())
  })
}

class APBUart16550(
    address: Seq[AddressSet],
    val xlen: Int = Config.peripheralXlen("uart")
)(implicit p: Parameters)
    extends LazyModule {
  APBConfig.checkXlen(xlen, "uart")
  private val busBytes = APBConfig.apbBytes
  private val coreBytes = 4 // opencores 16550 固定 32 位寄存器接口

  val node = APBSlaveNode(Seq(APBSlavePortParameters(
    Seq(APBSlaveParameters(
      address       = address,
      executable    = false,
      supportsRead  = true,
      supportsWrite = true)),
    beatBytes  = busBytes)))

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val (in, _) = node.in(0)
    val uart = IO(new UARTIO)
    val interrupt = IO(Output(Bool()))

    val muart = Module(new uart_top_apb)
    muart.io.clock := clock
    muart.io.reset := reset
    if (busBytes == coreBytes) {
      muart.io.in <> in
    } else {
      val bridge = Module(new APBWidthConverter(
        in.paddr.getWidth, busBytes * 8, coreBytes * 8))
      bridge.io.clock := clock
      bridge.io.reset := reset
      bridge.io.in <> in
      muart.io.in <> bridge.io.out
    }
    uart <> muart.io.uart
    interrupt := muart.io.interrupt
  }
}
