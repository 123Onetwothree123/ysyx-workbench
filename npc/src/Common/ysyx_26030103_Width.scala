package ysyx_26030103.common

import chisel3._
import chisel3.util._

object ysyx_26030103_Width {
  def Sext(value: UInt, from: Int, to: Int): UInt =
    if (from == to) value else Cat(Fill(to - from, value(from - 1)), value)

  def Zext(value: UInt, from: Int, to: Int): UInt =
    if (from == to) value else Cat(0.U((to - from).W), value)

  def Sext32(value: UInt, to: Int): UInt = Sext(value, 32, to)

  def Zext32(value: UInt, to: Int): UInt = Zext(value, 32, to)
}
