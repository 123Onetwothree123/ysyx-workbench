// Adapted from NutShell's MDU.scala (Mulan PSL v2).
package ysyx_26030103.exu

import chisel3._
import chisel3.util._

class ysyx_26030103_NutShellDivider(val length: Int = 64) extends Module {
  require(length >= 2, "NutShell divider width 非法")
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(Vec(2, UInt(length.W))))
    val sign = Input(Bool())
    val out = Decoupled(UInt((2 * length).W))
    val flush = Input(Bool())
    val IterationCount = Output(UInt(16.W))
    val EarlyOut = Output(Bool())
  })

  private def abs(value: UInt, signed: Bool): (Bool, UInt) = {
    val negative = value(length - 1) && signed
    (negative, Mux(negative, -value, value))
  }

  private val sIdle :: sLog2 :: sShift :: sCompute :: sFinish :: Nil = Enum(5)
  private val state = RegInit(sIdle)
  private val newRequest = (state === sIdle) && io.in.fire
  private val (a, b) = (io.in.bits(0), io.in.bits(1))
  private val divBy0 = b === 0.U(length.W)
  private val shiftReg = Reg(UInt((1 + length * 2).W))
  private val hi = shiftReg(length * 2, length)
  private val lo = shiftReg(length - 1, 0)
  private val (aSign, aVal) = abs(a, io.sign)
  private val (bSign, bVal) = abs(b, io.sign)
  private val aSignReg = RegEnable(aSign, newRequest)
  private val qSignReg = RegEnable((aSign ^ bSign) && !divBy0, newRequest)
  private val bReg = RegEnable(bVal, newRequest)
  private val aValx2Reg = RegEnable(Cat(aVal, 0.U(1.W)), newRequest)
  private val count = Counter(length)
  io.IterationCount := count.value.pad(16)
  io.EarlyOut := state === sFinish && count.value =/= (length - 1).U

  when(io.flush) {
    state := sIdle
  }.otherwise {
    when(newRequest) {
      state := sLog2
    }.elsewhen(state === sLog2) {
      val canSkipShift = (length.U | Log2(bReg)) - Log2(aValx2Reg)
      count.value := Mux(divBy0, 0.U,
        Mux(canSkipShift >= (length - 1).U, (length - 1).U, canSkipShift))
      state := sShift
    }.elsewhen(state === sShift) {
      shiftReg := aValx2Reg << count.value
      state := sCompute
    }.elsewhen(state === sCompute) {
      val enough = hi >= bReg
      shiftReg := Cat(Mux(enough, hi - bReg, hi)(length - 1, 0), lo, enough)
      count.inc()
      when(count.value === (length - 1).U) {
        state := sFinish
      }
    }.elsewhen(state === sFinish) {
      state := sIdle
    }
  }

  private val remainder = hi(length, 1)
  private val quotient = Mux(qSignReg, -lo, lo)
  private val signedRemainder = Mux(aSignReg, -remainder, remainder)
  io.out.bits := Cat(signedRemainder, quotient)
  io.out.valid := state === sFinish
  io.in.ready := state === sIdle
}
