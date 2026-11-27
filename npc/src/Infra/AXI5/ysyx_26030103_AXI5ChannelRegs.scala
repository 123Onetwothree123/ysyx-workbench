package ysyx_26030103.infra

import chisel3._
import chisel3.util._

private[infra] class ysyx_26030103_AXI5AWChannelRegs(
    AddressWidth: Int,
    IDWidth: Int
) extends Bundle {
  val valid = Bool()
  val id = UInt(IDWidth.W)
  val address = UInt(AddressWidth.W)
}

private[infra] class ysyx_26030103_AXI5WChannelRegs(DataWidth: Int) extends Bundle {
  val valid = Bool()
  val last = Bool()
  val data = UInt(DataWidth.W)
  val strobe = UInt((DataWidth / 8).W)
}

private[infra] class ysyx_26030103_AXI5BChannelRegs extends Bundle {
  val valid = Bool()
  val response = UInt(2.W)
}

private[infra] class ysyx_26030103_AXI5RChannelRegs(
    DataWidth: Int,
    IDWidth: Int
) extends Bundle {
  val valid = Bool()
  val id = UInt(IDWidth.W)
  val data = UInt(DataWidth.W)
  val response = UInt(2.W)
  val last = Bool()
}

private[infra] class ysyx_26030103_AXI5ARChannelRegs(
    AddressWidth: Int,
    IDWidth: Int
) extends Bundle {
  val valid = Bool()
  val id = UInt(IDWidth.W)
  val address = UInt(AddressWidth.W)
  val length = UInt(8.W)
}

private[infra] object ysyx_26030103_AXI5ChannelRegs {
  def apply[T <: Bundle](gen: => T): T = RegInit(0.U.asTypeOf(gen))
}

private[infra] trait ysyx_26030103_AXI5ChannelFire {
  def AwFire(valid: Bool, ready: Bool): Bool = valid && ready
  def WFire(valid: Bool, ready: Bool): Bool = valid && ready
  def BFire(valid: Bool, ready: Bool): Bool = valid && ready
  def ArFire(valid: Bool, ready: Bool): Bool = valid && ready
  def RFire(valid: Bool, ready: Bool): Bool = valid && ready
}
