package ysyx_26030103.mmu
import _root_.ysyx_26030103.common.ysyx_26030103_KconfigDefaults
import _root_.ysyx_26030103.common.ysyx_26030103_RingBuffer

import chisel3._
import chisel3.util._

// TLB miss queue（FIFO），只保存等待 PTW 的上下文编号；请求本体、序号和
// 权限信息仍保存在 MMU 上下文表里，队列无需复制完整 MMURequest。深度由
// ITLB/DTLB/UTLB/GLOBAL_MISS_QUEUE_ENTRIES 分别配置。
// flush 同拍清空且优先于本拍 enq/deq，避免冲刷后旧 miss 又被送给 PTW。
class ysyx_26030103_TLBMissQueue(
  entries: Int = ysyx_26030103_KconfigDefaults.MMUItlbMissQueueEntries,
  width:   Int = 4)
    extends Module {
  require(entries >= 1, "TLB miss queue 深度必须 >= 1")
  require(width >= 1, "TLB miss queue 数据宽度必须 >= 1")

  private val countBits = log2Ceil(entries + 1)

  val io = IO(new Bundle {
    val enq       = Flipped(Decoupled(UInt(width.W)))
    val deq       = Decoupled(UInt(width.W))
    val flush     = Input(Bool())
    // 当前占用项数，便于顶层观测和定向测试。
    val occupancy = Output(UInt(countBits.W))
  })

  private val MissQueue = Module(
    new ysyx_26030103_RingBuffer(entries, width, DequeueBypass = false)
  )

  io.enq.ready := MissQueue.io.HasSpace
  io.deq.valid := !MissQueue.io.Empty
  io.deq.bits  := MissQueue.io.HeadData
  io.occupancy := MissQueue.io.Occupancy

  MissQueue.io.Enqueue     := io.enq.fire
  MissQueue.io.EnqueueData := io.enq.bits
  MissQueue.io.Dequeue     := io.deq.fire
  MissQueue.io.Flush       := io.flush
}
