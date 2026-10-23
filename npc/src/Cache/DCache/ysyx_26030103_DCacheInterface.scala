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

class ysyx_26030103_DCacheResponse(val DataWidth: Int = 32) extends Bundle {
  require(DataWidth == 32 || DataWidth == 64,
    s"DCacheResponse DataWidth 非法: $DataWidth")
  val data = UInt(DataWidth.W)
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
class ysyx_26030103_DCacheInterface(
    val AddressWidth: Int = 32,
    val DataWidth: Int = 32
) extends Bundle {
  val req = Flipped(Decoupled(new ysyx_26030103_DCacheRequest(AddressWidth)))
  // VIPT 并行路径的翻译结果：PA 可能晚于 req 到达，paddr_vaddr 是这笔
  // PA 对应的虚拟地址，用作“迟到 PA”的归属标签。PIPT 模式忽略这两个端口。
  val req_paddr_valid = Input(Bool())
  val req_paddr_vaddr = Input(UInt(AddressWidth.W))
  // MMU 翻译故障：放弃已经受理但尚未完成 PA 判定的请求，不产生响应。
  val req_kill = Input(Bool())
  val resp = Decoupled(new ysyx_26030103_DCacheResponse(DataWidth))
  val StoreValid = Input(Bool())
  val StoreResp = Input(UInt(2.W))
  val StoreAddr = Input(UInt(AddressWidth.W))
  val StoreVAddr = Input(UInt(AddressWidth.W))
  val StoreData = Input(UInt(DataWidth.W))
  val StoreStrb = Input(UInt((DataWidth / 8).W))
  val AXI = new ysyx_26030103_AXI5IO(AddressWidth) // DCache只使用AR/R通道
  val flush = Input(Bool())
  // DMA 地址级 snoop（仅 DMA_COHERENCE=hardware_snoop 生成）：
  // [dma_snoop_start, dma_snoop_end] 是 DMA 写实际覆盖的字节范围。
  // 每个重叠的物理行都必须失效；alias-safe 几何下扫描全部 set。
  // ready 为低表示还有匹配的在途 refill 或尚未交付的旧响应，DMA 的 B
  // 必须继续等待，不能在失效完成前对软件可见。
  val dma_snoop_valid = Input(Bool())
  val dma_snoop_start = Input(UInt(AddressWidth.W))
  val dma_snoop_end = Input(UInt(AddressWidth.W))
  val dma_snoop_ready = Output(Bool())
  val perf_hit = Output(Bool())
  val perf_miss = Output(Bool())
  val perf_refill_req = Output(Bool())
  val perf_refill_resp = Output(Bool())
  val req_cacheable = Output(Bool())
  val axi_active = Output(Bool())
}
