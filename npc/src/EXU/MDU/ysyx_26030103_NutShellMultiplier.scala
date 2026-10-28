// Adapted from NutShell's MDU.scala (Mulan PSL v2).
package ysyx_26030103.exu

import chisel3._
import chisel3.util._

class ysyx_26030103_NutShellMultiplier(val len: Int = 64) extends Module {
  require(len >= 2, "NutShell multiplier width 非法")
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(Vec(2, UInt(len.W))))
    val out = Decoupled(UInt((2 * len).W))
    val flush = Input(Bool())
  })
  private val valid = RegInit(VecInit(Seq.fill(4)(false.B)))
  private val a0 = Reg(UInt(len.W))
  private val b0 = Reg(UInt(len.W))
  private val a1 = Reg(UInt(len.W))
  private val b1 = Reg(UInt(len.W))
  private val a2 = Reg(UInt(len.W))
  private val b2 = Reg(UInt(len.W))
  private val a3 = Reg(UInt(len.W))
  private val b3 = Reg(UInt(len.W))
  private val busy = RegInit(false.B)

  a0 := io.in.bits(0)
  b0 := io.in.bits(1)
  a1 := a0
  b1 := b0
  a2 := a1
  b2 := b1
  a3 := a2
  b3 := b2
  io.out.bits := (a3.asSInt * b3.asSInt).asUInt
  io.out.valid := valid(3)
  io.in.ready := !busy

  when(io.flush) {
    valid := VecInit(Seq.fill(4)(false.B))
    busy := false.B
  }.otherwise {
    valid(0) := io.in.fire
    for (i <- 1 until 4) {
      valid(i) := valid(i - 1)
    }
    when(io.in.fire) {
      busy := true.B
    }.elsewhen(io.out.valid && io.out.ready) {
      busy := false.B
    }
  }
}
