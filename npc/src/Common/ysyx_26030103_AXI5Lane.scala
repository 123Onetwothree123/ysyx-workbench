package ysyx_26030103.common

import chisel3._
import chisel3.util._

/** 32 位数据在 32/64 位 AXI 数据通道上的 lane 放置与选择。 */
object ysyx_26030103_AXI5Lane {
  def LaneWord(data: UInt, address: UInt, wide: Boolean): UInt =
    if (wide)
      Mux(
        address(2),
        Cat(data(31, 0), 0.U(32.W)),
        Cat(0.U(32.W), data(31, 0))
      )
    else data(31, 0)

  def LaneStrobe(strobe: UInt, address: UInt, wide: Boolean): UInt =
    if (wide)
      Mux(
        address(2),
        Cat(strobe(3, 0), 0.U(4.W)),
        Cat(0.U(4.W), strobe(3, 0))
      )
    else strobe(3, 0)

  def SelectWord(data: UInt, address: UInt, wide: Boolean): UInt =
    if (wide) Mux(address(2), data(63, 32), data(31, 0))
    else data(31, 0)

  def SelectStrobe(strobe: UInt, address: UInt, wide: Boolean): UInt =
    if (wide) Mux(address(2), strobe(7, 4), strobe(3, 0))
    else strobe(3, 0)
}
