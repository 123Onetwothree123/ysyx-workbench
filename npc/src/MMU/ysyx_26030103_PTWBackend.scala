package ysyx_26030103.mmu

import chisel3._
import chisel3.util._

/** PTW 后端公共端口。
  *
  * independent 多 walker 簇与 pipelined 共享同一组端口，MMU 只根据
  * `MMU_PTW_ARCH` 选择具体实现。
  */
class ysyx_26030103_PTWBackendIO(respPorts: Int) extends Bundle {
  val req = Flipped(Decoupled(new ysyx_26030103_PTWRequest))
  val resp = Vec(respPorts, Decoupled(new ysyx_26030103_PTWResponse))
  val bus = new ysyx_26030103_PTEBusIO
  val flush = Input(Bool())
}

/** PTW 后端基类，统一 MMU 侧的类型。 */
abstract class ysyx_26030103_PTWBackend(respPorts: Int) extends Module {
  val io = IO(new ysyx_26030103_PTWBackendIO(respPorts))
}
