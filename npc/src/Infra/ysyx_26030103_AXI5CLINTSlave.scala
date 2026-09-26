package ysyx_26030103.infra
import chisel3._
import chisel3.util._
class ysyx_26030103_AXI5CLINTSlave extends Module {
  val io = IO(new ysyx_26030103_AXI5Slave(32))
  io.ACLK := clock.asBool
  io.ARESETn := !reset.asBool
  val OKAY = 0.U(2.W)
  val SLVERR = 2.U(2.W)
  val MtimeLowAddress = "h0200bff8".U(32.W) // RISC-V标准CLINT的mtime寄存器
  val MtimeHighAddress = "h0200bffc".U(32.W)
  val Mtime = Module(new ysyx_26030103_mtime)
  // 日常默认值，都复制粘贴了
  val AWValidReg = RegInit(false.B)
  val WValidReg = RegInit(false.B)
  val WLastReg = RegInit(false.B)
  val BValidReg = RegInit(false.B)
  val AWIDReg = RegInit(0.U(4.W))
  val BRESPReg = RegInit(OKAY)
  val RValidReg = RegInit(false.B)
  val ARIDReg = RegInit(0.U(4.W))
  val ARAddrReg = RegInit(0.U(32.W))
  val ARLenReg = RegInit(0.U(8.W))
  val ARSizeReg = RegInit(2.U(3.W))
  val ARBurstReg = RegInit(0.U(2.W))
  val ARBeatReg = RegInit(0.U(8.W))
  val ARConfigErrorReg = RegInit(false.B)
  val RDataReg = RegInit(0.U(32.W))
  val RRESPReg = RegInit(OKAY)
  val RLastReg = RegInit(false.B)
  io.AW.AWREADY := !AWValidReg && !BValidReg
  io.W.WREADY := !WValidReg && !BValidReg
  io.B.BID := AWIDReg
  io.B.BVALID := BValidReg
  io.B.BRESP := BRESPReg
  io.AR.ARREADY := !RValidReg
  io.R.RID := ARIDReg
  io.R.RVALID := RValidReg
  io.R.RDATA := RDataReg
  io.R.RRESP := RRESPReg
  io.R.RLAST := RValidReg && RLastReg
  val AWFire = io.AW.AWVALID && io.AW.AWREADY
  val WFire = io.W.WVALID && io.W.WREADY
  val BFire = io.B.BVALID && io.B.BREADY
  val ARFire = io.AR.ARVALID && io.AR.ARREADY
  val RFire = io.R.RVALID && io.R.RREADY
  when(AWFire) {
    AWIDReg := io.AW.AWID
    AWValidReg := true.B
  }
  when(WFire) {
    WValidReg := true.B
    WLastReg := io.W.WLAST
  }
  when(AWValidReg && WValidReg && !BValidReg) {
    // ysyx_26030103_mtime不让写；仍必须先排空完整的W burst。
    when(WLastReg) {
      BRESPReg := SLVERR
      BValidReg := true.B
      AWValidReg := false.B
      WValidReg := false.B
      WLastReg := false.B
    }.otherwise {
      // A multi-beat write is drained one beat at a time.  Keep AWValid so
      // the following W beat remains associated with the same transaction.
      WValidReg := false.B
      WLastReg := false.B
    }
  }
  when(BFire) {
    BValidReg := false.B
    WLastReg := false.B
  }
  def IsMtime(address: UInt): Bool =
    address === MtimeLowAddress || address === MtimeHighAddress

  val ARStep = MuxLookup(ARSizeReg, 0.U(32.W))(
    Seq(
      0.U -> 1.U(32.W),
      1.U -> 2.U(32.W),
      2.U -> 4.U(32.W)
    )
  )
  val NextARAddr = Mux(ARBurstReg === 1.U, ARAddrReg + ARStep, ARAddrReg)
  // Select the address whose response is being registered at this edge.  The
  // data itself must be captured because mtime continues changing while the
  // master is allowed to hold RREADY low.
  val ResponseAddress = Mux(ARFire, io.AR.ARADDR, NextARAddr)
  Mtime.io.SelectHigh := ResponseAddress === MtimeHighAddress

  when(ARFire) {
    ARIDReg := io.AR.ARID
    ARAddrReg := io.AR.ARADDR
    ARLenReg := io.AR.ARLEN
    ARSizeReg := io.AR.ARSIZE
    ARBurstReg := io.AR.ARBURST
    ARBeatReg := 0.U
    ARConfigErrorReg := io.AR.ARSIZE > 2.U || io.AR.ARBURST > 1.U
    val ConfigError = io.AR.ARSIZE > 2.U || io.AR.ARBURST > 1.U
    val AddressValid = IsMtime(io.AR.ARADDR)
    RDataReg := Mux(!ConfigError && AddressValid, Mtime.io.rdata, 0.U)
    RRESPReg := Mux(!ConfigError && AddressValid, OKAY, SLVERR)
    RLastReg := io.AR.ARLEN === 0.U
    RValidReg := true.B
  }
  when(RFire) {
    when(ARBeatReg === ARLenReg) {
      RValidReg := false.B
      RLastReg := false.B
    }.otherwise {
      val NextBeat = ARBeatReg + 1.U
      val AddressValid = IsMtime(NextARAddr)
      ARAddrReg := NextARAddr
      ARBeatReg := NextBeat
      RDataReg := Mux(
        !ARConfigErrorReg && AddressValid,
        Mtime.io.rdata,
        0.U
      )
      RRESPReg := Mux(
        !ARConfigErrorReg && AddressValid,
        OKAY,
        SLVERR
      )
      RLastReg := NextBeat === ARLenReg
      RValidReg := true.B
    }
  }
}
