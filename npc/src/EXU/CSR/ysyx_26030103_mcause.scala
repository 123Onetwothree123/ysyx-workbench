package ysyx_26030103.exu
import _root_.ysyx_26030103.common.ysyx_26030103_XLENRequire
import chisel3._
class ysyx_26030103_mcause(val XLEN: Int = 32) extends Module {
  ysyx_26030103_XLENRequire(XLEN, "mcause")
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())
    val wen = Input(Bool())
    val wdata = Input(UInt(XLEN.W))
    val rdata = Output(UInt(XLEN.W))
  })
  val regMcause = withClockAndReset(io.clock, io.reset) { RegInit(0.U(XLEN.W)) }
  when(io.wen) {
    regMcause := io.wdata
  }
  io.rdata := regMcause
}
