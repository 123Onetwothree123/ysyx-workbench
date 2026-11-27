package ysyx_26030103
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.exu._

object DebugView {
  // RV64 调试观测的高 32 位视图。低 32 位端口保持历史 ABI 不变，
  // 高字单独输出，C++ 侧按 XLEN 拼成完整 64 位，避免 RV64 地址/PC 被截断。
  def highWord(value: UInt, XLEN: Int): UInt =
    if (XLEN == 64) value(63, 32) else 0.U(32.W)

  def Publish64(lo: UInt, hi: UInt, value: UInt, XLEN: Int): Unit = {
    lo := value(31, 0)
    hi := highWord(value, XLEN)
  }

  def Snapshot(
      src: UInt,
      lo: UInt,
      hi: UInt,
      en: Bool,
      reset: UInt,
      XLEN: Int
  ): Unit = {
    val snap = RegEnable(src, reset, en)
    Publish64(lo, hi, snap, XLEN)
  }

  // CSR 退休快照在首条指令退休前也必须与 CSR 读口一致：RV64 的
  // mstatus.SXL/UXL 固定为 2（bit[35:32]=0xA）。
  def mstatusResetValue(XLEN: Int): UInt =
    if (XLEN == 32) ysyx_26030103_mstatus.resetValue
    else Cat("h0000000a".U(32.W), ysyx_26030103_mstatus.resetValue)
}
