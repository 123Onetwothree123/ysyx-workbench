package ysyx_26030103.mmu

import chisel3._
import chisel3.util._

/** MMU 的访问类型。 */
object ysyx_26030103_AccessType {
  val Fetch = 0.U(2.W)
  val Load  = 1.U(2.W)
  val Store = 2.U(2.W)
}

class ysyx_26030103_MMURequest extends Bundle {
  val id = UInt(4.W)
  val vaddr = UInt(32.W)
  val access = UInt(2.W)
  val privilege = UInt(2.W)
  val sum = Bool()
  val mxr = Bool()
  val satp = UInt(32.W)
}

class ysyx_26030103_MMUResponse extends Bundle {
  val id = UInt(4.W)
  val paddr = UInt(32.W)
  val fault = Bool()
  val cause = UInt(32.W)
  val tval = UInt(32.W)
}

/** PTW 的物理页表访问总线。
  *
  * id 用于标识一笔在途事务：PTW pipeline 可以同时发出多笔页表读，总线
  * 响应允许乱序返回，由 id 匹配回各自 B 级槽位。单笔 walker 固定使用 0。
  */
class ysyx_26030103_PTEBusRequest extends Bundle {
  val id = UInt(4.W)
  val addr = UInt(32.W)
  val write = Bool()
  val data = UInt(32.W)
  val strb = UInt(4.W)
}

class ysyx_26030103_PTEBusResponse extends Bundle {
  // 与请求相同的 id；响应方必须原样带回。
  val id = UInt(4.W)
  val data = UInt(32.W)
  val fault = Bool()
}

class ysyx_26030103_PTEBusIO extends Bundle {
  val req = Decoupled(new ysyx_26030103_PTEBusRequest)
  val resp = Flipped(Decoupled(new ysyx_26030103_PTEBusResponse))
}
