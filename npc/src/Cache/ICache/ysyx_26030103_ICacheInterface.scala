package ysyx_26030103.cache
import _root_.ysyx_26030103.common.ysyx_26030103_KconfigDefaults

import chisel3._
import _root_.ysyx_26030103.infra.ysyx_26030103_AXI5IO

/** 指令缓存的统一对外接口。
  *
  * 所有ICache实现都必须挂在本接口后面，外部（顶层/IFU）只依赖这里的端口
  * 和下面的行为约定，不依赖任何实现内部的流水与时序：
  *   1. 请求/响应均使用valid-ready握手，响应可以晚任意拍返回，但必须保序；
  *   2. 取指访问错误由 ResponseFault 与 AccessFaultResponse 报告，
  *      语义与EXU提交点使用的约定保持一致；
  *   3. kill 丢弃在飞取指请求，flush（fence.i）使全部缓存行失效。
  */
class ysyx_26030103_ICacheInterface(
    val AddressWidth: Int = ysyx_26030103_KconfigDefaults.NpcAddressWidth,
    val DataWidth: Int = ysyx_26030103_KconfigDefaults.NpcInternalBusWidth
) extends Bundle {
  require(DataWidth == 32 || DataWidth == 64,
    s"ICacheInterface DataWidth 非法: $DataWidth")
  // FetchAddress：物理地址（PIPT 下也是唯一地址）；FetchVaddr：虚拟地址。
  val FetchAddress = Input(UInt(AddressWidth.W))
  val FetchVaddr = Input(UInt(AddressWidth.W))
  // VIPT 并行模式：FetchValid 可以先于翻译完成拉高，用 VA 立即选 index；
  // 等 FetchPaddrValid 与 FetchPaddrVaddr 表明 PA 已到达本请求后，
  // 再用 PA 做 tag/PMA/refill。PIPT 实现忽略这两个信号。
  val FetchPaddrValid = Input(Bool())
  val FetchPaddrVaddr = Input(UInt(AddressWidth.W))
  val FetchValid = Input(Bool())
  val FetchReady = Output(Bool())
  // VIPT：s1 已受理 VA、仍在等待对应 PA。顶层用这对信号驱动取指 MMU，
  // 确保只为已经进入 ICache 的请求发起翻译，避免翻译跑在指令流前面。
  val FetchXlatePending = Output(Bool())
  val FetchXlateVaddr = Output(UInt(AddressWidth.W))
  val ResponseData = Output(UInt(32.W))
  val ResponseAddress = Output(UInt(AddressWidth.W)) // 响应对应的取指地址(即那条指令的PC)
  val ResponseFault = Output(Bool()) // 该响应是否是取指访问错误
  val ResponseValid = Output(Bool())
  val ResponseReady = Input(Bool())
  val AXI = new ysyx_26030103_AXI5IO(AddressWidth, DataWidth, 4)
  val perf_hit = Output(Bool())
  val perf_miss = Output(Bool())
  val perf_refill_req = Output(Bool())
  val perf_refill_resp = Output(Bool())
  val perf_line_fill = Output(Bool())
  val AccessFaultResponse = Output(UInt(2.W))
  val flush = Input(Bool()) // fence.i: 清cache行并丢弃在飞refill
  val kill = Input(Bool()) // 重定向/异常: 杀掉在飞取指请求,不动cache行
  // 自修改代码硬件 snoop（可选）：CPU store 的 B 响应完成时，按物理地址
  // 失效对应行。[SnoopAddress, SnoopAddressHi] 覆盖可能跨行的一次 store；
  // FENCE_I 模式下有效位恒为 0，不生成额外阵列扫描逻辑。
  val SnoopValid = Input(Bool())
  val SnoopAddress = Input(UInt(AddressWidth.W))
  val SnoopAddressHi = Input(UInt(AddressWidth.W))
  // DMA 地址级 snoop（仅 DMA_COHERENCE=hardware_snoop 生成）：
  // [DMASnoopStart, DMASnoopEnd] 内的所有物理行都必须失效。
  // ready 为低表示匹配的在途 refill 尚未排空/丢弃，DMA 的 B 必须等待。
  val DMASnoopValid = Input(Bool())
  val DMASnoopStart = Input(UInt(AddressWidth.W))
  val DMASnoopEnd = Input(UInt(AddressWidth.W))
  val DMASnoopReady = Output(Bool())
}
