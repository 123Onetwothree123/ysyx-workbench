package ysyx_26030103.exu
import _root_.ysyx_26030103.common.ysyx_26030103_KconfigDefaults
import chisel3._
import chisel3.util._
class ysyx_26030103_mepc(
    val XLEN: Int = ysyx_26030103_KconfigDefaults.NpcXlen,
    // IALIGN=16（C 扩展打开）时 mepc/sepc 只清 bit0；否则低两位都为 0。
    val UseC: Boolean = false
) extends Module {
  require(XLEN == 32 || XLEN == 64, s"mepc XLEN 非法: $XLEN")
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
  // IALIGN=16 时只固定 bit0；IALIGN=32 时 mepc[1:0]都为 0。
  private def align(value: UInt): UInt =
    if (UseC) Cat(value(XLEN - 1, 1), 0.U(1.W))
    else Cat(value(XLEN - 1, 2), 0.U(2.W))
  when(io.ExceptionWE) { // 异常优先跑
    regMepc := align(io.ExceptionData)
  }.elsewhen(io.wen) {
    regMepc := align(io.wdata)
  }
  io.rdata := align(regMepc)
}
