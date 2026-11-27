package ysyx_26030103.common

import chisel3._
import chisel3.util._

class ysyx_26030103_RingBuffer(
    val Depth: Int,
    val DataWidth: Int,
    val DequeueBypass: Boolean = true
) extends Module {
  require(Depth >= 1, "环形队列深度非法")
  require(DataWidth >= 1, "环形队列数据宽度非法")

  val PointerWidth = log2Ceil(Depth.max(2))
  val CountWidth = log2Ceil(Depth + 1)

  private def NextPointer(Pointer: UInt): UInt =
    if (Depth == 1) 0.U(PointerWidth.W)
    else Mux(Pointer === (Depth - 1).U, 0.U, Pointer + 1.U)

  private def SlotIndex(Pointer: UInt): UInt =
    if (Depth == 1) 0.U(0.W) else Pointer

  val io = IO(new Bundle {
    val Enqueue = Input(Bool())
    val EnqueueData = Input(UInt(DataWidth.W))
    val Dequeue = Input(Bool())
    val Flush = Input(Bool())
    val Occupancy = Output(UInt(CountWidth.W))
    val HeadPointer = Output(UInt(PointerWidth.W))
    val HeadData = Output(UInt(DataWidth.W))
    val SlotData = Output(Vec(Depth, UInt(DataWidth.W)))
    val Empty = Output(Bool())
    val Full = Output(Bool())
    val HasSpace = Output(Bool())
  })

  private val Storage = RegInit(VecInit(Seq.fill(Depth)(0.U(DataWidth.W))))
  val Head = RegInit(0.U(PointerWidth.W))
  val Tail = RegInit(0.U(PointerWidth.W))
  val Count = RegInit(0.U(CountWidth.W))

  io.Empty := Count === 0.U
  io.Full := Count === Depth.U
  io.HasSpace := !io.Full || (if (DequeueBypass) io.Dequeue else false.B)
  io.Occupancy := Count
  io.HeadPointer := Head
  io.HeadData := Storage(SlotIndex(Head))
  io.SlotData := Storage

  assert(Count <= Depth.U)

  val EnqueueFire = io.Enqueue && !io.Flush
  val DequeueFire = io.Dequeue && !io.Flush
  when(io.Flush) {
    Head := 0.U
    Tail := 0.U
    Count := 0.U
  }.elsewhen(EnqueueFire || DequeueFire) {
    when(EnqueueFire) {
      Storage(SlotIndex(Tail)) := io.EnqueueData
      Tail := NextPointer(Tail)
    }
    when(DequeueFire) {
      Head := NextPointer(Head)
    }
    when(EnqueueFire && !DequeueFire) {
      Count := Count + 1.U
    }.elsewhen(!EnqueueFire && DequeueFire) {
      Count := Count - 1.U
    }
  }
}
