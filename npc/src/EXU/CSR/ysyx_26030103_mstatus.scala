package ysyx_26030103.exu
import chisel3._
import chisel3.util._

object ysyx_26030103_mstatus {
  // 已实现的 mstatus 字段：
  //   SIE(1) SPIE(5) MIE(3) MPIE(7) SPP(8) MPP(12:11)
  //   MPRV(17) SUM(18) MXR(19) TVM(20) TW(21) TSR(22)
  // 其余字段读回零。MPP 允许 U/S/M；保留编码 2 收敛到 U。
  private val FieldMask = BigInt("008a19aa", 16)
  private val MppMask = BigInt("00001800", 16)
  private val MachineMpp = BigInt("00001800", 16)
  def resetValue: UInt = MachineMpp.U(32.W)
  def legalize(value: UInt): UInt = {
    val extended = value.pad(32)
    val masked = extended & FieldMask.U(32.W)
    val mpp = Mux(masked(12, 11) === 2.U, 0.U(2.W), masked(12, 11))
    (masked & (~MppMask.U(32.W))) | (mpp << 11)
  }
}
class ysyx_26030103_mstatus extends Module {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val rst = Input(Bool())
    val wen = Input(Bool())
    val wdata = Input(UInt(32.W))
    val rdata = Output(UInt(32.W))
  })
  import ysyx_26030103_mstatus._
  val RegMstatus = withClockAndReset(io.clk, io.rst) { RegInit(resetValue) }
  when(io.wen) {
    RegMstatus := legalize(io.wdata)
  }
  io.rdata := RegMstatus
}
