package ysyx_26030103.cache

import chisel3._
import _root_.ysyx_26030103.infra.ysyx_26030103_AXI5IO

/** 指令缓存的统一对外接口。
  *
  * 所有ICache实现都必须挂在本接口后面，外部（顶层/IFU）只依赖这里的端口
  * 和下面的行为约定，不依赖任何实现内部的流水与时序：
  *   1. 请求/响应均使用valid-ready握手，响应可以晚任意拍返回，但必须保序；
  *   2. 取指访问错误由 resp_fault 与 access_fault/access_fault_resp 报告，
  *      语义与EXU提交点使用的约定保持一致；
  *   3. kill 丢弃在飞取指请求，flush（fence.i）使全部缓存行失效。
  */
class ysyx_26030103_ICacheInterface(val AddressWidth: Int = 32)
    extends Bundle {
  // fetch_addr：物理地址（PIPT 下也是唯一地址）；fetch_vaddr：虚拟地址。
  val fetch_addr = Input(UInt(AddressWidth.W))
  val fetch_vaddr = Input(UInt(AddressWidth.W))
  // VIPT 并行模式：fetch_valid 可以先于翻译完成拉高，用 VA 立即选 index；
  // 等 fetch_paddr_valid 与 fetch_paddr_vaddr 表明 PA 已到达本请求后，
  // 再用 PA 做 tag/PMA/refill。PIPT 实现忽略这两个信号。
  val fetch_paddr_valid = Input(Bool())
  val fetch_paddr_vaddr = Input(UInt(AddressWidth.W))
  val fetch_valid = Input(Bool())
  val fetch_ready = Output(Bool())
  // VIPT：s1 已受理 VA、仍在等待对应 PA。顶层用这对信号驱动取指 MMU，
  // 确保只为已经进入 ICache 的请求发起翻译，避免翻译跑在指令流前面。
  val fetch_xlate_pending = Output(Bool())
  val fetch_xlate_vaddr = Output(UInt(AddressWidth.W))
  val resp_data = Output(UInt(32.W))
  val resp_addr = Output(UInt(AddressWidth.W)) // 响应对应的取指地址(即那条指令的PC)
  val resp_fault = Output(Bool()) // 该响应是否是取指访问错误
  val resp_valid = Output(Bool())
  val resp_ready = Input(Bool())
  val axi = new ysyx_26030103_AXI5IO(AddressWidth)
  val perf_hit = Output(Bool())
  val perf_miss = Output(Bool())
  val perf_refill_req = Output(Bool())
  val perf_refill_resp = Output(Bool())
  val access_fault = Output(Bool())
  val access_fault_resp = Output(UInt(2.W))
  val flush = Input(Bool()) // fence.i: 清cache行并丢弃在飞refill
  val kill = Input(Bool()) // 重定向/异常: 杀掉在飞取指请求,不动cache行
}
