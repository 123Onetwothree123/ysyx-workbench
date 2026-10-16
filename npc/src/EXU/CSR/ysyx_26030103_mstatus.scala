package ysyx_26030103.exu
import chisel3._
import chisel3.util._
object ysyx_26030103_mstatus {
  // 本核明确实现为 M-only。MIE/MPIE 可写，MPP 是 WARL 且只能表示 M；
  // 其余字段均为 WPRI，必须读回零。这样既不会伪装成已经实现 U-mode，
  // MRET 后也仍停留在唯一支持的机器特权级。
  private val InterruptMask = BigInt("00000088", 16)
  private val MachineMPP = BigInt("00001800", 16)
  def resetValue: UInt = MachineMPP.U(32.W)
  def legalize(value: UInt): UInt = {
    val extended = value.pad(32)
    (extended & InterruptMask.U(32.W)) | resetValue
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
