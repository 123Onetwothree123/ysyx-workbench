package ysyx_26030103.common

import chisel3._
import chisel3.util._

object ysyx_26030103_RISCVFields {
  def SatpMode(satp: UInt, xlen: Int): UInt =
    if (xlen == 32) satp(31) else satp(63, 60)
  def SatpASID(satp: UInt, xlen: Int): UInt =
    if (xlen == 32) satp(30, 22) else satp(59, 44)
  def SatpPPN(satp: UInt, xlen: Int): UInt =
    if (xlen == 32) satp(21, 0) else satp(43, 0)
  def CanonicalOk(address: UInt, vaBits: Int, xlen: Int): Bool =
    if (vaBits == xlen) true.B
    else {
      val upper = address(xlen - 1, vaBits)
      val sign = Fill(xlen - vaBits, address(vaBits - 1))
      upper === sign
    }
  def PageFaultCause(isFetch: Bool, isStore: Bool): UInt =
    Mux(isFetch, 12.U, Mux(isStore, 15.U, 13.U))
  def AccessFaultCause(isWrite: Bool): UInt =
    Mux(isWrite, 7.U, 5.U)
  def MisalignedCause(isWrite: Bool): UInt =
    Mux(isWrite, 6.U, 4.U)
}
