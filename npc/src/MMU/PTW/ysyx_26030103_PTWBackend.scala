package ysyx_26030103.mmu

import chisel3._
import chisel3.util._

/** PTW 后端公共端口。
  *
  * independent 多 walker 簇与 pipelined 共享同一组端口，MMU 只根据
  * `MMU_PTW_ARCH` 选择具体实现。地址宽度按 XLEN/AddressWidth 参数化，
  * RV32/Sv32 保持 32/32，RV64/Sv39/Sv48 使用 64/64。
  */
class ysyx_26030103_PTWBackendIO(
    respPorts: Int,
    XLEN: Int = 32,
    AddressWidth: Int = 32,
    pteWidth: Int = 32
) extends Bundle {
  val req = Flipped(Decoupled(new ysyx_26030103_PTWRequest(XLEN, AddressWidth)))
  val resp = Vec(respPorts, Decoupled(
    new ysyx_26030103_PTWResponse(XLEN, AddressWidth, pteWidth)))
  val bus = new ysyx_26030103_PTEBusIO(AddressWidth)
  val flush = Input(Bool())
}

/** PTW 后端基类，统一 MMU 侧的类型。 */
abstract class ysyx_26030103_PTWBackend(
    respPorts: Int,
    XLEN: Int = 32,
    AddressWidth: Int = 32,
    pteWidth: Int = 32
) extends Module {
  val io = IO(new ysyx_26030103_PTWBackendIO(respPorts, XLEN, AddressWidth, pteWidth))
}
