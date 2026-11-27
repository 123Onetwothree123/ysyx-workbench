package ysyx_26030103
import chisel3._
private[ysyx_26030103] trait ysyx_26030103_IOParams {
  protected def SocXLEN: Int
  protected final def SocDataWidth: Int = SocXLEN
  protected final def SocStrobeWidth: Int = SocXLEN / 8
}
private[ysyx_26030103] object ysyx_26030103_IOParams {
  def requireSocXLEN(socXLEN: Int, ioName: String): Unit =
    require(socXLEN == 32 || socXLEN == 64,
      s"$ioName SocXLEN 只允许 32 或 64，收到 $socXLEN")
}
private[ysyx_26030103] trait ysyx_26030103_AXI5Payload {
  this: Bundle with ysyx_26030103_IOParams =>
  protected def masterInput[T <: Data](d: T): T
  protected def masterOutput[T <: Data](d: T): T
}
private[ysyx_26030103] trait ysyx_26030103_AXI5AWPayload
    extends ysyx_26030103_AXI5Payload {
  this: Bundle with ysyx_26030103_IOParams =>
  val awready = masterInput(Bool())
  val awvalid = masterOutput(Bool())
  val awaddr = masterOutput(UInt(SocXLEN.W))
  val awid = masterOutput(UInt(4.W))
  val awlen = masterOutput(UInt(8.W))
  val awsize = masterOutput(UInt(3.W))
  val awburst = masterOutput(UInt(2.W))
  val awlock = masterOutput(Bool())
  val awcache = masterOutput(UInt(4.W))
  val awprot = masterOutput(UInt(3.W))
  val awqos = masterOutput(UInt(4.W))
}
private[ysyx_26030103] trait ysyx_26030103_AXI5WPayload
    extends ysyx_26030103_AXI5Payload {
  this: Bundle with ysyx_26030103_IOParams =>
  val wready = masterInput(Bool())
  val wvalid = masterOutput(Bool())
  val wdata = masterOutput(UInt(SocDataWidth.W))
  val wstrb = masterOutput(UInt(SocStrobeWidth.W))
  val wlast = masterOutput(Bool())
}
private[ysyx_26030103] trait ysyx_26030103_AXI5BPayload
    extends ysyx_26030103_AXI5Payload {
  this: Bundle with ysyx_26030103_IOParams =>
  val bready = masterOutput(Bool())
  val bvalid = masterInput(Bool())
  val bresp = masterInput(UInt(2.W))
  val bid = masterInput(UInt(4.W))
}
private[ysyx_26030103] trait ysyx_26030103_AXI5ARPayload
    extends ysyx_26030103_AXI5Payload {
  this: Bundle with ysyx_26030103_IOParams =>
  val arready = masterInput(Bool())
  val arvalid = masterOutput(Bool())
  val araddr = masterOutput(UInt(SocXLEN.W))
  val arid = masterOutput(UInt(4.W))
  val arlen = masterOutput(UInt(8.W))
  val arsize = masterOutput(UInt(3.W))
  val arburst = masterOutput(UInt(2.W))
  val arlock = masterOutput(Bool())
  val arcache = masterOutput(UInt(4.W))
  val arprot = masterOutput(UInt(3.W))
  val arqos = masterOutput(UInt(4.W))
}
private[ysyx_26030103] trait ysyx_26030103_AXI5RPayload
    extends ysyx_26030103_AXI5Payload {
  this: Bundle with ysyx_26030103_IOParams =>
  val rready = masterOutput(Bool())
  val rvalid = masterInput(Bool())
  val rresp = masterInput(UInt(2.W))
  val rdata = masterInput(UInt(SocDataWidth.W))
  val rlast = masterInput(Bool())
  val rid = masterInput(UInt(4.W))
}
