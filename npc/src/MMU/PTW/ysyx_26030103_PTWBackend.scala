package ysyx_26030103.mmu
import _root_.ysyx_26030103.common.ysyx_26030103_KconfigDefaults

import chisel3._
import chisel3.util._

// PTW 后端公共端口：independent 多 walker 簇与 pipelined 共用，MMU 按
// `MMU_PTW_ARCH` 选择实现；地址宽度按 XLEN/AddressWidth 参数化。
class ysyx_26030103_PTWBackendIO(
    respPorts: Int,
    XLEN: Int = ysyx_26030103_KconfigDefaults.NpcXlen,
    AddressWidth: Int = ysyx_26030103_KconfigDefaults.NpcAddressWidth,
    pteWidth: Int = ysyx_26030103_KconfigDefaults.MmuPteWidth
) extends Bundle {
  val req = Flipped(Decoupled(new ysyx_26030103_PTWRequest(XLEN, AddressWidth)))
  val resp = Vec(respPorts, Decoupled(
    new ysyx_26030103_PTWResponse(XLEN, AddressWidth, pteWidth)))
  val bus = new ysyx_26030103_PTEBusIO(AddressWidth)
  val flush = Input(Bool())
}

// PTW 后端基类，统一 MMU 侧的类型。
abstract class ysyx_26030103_PTWBackend(
    respPorts: Int,
    XLEN: Int = ysyx_26030103_KconfigDefaults.NpcXlen,
    AddressWidth: Int = ysyx_26030103_KconfigDefaults.NpcAddressWidth,
    pteWidth: Int = ysyx_26030103_KconfigDefaults.MmuPteWidth
) extends Module {
  val io = IO(new ysyx_26030103_PTWBackendIO(respPorts, XLEN, AddressWidth, pteWidth))
}
