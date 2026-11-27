package ysyx_26030103.infra

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._

class ysyx_26030103_AXI5BurstShape(AddressWidth: Int, IDWidth: Int) extends Bundle {
  val ID = UInt(IDWidth.W)
  val address = UInt(AddressWidth.W)
  val length = UInt(8.W)
  val size = UInt(3.W)
  val burst = UInt(2.W)
  val prot = UInt(3.W)
  val cache = UInt(4.W)
  val qos = UInt(4.W)
}

object ysyx_26030103_AXI5BurstShape {
  def Default(AddressWidth: Int, IDWidth: Int): ysyx_26030103_AXI5BurstShape =
    (new ysyx_26030103_AXI5BurstShape(AddressWidth, IDWidth)).Lit(
      _.ID -> 0.U(IDWidth.W),
      _.address -> 0.U(AddressWidth.W),
      _.length -> 0.U(8.W),
      _.size -> 2.U(3.W),
      _.burst -> 0.U(2.W),
      _.prot -> 0.U(3.W),
      _.cache -> 0.U(4.W),
      _.qos -> 0.U(4.W)
    )
}

object ysyx_26030103_AXI5Burst {

  def beatBytes(size: UInt, width: Int): UInt =
    (1.U << size).pad(width)

  def lenPlusOne(length: UInt): UInt =
    length.pad(length.getWidth + 1) + 1.U

  def totalBytes(length: UInt, bytesPerBeat: UInt): UInt =
    lenPlusOne(length) * bytesPerBeat

  // 末字节地址。返回值不截断，宽度 = address 宽度 + length 宽度 + 2。
  def lastByte(address: UInt, length: UInt, size: UInt, burst: UInt): UInt = {
    val width = address.getWidth + 1
    val extendedAddress = address.pad(width)
    val beat = beatBytes(size, width)
    val alignedStart = extendedAddress & ~(beat - 1.U)
    val total = totalBytes(length, beat)
    val wrapMask = total - 1.U
    val wide = total.getWidth
    val addressWide = extendedAddress.pad(wide)
    val alignedWide = alignedStart.pad(wide)
    val beatWide = beat.pad(wide)
    Mux(
      burst === 2.U,
      (addressWide & ~wrapMask) + total - 1.U,
      Mux(
        burst === 1.U,
        alignedWide + total - 1.U,
        alignedWide + beatWide - 1.U
      )
    )
  }

  def WithinWindow(
      address: UInt,
      length: UInt,
      size: UInt,
      burst: UInt,
      base: BigInt,
      endExclusive: BigInt
  ): Bool = {
    val width = address.getWidth + 1
    val extendedAddress = address.pad(width)
    val lastAddress = lastByte(address, length, size, burst)
    extendedAddress >= base.U(width.W) &&
    lastAddress < endExclusive.U(width.W)
  }

  def HighBitsSet(
      address: UInt,
      length: UInt,
      size: UInt,
      burst: UInt,
      keepWidth: Int
  ): Bool = {
    val extendedAddress = Cat(0.U(1.W), address)
    val lastAddress = lastByte(address, length, size, burst)
    extendedAddress(address.getWidth, keepWidth).orR ||
    lastAddress(address.getWidth, keepWidth).orR
  }
}
