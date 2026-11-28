package ysyx_26030103.infra

import _root_.ysyx_26030103.common.ysyx_26030103_KconfigDefaults

// CPU 侧读通道 AXI ID 分配（4 位，与 AXI5Xbar 的 16 个读槽对应）：
//   0 ICache 取指（复用 LSU 路径时 DCache 的局部 ID 也先占 0）
//   1 LSU 数据读、2 DMA 读、3..15 PTW 页表读（13 笔可同时在途）
// PTW 内部页表总线最多 16 笔，但下游 ID 还要留给 ICache/LSU/DMA，其余
// 请求留在请求队列等 ID 槽位；上限由 AXI ID 位宽决定，不是 PTW 的限制。
object ysyx_26030103_AXIReadID {
  val IDWidth = ysyx_26030103_KconfigDefaults.AXIIDWidth
  require(IDWidth == 4,
    s"当前 AXI ID 固定为 4 位，收到 IDWidth=$IDWidth")
  val SlotCount = 1 << IDWidth
  val IFU = 0
  val LSU = 1
  val DMA = 2
  val PTWBase = 3
  val PTWSlots = (1 << IDWidth) - PTWBase
  val PTWLast = PTWBase + PTWSlots - 1
}
