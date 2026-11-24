package ysyx_26030103.common

import chisel3._
import chisel3.util._

/** 把最多两个 32 位字格式化为架构 load 结果。
  *
  * width=0/1/2 分别是 LB/LH/LW，按 ResultWidth 做符号/零扩展；
  * width=3 是 8 字节访问，直接拼接高/低 word。ResultWidth=32 且
  * WideLoad=false 时不生成 width=3 分支，与只支持 32 位访问的
  * 数据通路保持一致。
  */
object ysyx_26030103_LoadFormat {
  def FormatLoad(
      wordLow: UInt,
      wordHigh: UInt,
      address: UInt,
      width: UInt,
      signed: Bool,
      ResultWidth: Int,
      WideLoad: Boolean
  ): UInt = {
    require(ResultWidth == 32 || ResultWidth == 64,
      s"load 结果宽度只允许 32 或 64，收到 $ResultWidth")
    val word = wordLow(31, 0)
    val rawWide = Cat(wordHigh(31, 0), word)
    val byteData = MuxLookup(address(1, 0), word(7, 0))(
      Seq(1.U -> word(15, 8), 2.U -> word(23, 16), 3.U -> word(31, 24))
    )
    val halfData = Mux(address(1), word(31, 16), word(15, 0))
    def extend(value: UInt, bits: Int): UInt =
      if (ResultWidth == bits) {
        value
      } else {
        Mux(signed, Cat(Fill(ResultWidth - bits, value(bits - 1)), value),
          Cat(0.U((ResultWidth - bits).W), value))
      }
    val byteResult = extend(byteData, 8)
    val halfResult = extend(halfData, 16)
    val wordResult = extend(word, 32)
    if (WideLoad) {
      MuxLookup(width, wordResult)(
        Seq(0.U -> byteResult, 1.U -> halfResult, 2.U -> wordResult,
          3.U -> rawWide)
      )
    } else {
      MuxLookup(width, wordResult)(
        Seq(0.U -> byteResult, 1.U -> halfResult, 2.U -> wordResult)
      )
    }
  }
}
