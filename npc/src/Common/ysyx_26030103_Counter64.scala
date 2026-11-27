package ysyx_26030103.common

import chisel3._
import chisel3.util._

object ysyx_26030103_Counter64 {
  def ReadWord(Value: UInt, WordWidth: Int): UInt =
    if (WordWidth == 64) Value else Value(31, 0)

  def ReadHalf(Value: UInt, SelectHigh: Bool): UInt =
    Mux(SelectHigh, Value(63, 32), Value(31, 0))

  def WriteHalf(
      OldValue: UInt,
      WriteWord: UInt,
      SelectHigh: Bool
  ): UInt =
    Mux(
      SelectHigh,
      Cat(WriteWord(31, 0), OldValue(31, 0)),
      Cat(OldValue(63, 32), WriteWord(31, 0))
    )

  def UpdateValue(
      OldValue: UInt,
      WriteWord: UInt,
      WordWidth: Int,
      SelectHigh: Bool,
      LowWrite: Bool,
      HighWrite: Bool,
      Increment: Bool
  ): UInt = {
    val WrittenValue =
      if (WordWidth == 64) WriteWord
      else WriteHalf(OldValue, WriteWord, SelectHigh)
    Mux(
      LowWrite || HighWrite,
      WrittenValue,
      Mux(Increment, OldValue + 1.U, OldValue)
    )
  }
}
