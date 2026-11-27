package ysyx_26030103.common

import chisel3._
import chisel3.util._

object ysyx_26030103_WritebackSource {
  val ALU = 0.U(2.W)
  val Load = 1.U(2.W)
  val PC = 2.U(2.W)
  val CSR = 3.U(2.W)

  def Select(
      select: UInt,
      alu: UInt,
      load: UInt,
      pc: UInt,
      csr: UInt,
      default: UInt
  ): UInt =
    Mux(
      select === PC,
      pc,
      Mux(
        select === CSR,
        csr,
        Mux(select === Load, load, Mux(select === ALU, alu, default))
      )
    )
}
