package ysyx_26030103.ysyx_26030103_AXI5
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
  val BValidReg = RegInit(false.B)
  val AWIDReg = RegInit(0.U(4.W))
  val BRESPReg = RegInit(OKAY)
  val RValidReg = RegInit(false.B)
  val ARIDReg = RegInit(0.U(4.W))
  val RDataReg = RegInit(0.U(32.W))
  val RRESPReg = RegInit(OKAY)
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
  io.R.RLAST := RValidReg
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
  }
  when(AWValidReg && WValidReg && !BValidReg) {
    // ysyx_26030103_mtime不让写
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
    ARIDReg := io.AR.ARID
    RDataReg := Mtime.io.rdata
    RRESPReg := Mux(IsMtime, OKAY, SLVERR)
    RValidReg := true.B
  }
}
