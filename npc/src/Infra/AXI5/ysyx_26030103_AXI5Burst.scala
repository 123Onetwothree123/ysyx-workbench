package ysyx_26030103.infra

import chisel3._

object ysyx_26030103_AXI5Burst {

  def beatBytes(size: UInt, width: Int): UInt =
    (1.U << size).pad(width)

  def lenPlusOne(len: UInt): UInt =
    len.pad(len.getWidth + 1) + 1.U

  def totalBytes(len: UInt, beatBytes: UInt): UInt =
    lenPlusOne(len) * beatBytes

  /** 末字节地址。返回值不截断，宽度 = address 宽度 + len 宽度 + 2。 */
  def lastByte(address: UInt, len: UInt, size: UInt, burst: UInt): UInt = {
    val width = address.getWidth + 1
    val addr = address.pad(width)
    val beat = beatBytes(size, width)
    val alignedStart = addr & ~(beat - 1.U)
    val total = totalBytes(len, beat)
    val wrapMask = total - 1.U
    val wide = total.getWidth
    val addrWide = addr.pad(wide)
    val alignedWide = alignedStart.pad(wide)
    val beatWide = beat.pad(wide)
    Mux(
      burst === 2.U,
      (addrWide & ~wrapMask) + total - 1.U,
      Mux(
        burst === 1.U,
        alignedWide + total - 1.U,
        alignedWide + beatWide - 1.U
      )
    )
  }
}
