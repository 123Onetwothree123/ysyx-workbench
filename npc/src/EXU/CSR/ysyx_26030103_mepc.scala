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
  val UseC: Boolean = false)
    extends Module {
  ysyx_26030103_XLENRequire(XLEN, "mepc")
  val io               = IO(new Bundle {
    val clock         = Input(Clock())
    val reset         = Input(Bool())
    val wen           = Input(Bool())
    // ecall异常触发时自动写入
    val ExceptionWE   = Input(Bool())
    val ExceptionData = Input(UInt(XLEN.W))
    val wdata         = Input(UInt(XLEN.W))
    val rdata         = Output(UInt(XLEN.W))
    // 时钟沿之后的值。CSR 的 StateMepc 直接使用这里已 Align 的结果，
    // 不再在 CSR 里重复调用 Align。
    val rdataNext     = Output(UInt(XLEN.W))
  })
  val regMepc          = withClockAndReset(io.clock, io.reset) { RegInit(0.U(XLEN.W)) }
  // IALIGN 对齐只在这里做一次；异常入口优先于软件 CSR 写。
  val exceptionAligned =
    ysyx_26030103_mepc.Align(io.ExceptionData, UseC, XLEN)
  val writeAligned     = ysyx_26030103_mepc.Align(io.wdata, UseC, XLEN)
  when(io.ExceptionWE) { // 异常优先跑
    regMepc := exceptionAligned
  }.elsewhen(io.wen) {
    regMepc := writeAligned
  }
  io.rdata := ysyx_26030103_mepc.Align(regMepc, UseC, XLEN)
  io.rdataNext :=
    Mux(io.ExceptionWE, exceptionAligned, Mux(io.wen, writeAligned, regMepc))
}
