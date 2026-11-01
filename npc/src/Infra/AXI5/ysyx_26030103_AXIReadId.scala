package ysyx_26030103.infra

/** CPU 侧读通道 AXI ID 分配。
  *
  * 4 位 ID 在 IFU/LSU/DMA 之间共享，并与 AXI5Xbar 的 16 个读槽一一对应：
  *   0      ICache 取指（复用 LSU 路径时，DCache 的局部 ID 也先占 0）
  *   1      LSU 数据读（由仲裁器把局部 ID 0 映射到这里）
  *   2      DMA 读
  *   3..15  PTW 页表读，共 13 个可同时在途的 AXI 读事务
  *
  * PTW 内部页表总线最多 16 笔在途，但下游 4 位 ID 还要留给 ICache、
  * LSU 和 DMA；因此适配器最多同时发出 13 笔 AXI 读，其余请求留在
  * 请求队列中等待 ID 槽位。这个上限由 AXI ID 位宽决定，不是 PTW 本身
  * 的限制。
  */
object ysyx_26030103_AXIReadId {
  val Ifu = 0
  val Lsu = 1
  val Dma = 2
  val PtwBase = 3
  val PtwSlots = 13
  val PtwLast = PtwBase + PtwSlots - 1
}
