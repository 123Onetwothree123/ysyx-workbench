package ysyx_26030103.exu
import chisel3._
import chisel3.util._
class ysyx_26030103_mtvec extends Module {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val rst = Input(Bool())
    val wen = Input(Bool())
    val wdata = Input(UInt(32.W))
    val rdata = Output(UInt(32.W))
  })
  val RegMtvec = withClockAndReset(io.clk, io.rst) { RegInit(0.U(32.W)) }
  when(io.rst) {
    RegMtvec := 0.U(32.W)
  }.elsewhen(io.wen) {
    // WARL: BASE按4字节对齐，MODE仅支持Direct(0)/Vectored(1)。
    // 对于保留编码2/3，选择确定性地收敛到Direct。
    val LegalMode = Mux(io.wdata(1, 0) === 1.U, 1.U(2.W), 0.U(2.W))
    RegMtvec := Cat(io.wdata(31, 2), LegalMode)
  }
  io.rdata := RegMtvec
}
