package ysyx_26030103.infra
import _root_.ysyx_26030103.common.ysyx_26030103_KconfigDefaults
import chisel3._
class ysyx_26030103_AXI5IO(
    AddressWidth: Int = ysyx_26030103_KconfigDefaults.NpcAddressWidth,
    DataWidth: Int = ysyx_26030103_KconfigDefaults.NpcInternalBusWidth,
    IDWidth: Int = ysyx_26030103_KconfigDefaults.AXIIDWidth
) extends Bundle {
  val AW = new ysyx_26030103_AXI5AW(AddressWidth, IDWidth)
  val W = new ysyx_26030103_AXI5W(DataWidth)
  val B = new ysyx_26030103_AXI5B(IDWidth)
  val AR = new ysyx_26030103_AXI5AR(AddressWidth, IDWidth)
  val R = new ysyx_26030103_AXI5R(DataWidth, IDWidth)
}

class ysyx_26030103_AXI5RPayload(IDWidth: Int, DataWidth: Int) extends Bundle {
  val ID = UInt(IDWidth.W)
  val data = UInt(DataWidth.W)
  val response = UInt(2.W)
  val last = Bool()
}

object ysyx_26030103_AXI5Resp {
  def OKAY = 0.U(2.W)
  def EXOKAY = 1.U(2.W)
  def SLVERR = 2.U(2.W)
  def DECERR = 3.U(2.W)
}

object ysyx_26030103_AXI5Idle {
  def MasterOut(
      axiPort: ysyx_26030103_AXI5IO,
      write: Boolean = true,
      read: Boolean = true,
      BeatSize: Int = 2
  ): Unit = {
    if (write) {
      axiPort.AW.AWVALID := false.B
      axiPort.AW.AWID := 0.U
      axiPort.AW.AWADDR := 0.U
      axiPort.AW.AWLEN := 0.U
      axiPort.AW.AWSIZE := BeatSize.U
      axiPort.AW.AWBURST := 0.U
      axiPort.AW.AWPROT := 0.U
      axiPort.AW.AWCACHE := 0.U
      axiPort.AW.AWQOS := 0.U
      axiPort.W.WVALID := false.B
      axiPort.W.WDATA := 0.U
      axiPort.W.WSTRB := 0.U
      axiPort.W.WLAST := false.B
      axiPort.B.BREADY := false.B
    }
    if (read) {
      axiPort.AR.ARVALID := false.B
      axiPort.AR.ARID := 0.U
      axiPort.AR.ARADDR := 0.U
      axiPort.AR.ARLEN := 0.U
      axiPort.AR.ARSIZE := BeatSize.U
      axiPort.AR.ARBURST := 0.U
      axiPort.AR.ARPROT := 0.U
      axiPort.AR.ARCACHE := 0.U
      axiPort.AR.ARQOS := 0.U
      axiPort.R.RREADY := false.B
    }
  }

  def SlaveOut(
      axiPort: ysyx_26030103_AXI5IO,
      write: Boolean = true,
      read: Boolean = true
  ): Unit = {
    if (write) {
      axiPort.AW.AWREADY := false.B
      axiPort.W.WREADY := false.B
      axiPort.B.BVALID := false.B
      axiPort.B.BID := 0.U
      axiPort.B.BRESP := ysyx_26030103_AXI5Resp.OKAY
    }
    if (read) {
      axiPort.AR.ARREADY := false.B
      axiPort.R.RVALID := false.B
      axiPort.R.RID := 0.U
      axiPort.R.RDATA := 0.U
      axiPort.R.RRESP := ysyx_26030103_AXI5Resp.OKAY
      axiPort.R.RLAST := false.B
    }
  }
}
