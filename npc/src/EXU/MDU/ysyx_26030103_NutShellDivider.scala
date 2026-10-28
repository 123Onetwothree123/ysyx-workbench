// Adapted from NutShell's MDU.scala (Mulan PSL v2).
package ysyx_26030103.exu

import chisel3._
import chisel3.util._

class ysyx_26030103_NutShellDivider(val len: Int = 64) extends Module {
  require(len >= 2, "NutShell divider width 非法")
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(Vec(2, UInt(len.W))))
    val sign = Input(Bool())
    val out = Decoupled(UInt((2 * len).W))
    val flush = Input(Bool())
  })

  private def abs(value: UInt, signed: Bool): (Bool, UInt) = {
    val negative = value(len - 1) && signed
    (negative, Mux(negative, -value, value))
  }

  private val s_idle :: s_log2 :: s_shift :: s_compute :: s_finish :: Nil = Enum(5)
  private val state = RegInit(s_idle)
  private val newReq = (state === s_idle) && io.in.fire
  private val (a, b) = (io.in.bits(0), io.in.bits(1))
  private val divBy0 = b === 0.U(len.W)
  private val shiftReg = Reg(UInt((1 + len * 2).W))
  private val hi = shiftReg(len * 2, len)
  private val lo = shiftReg(len - 1, 0)
  private val (aSign, aVal) = abs(a, io.sign)
  private val (bSign, bVal) = abs(b, io.sign)
  private val aSignReg = RegEnable(aSign, newReq)
  private val qSignReg = RegEnable((aSign ^ bSign) && !divBy0, newReq)
  private val bReg = RegEnable(bVal, newReq)
  private val aValx2Reg = RegEnable(Cat(aVal, 0.U(1.W)), newReq)
  private val cnt = Counter(len)

  when(io.flush) {
    state := s_idle
  }.otherwise {
    when(newReq) {
      state := s_log2
    }.elsewhen(state === s_log2) {
      val canSkipShift = (len.U | Log2(bReg)) - Log2(aValx2Reg)
      cnt.value := Mux(divBy0, 0.U,
        Mux(canSkipShift >= (len - 1).U, (len - 1).U, canSkipShift))
      state := s_shift
    }.elsewhen(state === s_shift) {
      shiftReg := aValx2Reg << cnt.value
      state := s_compute
    }.elsewhen(state === s_compute) {
      val enough = hi >= bReg
      shiftReg := Cat(Mux(enough, hi - bReg, hi)(len - 1, 0), lo, enough)
      cnt.inc()
      when(cnt.value === (len - 1).U) {
        state := s_finish
      }
    }.elsewhen(state === s_finish) {
      state := s_idle
    }
  }

  private val remainder = hi(len, 1)
  private val quotient = Mux(qSignReg, -lo, lo)
  private val signedRemainder = Mux(aSignReg, -remainder, remainder)
  io.out.bits := Cat(signedRemainder, quotient)
  io.out.valid := state === s_finish
  io.in.ready := state === s_idle
}
