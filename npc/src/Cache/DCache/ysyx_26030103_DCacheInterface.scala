package ysyx_26030103.cache

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.infra.ysyx_26030103_AXI5IO

class ysyx_26030103_DCacheRequest(val AddressWidth: Int = 32) extends Bundle {
  val addr = UInt(AddressWidth.W)
  // VIPT 模式下用于选 index 的虚拟地址；addr 始终是物理地址。
  val vaddr = UInt(AddressWidth.W)
  val WidthSelect = UInt(2.W) // 0=字节，1=半字，2=字
  val signed = Bool()
}

class ysyx_26030103_DCacheResponse extends Bundle {
  val data = UInt(32.W)
  val fault = Bool()
  val FaultResp = UInt(2.W)
}

/** 数据缓存的统一对外接口。
  *
  * 行为约定：
  *   1. req/resp为valid-ready握手，一次可有一条在途请求，响应保序，
  *      命中延迟由实现自行决定；
  *   2. AXI端口只使用AR/R通道，写通道由LSU写缓冲负责；
  *   3. Store*为写缓冲匹配B响应后的事件通知，实现允许忽略，但必须接线；
  *   4. flush使全部缓存行失效（DMA等外部一致性事件）。
  */
class ysyx_26030103_DCacheInterface(val AddressWidth: Int = 32)
    extends Bundle {
  val req = Flipped(Decoupled(new ysyx_26030103_DCacheRequest(AddressWidth)))
  val resp = Decoupled(new ysyx_26030103_DCacheResponse)
  val StoreValid = Input(Bool())
  val StoreResp = Input(UInt(2.W))
  val StoreAddr = Input(UInt(AddressWidth.W))
  val StoreVAddr = Input(UInt(AddressWidth.W))
  val StoreData = Input(UInt(32.W))
  val StoreStrb = Input(UInt(4.W))
  val AXI = new ysyx_26030103_AXI5IO(AddressWidth) // DCache只使用AR/R通道
  val flush = Input(Bool())
  val perf_hit = Output(Bool())
  val perf_miss = Output(Bool())
  val perf_refill_req = Output(Bool())
  val perf_refill_resp = Output(Bool())
  val req_cacheable = Output(Bool())
  val axi_active = Output(Bool())
}
