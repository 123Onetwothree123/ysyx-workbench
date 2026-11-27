package ysyx_26030103.exu
import _root_.ysyx_26030103.common.ysyx_26030103_XLENRequire
import _root_.ysyx_26030103.common.ysyx_26030103_KconfigDefaults
import chisel3._
import chisel3.util._
object ysyx_26030103_mepc {
  def Align(value: UInt, useC: Boolean, xlen: Int): UInt =
    if (useC) Cat(value(xlen - 1, 1), 0.U(1.W))
    else Cat(value(xlen - 1, 2), 0.U(2.W))
}
class ysyx_26030103_mepc(
    val XLEN: Int = ysyx_26030103_KconfigDefaults.NpcXlen,
    // IALIGN=16（C 扩展打开）时 mepc/sepc 只清 bit0；否则低两位都为 0。
    val UseC: Boolean = false
) extends Module {
  ysyx_26030103_XLENRequire(XLEN, "mepc")
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())
    val wen = Input(Bool())
    // ecall异常触发时自动写入
    val ExceptionWE = Input(Bool())
    val ExceptionData = Input(UInt(XLEN.W))
    val wdata = Input(UInt(XLEN.W))
    val rdata = Output(UInt(XLEN.W))
  })
  val regMepc = withClockAndReset(io.clock, io.reset) { RegInit(0.U(XLEN.W)) }
  when(io.ExceptionWE) { // 异常优先跑
    regMepc := ysyx_26030103_mepc.Align(io.ExceptionData, UseC, XLEN)
  }.elsewhen(io.wen) {
    regMepc := ysyx_26030103_mepc.Align(io.wdata, UseC, XLEN)
  }
  io.rdata := ysyx_26030103_mepc.Align(regMepc, UseC, XLEN)
}
