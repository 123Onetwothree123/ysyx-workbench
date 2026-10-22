package ysyx_26030103.mmu

import chisel3._
import chisel3.util._

/** MMU 的访问类型。 */
object ysyx_26030103_AccessType {
  val Fetch = 0.U(2.W)
  val Load  = 1.U(2.W)
  val Store = 2.U(2.W)
}

/** 架构虚拟地址/SATP 宽度为 XLEN，物理地址宽度为 AddressWidth。
  *
  * RV32/Sv32 使用默认值 32/32，端子宽度与既有实现完全一致；RV64/Sv39
  * 使用 64/64，satp 的 MODE/ASID/PPN 字段全部保留。
  */
class ysyx_26030103_MMURequest(
    val XLEN: Int = 32,
    val AddressWidth: Int = 32
) extends Bundle {
  require(XLEN == 32 || XLEN == 64, s"MMURequest XLEN 非法: $XLEN")
  require(AddressWidth == 32 || AddressWidth == 64,
    s"MMURequest AddressWidth 非法: $AddressWidth")
  val id = UInt(4.W)
  val vaddr = UInt(XLEN.W)
  val access = UInt(2.W)
  val privilege = UInt(2.W)
  val sum = Bool()
  val mxr = Bool()
  val satp = UInt(XLEN.W)
}

class ysyx_26030103_MMUResponse(
    val XLEN: Int = 32,
    val AddressWidth: Int = 32
) extends Bundle {
  require(XLEN == 32 || XLEN == 64, s"MMUResponse XLEN 非法: $XLEN")
  require(AddressWidth == 32 || AddressWidth == 64,
    s"MMUResponse AddressWidth 非法: $AddressWidth")
  val id = UInt(4.W)
  val paddr = UInt(AddressWidth.W)
  val fault = Bool()
  val cause = UInt(32.W)
  val tval = UInt(XLEN.W)
}

/** PTW 的物理页表访问总线。
  *
  * id 用于标识一笔在途事务：PTW pipeline 可以同时发出多笔页表读，总线
  * 响应允许乱序返回，由 id 匹配回各自 B 级槽位。单笔 walker 固定使用 0。
  * 总线数据仍为 32 位：8B PTE 由低/高两个 32 位字组成，A/D 写回也只
  * 修改包含标志位的低字；地址宽度跟随 AddressWidth（RV64 为 64 位）。
  */
class ysyx_26030103_PTEBusRequest(val AddressWidth: Int = 32) extends Bundle {
  require(AddressWidth == 32 || AddressWidth == 64,
    s"PTEBusRequest AddressWidth 非法: $AddressWidth")
  val id = UInt(4.W)
  val addr = UInt(AddressWidth.W)
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

class ysyx_26030103_PTEBusIO(val AddressWidth: Int = 32) extends Bundle {
  val req = Decoupled(new ysyx_26030103_PTEBusRequest(AddressWidth))
  val resp = Flipped(Decoupled(new ysyx_26030103_PTEBusResponse))
}
