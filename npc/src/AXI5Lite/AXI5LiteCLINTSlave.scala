package RV32I.AXI5Lite
import chisel3._
import chisel3.util._
class AXI5LiteCLINTSlave extends Module {
  val io = IO(new AXI5LiteSlave(32))
  io.ACLK := clock.asBool
  io.ARESETn := !reset.asBool
  val OKAY = 0.U(2.W)
  val SLVERR = 2.U(2.W)
  val MtimeLowAddress = "ha0000048".U(32.W) // AM的地址
  val MtimeHighAddress = "ha000004c".U(32.W)
  val Mtime = Module(new mtime)
  // 日常默认值，都复制粘贴了
  val AWValidReg = RegInit(false.B)
  val WValidReg = RegInit(false.B)
  val BValidReg = RegInit(false.B)
  val BRESPReg = RegInit(OKAY)
  val RValidReg = RegInit(false.B)
  val RDataReg = RegInit(0.U(32.W))
  val RRESPReg = RegInit(OKAY)
  io.AW.AWREADY := !AWValidReg && !BValidReg
  io.W.WREADY := !WValidReg && !BValidReg
  io.B.BVALID := BValidReg
  io.B.BRESP := BRESPReg
  io.AR.ARREADY := !RValidReg
  io.R.RVALID := RValidReg
  io.R.RDATA := RDataReg
  io.R.RRESP := RRESPReg
  val AWFire = io.AW.AWVALID && io.AW.AWREADY
  val WFire = io.W.WVALID && io.W.WREADY
  val BFire = io.B.BVALID && io.B.BREADY
  val ARFire = io.AR.ARVALID && io.AR.ARREADY
  val RFire = io.R.RVALID && io.R.RREADY
  when(AWFire) {
    AWValidReg := true.B
  }
  when(WFire) {
    WValidReg := true.B
  }
  when(AWValidReg && WValidReg && !BValidReg) {
    // mtime不让写
    BRESPReg := SLVERR
    BValidReg := true.B
    AWValidReg := false.B
    WValidReg := false.B
  }
  when(BFire) {
    BValidReg := false.B
  }
  when(RFire) {
    RValidReg := false.B
  }
  val IsMtimeLow = io.AR.ARADDR === MtimeLowAddress
  val IsMtimeHigh = io.AR.ARADDR === MtimeHighAddress
  val IsMtime = IsMtimeLow || IsMtimeHigh
  Mtime.io.SelectHigh := IsMtimeHigh
  when(ARFire) {
    RDataReg := Mtime.io.rdata
    RRESPReg := Mux(IsMtime, OKAY, SLVERR)
    RValidReg := true.B
  }
}
