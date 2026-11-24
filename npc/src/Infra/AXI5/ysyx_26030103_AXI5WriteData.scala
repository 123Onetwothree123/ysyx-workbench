package ysyx_26030103.infra

import chisel3._
import chisel3.util._

/** AXI 字节写：只覆盖 WSTRB 选中的字节，其余字节保留寄存器原值。 */
private[infra] object ysyx_26030103_AXI5WriteData {

  def merge32(old: UInt, data: UInt, strobe: UInt): UInt = {
    val OldWord = old.pad(32)
    val DataWord = data.pad(32)
    val StrobeBits = strobe.pad(4)
    Cat(
      Mux(StrobeBits(3), DataWord(31, 24), OldWord(31, 24)),
      Mux(StrobeBits(2), DataWord(23, 16), OldWord(23, 16)),
      Mux(StrobeBits(1), DataWord(15, 8), OldWord(15, 8)),
      Mux(StrobeBits(0), DataWord(7, 0), OldWord(7, 0))
    )
  }
}
