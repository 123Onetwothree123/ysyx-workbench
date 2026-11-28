package ysyx_26030103.exu
import _root_.ysyx_26030103.common.ysyx_26030103_XLENRequire
import chisel3._
import chisel3.util._
object ysyx_26030103_mtvec {
  def Legalize(value: UInt, xlen: Int): UInt =
    Cat(value(xlen - 1, 2), Mux(value(1, 0) === 1.U, 1.U(2.W), 0.U(2.W)))
}
class ysyx_26030103_mtvec(val XLEN: Int = 32) extends Module {
  ysyx_26030103_XLENRequire(XLEN, "mtvec")
  val io             = IO(new Bundle {
    val clock     = Input(Clock())
    val reset     = Input(Bool())
    val wen       = Input(Bool())
    val wdata     = Input(UInt(XLEN.W))
    val rdata     = Output(UInt(XLEN.W))
    // 时钟沿之后的值。CSR 的 StateMtvec 直接使用这里已 Legalize 的
    // 结果，不再重复调用 Legalize。
    val rdataNext = Output(UInt(XLEN.W))
  })
  val regMtvec       = withClockAndReset(io.clock, io.reset) { RegInit(0.U(XLEN.W)) }
  // WARL: BASE按4字节对齐，MODE仅支持Direct(0)/Vectored(1)。
  // 对于保留编码2/3，选择确定性地收敛到Direct。合法化只在这里做一次。
  val legalizedWdata = ysyx_26030103_mtvec.Legalize(io.wdata, XLEN)
  when(io.reset) {
    regMtvec := 0.U(XLEN.W)
  }.elsewhen(io.wen) {
    regMtvec := legalizedWdata
  }
  io.rdata := regMtvec
  io.rdataNext := Mux(io.reset, 0.U(XLEN.W), Mux(io.wen, legalizedWdata, regMtvec))
}
