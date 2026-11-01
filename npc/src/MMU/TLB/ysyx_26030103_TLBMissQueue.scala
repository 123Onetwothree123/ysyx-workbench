package ysyx_26030103.mmu

import chisel3._
import chisel3.util._

/** TLB miss queue（FIFO）。
  *
  * 只保存等待 PTW 的上下文编号；请求本体、序号和权限信息仍保存在 MMU 的上下文表里，因此队列本身不需要复制完整的 MMURequest。深度由 ITLB/DTLB/UTLB/GLOBAL_MISS_QUEUE_ENTRIES
  * 分别配置。
  *
  * 队列支持与 flush 同拍的清空：flush 优先于本拍的 enq/deq，避免流水线 冲刷后旧 miss 又被重新送给 PTW。
  */
class ysyx_26030103_TLBMissQueue(
  entries: Int = 4,
  width:   Int = 4)
    extends Module {
  require(entries >= 1, "TLB miss queue 深度必须 >= 1")
  require(width >= 1, "TLB miss queue 数据宽度必须 >= 1")

  private val ptrBits   = log2Ceil(entries.max(2))
  private val countBits = log2Ceil(entries + 1)
  // entries=1 时 Vec 动态索引位宽必须为 0，避免无效的 1 位索引告警。
  private def ptrIndex(ptr: UInt): UInt =
    if (entries == 1) 0.U(0.W) else ptr

  val io = IO(new Bundle {
    val enq       = Flipped(Decoupled(UInt(width.W)))
    val deq       = Decoupled(UInt(width.W))
    val flush     = Input(Bool())
    // 当前占用项数，便于顶层观测和定向测试。
    val occupancy = Output(UInt(countBits.W))
  })

  val mem           = RegInit(VecInit(Seq.fill(entries)(0.U(width.W))))
  val head          = RegInit(0.U(ptrBits.W))
  val tail          = RegInit(0.U(ptrBits.W))
  val count         = RegInit(0.U(countBits.W))
  private val full  = count === entries.U
  private val empty = count === 0.U

  io.enq.ready := !full
  io.deq.valid := !empty
  io.deq.bits  := mem(ptrIndex(head))
  io.occupancy := count

  // enq 与 deq 同拍时 tail/head 各自前进，count 保持不变。由于 deq.bits
  // 组合读取的是旧 head，而写口只更新 tail，同拍读写同一物理槽位也不会
  // 把新数据错误地送给本拍出队者。
  when(io.flush) {
    head  := 0.U
    tail  := 0.U
    count := 0.U
  }.elsewhen(io.enq.fire && io.deq.fire) {
    mem(ptrIndex(tail)) := io.enq.bits
    head                := Mux(head === (entries - 1).U, 0.U, head + 1.U)
    tail                := Mux(tail === (entries - 1).U, 0.U, tail + 1.U)
  }.elsewhen(io.enq.fire) {
    mem(ptrIndex(tail)) := io.enq.bits
    tail                := Mux(tail === (entries - 1).U, 0.U, tail + 1.U)
    count               := count + 1.U
  }.elsewhen(io.deq.fire) {
    head  := Mux(head === (entries - 1).U, 0.U, head + 1.U)
    count := count - 1.U
  }
}
