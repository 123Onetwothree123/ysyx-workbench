package ysyx_26030103.exu
import _root_.ysyx_26030103.common.ysyx_26030103_XLENRequire
import chisel3._
import chisel3.util._
class ysyx_26030103_mtvec(val XLEN: Int = 32) extends Module {
  ysyx_26030103_XLENRequire(XLEN, "mtvec")
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())
    val wen = Input(Bool())
    val wdata = Input(UInt(XLEN.W))
    val rdata = Output(UInt(XLEN.W))
  })
  val regMtvec = withClockAndReset(io.clock, io.reset) { RegInit(0.U(XLEN.W)) }
  when(io.reset) {
    regMtvec := 0.U(XLEN.W)
  }.elsewhen(io.wen) {
    // WARL: BASE按4字节对齐，MODE仅支持Direct(0)/Vectored(1)。
    // 对于保留编码2/3，选择确定性地收敛到Direct。
    val legalMode = Mux(io.wdata(1, 0) === 1.U, 1.U(2.W), 0.U(2.W))
    regMtvec := Cat(io.wdata(XLEN - 1, 2), legalMode)
  }
  io.rdata := regMtvec
}
