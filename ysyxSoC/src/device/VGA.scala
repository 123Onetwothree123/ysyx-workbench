package ysyx

import chisel3._
import chisel3.util._

import freechips.rocketchip.amba.apb._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

class VGAIO extends Bundle {
  val r = Output(UInt(8.W))
  val g = Output(UInt(8.W))
  val b = Output(UInt(8.W))
  val hsync = Output(Bool())
  val vsync = Output(Bool())
  val valid = Output(Bool())
}

class VGACtrlIO(val dataBits: Int = 32) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())
  val in = Flipped(
    new APBBundle(APBBundleParameters(addrBits = 32, dataBits = dataBits))
  )
  val vga = new VGAIO
}

class vga_top_apb extends BlackBox {
  val io = IO(new VGACtrlIO)
}

class vgaChisel(val dataBits: Int = 32) extends Module {
  require(dataBits == 32 || dataBits == 64, s"VGA APB 宽度必须是 32 或 64，当前值 $dataBits")
  val io = IO(new VGACtrlIO(dataBits))
  val apb = io.in
  val HFront = 96; val HActive = 144; val HBack = 784; val HTotal = 800
  val VFront = 2; val VActive = 35; val VBack = 515; val VTotal = 525
  val XCnt = RegInit(1.U(10.W))
  val YCnt = RegInit(1.U(10.W))
  val FrameBuffer = Mem(640 * 480, Vec(4, UInt(8.W)))
  when(reset.asBool) {
    XCnt := 1.U
    YCnt := 1.U
  }.otherwise {
    XCnt := Mux(XCnt === HTotal.U, 1.U, XCnt + 1.U)
    YCnt := Mux(
      YCnt === VTotal.U && XCnt === HTotal.U,
      1.U,
      Mux(XCnt === HTotal.U, YCnt + 1.U, YCnt)
    )
  }
  val HValid = XCnt > HActive.U && XCnt <= HBack.U
  val VValid = YCnt > VActive.U && YCnt <= VBack.U
  val Valid = HValid && VValid
  val HAddr = Mux(HValid, XCnt - (HActive + 1).U, 0.U)
  val VAddr = Mux(VValid, YCnt - (VActive + 1).U, 0.U)
  val PixIdx = VAddr * 640.U + HAddr
  val PixData = FrameBuffer(PixIdx)
  io.vga.hsync := XCnt > HFront.U
  io.vga.vsync := YCnt > VFront.U
  io.vga.valid := Valid
  io.vga.r := Mux(Valid, PixData(2), 0.U)
  io.vga.g := Mux(Valid, PixData(1), 0.U)
  io.vga.b := Mux(Valid, PixData(0), 0.U)
  //拿来APB写帧缓冲的,按pstrb字节使能写,支持sb/sh/sl
  val ByteAddr = (apb.paddr - "h21000000".U)(20, 0)
  val WriteAddr =
    if (dataBits == 64) Cat(ByteAddr(20, 3), 0.U(1.W)) else ByteAddr(20, 2)
  val LowWord = VecInit(Seq.tabulate(4)(i => apb.pwdata(8 * i + 7, 8 * i)))
  when(apb.psel && apb.penable && apb.pwrite) {
    when(apb.pstrb(3, 0).orR) {
      FrameBuffer.write(WriteAddr, LowWord, apb.pstrb(3, 0).asBools)
    }
    if (dataBits == 64) {
      val HighWord = VecInit(Seq.tabulate(4)(i => apb.pwdata(32 + 8 * i + 7, 32 + 8 * i)))
      when(apb.pstrb(7, 4).orR) {
        FrameBuffer.write(WriteAddr + 1.U, HighWord, apb.pstrb(7, 4).asBools)
      }
    }
  }
  apb.prdata := 0.U(dataBits.W)
  apb.pready := true.B
  apb.pslverr := false.B
}

class APBVGA(
    address: Seq[AddressSet],
    val xlen: Int = Config.peripheralXlen("vga")
)(implicit p: Parameters)
    extends LazyModule {
  private val busBytes = APBConfig.apbBytes
  private val coreBytes = APBConfig.checkXlen(xlen, "vga") / 8

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
    val vga_bundle = IO(new VGAIO)

    // val mvga = Module(new vga_top_apb)
    val mvga = Module(new vgaChisel(coreBytes * 8))
    mvga.io.clock := clock
    mvga.io.reset := reset
    if (busBytes == coreBytes) {
      mvga.io.in <> in
    } else {
      val bridge = Module(new APBWidthConverter(
        in.paddr.getWidth, busBytes * 8, coreBytes * 8))
      bridge.io.clock := clock
      bridge.io.reset := reset
      bridge.io.in <> in
      mvga.io.in <> bridge.io.out
    }
    vga_bundle <> mvga.io.vga
  }
}
