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

class VGACtrlIO extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())
  val in = Flipped(
    new APBBundle(APBBundleParameters(addrBits = 32, dataBits = 32))
  )
  val vga = new VGAIO
}

class vga_top_apb extends BlackBox {
  val io = IO(new VGACtrlIO)
}

class vgaChisel extends Module {
  val io = IO(new VGACtrlIO)
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
  val WriteAddr = (apb.paddr - "h21000000".U)(20, 2)
  when(apb.psel && apb.penable && apb.pwrite) {
    FrameBuffer.write(
      WriteAddr,
      VecInit(Seq(apb.pwdata(7, 0), apb.pwdata(15, 8), apb.pwdata(23, 16), apb.pwdata(31, 24))),
      apb.pstrb.asBools
    )
  }
  apb.prdata := 0.U
  apb.pready := true.B
  apb.pslverr := false.B
}

class APBVGA(address: Seq[AddressSet])(implicit p: Parameters)
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
    val vga_bundle = IO(new VGAIO)

    // val mvga = Module(new vga_top_apb)
    val mvga = Module(new vgaChisel)
    mvga.io.clock := clock
    mvga.io.reset := reset
    mvga.io.in <> in
    vga_bundle <> mvga.io.vga
  }
}
