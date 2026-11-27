package ysyx_26030103.exu
import _root_.ysyx_26030103.common.ysyx_26030103_XLENRequire
import _root_.ysyx_26030103.common.ysyx_26030103_Counter64
import chisel3._
import chisel3.util._
// 64 位周期计数器。RV32 通过 mcycle/mcycleh 访问高低半部；
// RV64 的 mcycle 直接返回完整 64 位值。
class ysyx_26030103_mcycle(val XLEN: Int = 32) extends Module {
  ysyx_26030103_XLENRequire(XLEN, "mcycle")
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())
    val Enable = Input(Bool())
    val wen = Input(Bool())
    val SelectHigh = Input(Bool())
    val wdata = Input(UInt(XLEN.W))
    val rdata = Output(UInt(32.W))
    val rdataFull = Output(UInt(64.W))
  })
  val counter = withClockAndReset(io.clock, io.reset) { RegInit(0.U(64.W)) }
  counter := ysyx_26030103_Counter64.UpdateValue(
    counter,
    io.wdata,
    XLEN,
    io.SelectHigh,
    io.wen && !io.SelectHigh,
    io.wen && io.SelectHigh,
    io.Enable
  )
  io.rdata := ysyx_26030103_Counter64.ReadHalf(counter, io.SelectHigh)
  io.rdataFull := counter
}
