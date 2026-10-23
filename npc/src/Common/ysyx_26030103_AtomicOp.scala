package ysyx_26030103.common

import chisel3._
import chisel3.util._

/** RISC-V A 扩展的原子操作编码。
  *
  * 数值直接取 AMO 指令 funct5（Instruction[31:27]）的无符号值，
  * 译码级无需再做一张额外的映射表；LSU 只响应这里列出的合法编码。
  */
object ysyx_26030103_AtomicOp {
  val Width: Int = 5
  val ADD: UInt = 0x00.U(Width.W) // AMOADD
  val SWAP: UInt = 0x01.U(Width.W) // AMOSWAP
  val LR: UInt = 0x02.U(Width.W) // LR
  val SC: UInt = 0x03.U(Width.W) // SC
  val XOR: UInt = 0x04.U(Width.W) // AMOXOR
  val OR: UInt = 0x08.U(Width.W) // AMOOR
  val AND: UInt = 0x0c.U(Width.W) // AMOAND
  val MIN: UInt = 0x10.U(Width.W) // AMOMIN
  val MAX: UInt = 0x14.U(Width.W) // AMOMAX
  val MINU: UInt = 0x18.U(Width.W) // AMOMINU
  val MAXU: UInt = 0x1c.U(Width.W) // AMOMAXU

  def IsLr(op: UInt): Bool = op === LR
  def IsSc(op: UInt): Bool = op === SC
  def IsAmo(op: UInt): Bool =
    op =/= LR && op =/= SC && op =/= 0.U
  /** 该操作是否会在读之后写回内存（AMO 与成功的 SC）。 */
  def Writes(op: UInt): Bool = op =/= LR

  /** 以 XLEN 位宽计算 AMO 的新值；Word=true 时只使用低 32 位。 */
  def Compute(
      op: UInt,
      oldValue: UInt,
      rhs: UInt,
      word: Boolean
  ): UInt = {
    val a = if (word) oldValue(31, 0) else oldValue
    val b = if (word) rhs(31, 0) else rhs
    val raw = MuxLookup(op, a)(
      Seq(
        ADD -> (a + b),
        SWAP -> b,
        XOR -> (a ^ b),
        OR -> (a | b),
        AND -> (a & b),
        MIN -> Mux(a.asSInt < b.asSInt, a, b),
        MAX -> Mux(a.asSInt > b.asSInt, a, b),
        MINU -> Mux(a < b, a, b),
        MAXU -> Mux(a > b, a, b)
      )
    )
    // RV64 的 .W 原子结果按 bit31 符号扩展到 XLEN；.D/RV32 保持原宽度。
    if (word && oldValue.getWidth > 32) {
      Cat(Fill(oldValue.getWidth - 32, raw(31)), raw)
    } else {
      raw
    }
  }
}
