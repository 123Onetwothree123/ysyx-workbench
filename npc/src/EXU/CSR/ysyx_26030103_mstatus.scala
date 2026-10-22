package ysyx_26030103.exu
import chisel3._
import chisel3.util._

object ysyx_26030103_mstatus {
  // 已实现的 mstatus 字段：
  //   SIE(1) SPIE(5) MIE(3) MPIE(7) SPP(8) MPP(12:11)
  //   MPRV(17) SUM(18) MXR(19) TVM(20) TW(21) TSR(22)
  // 其余字段读回零。MPP 允许 U/S/M；保留编码 2 收敛到最低支持特权级。
  // HAS_U=n 时 U 级不存在：MPP/SPP 的最低值固定为 S，从而 MRET/SRET
  // 无法把特权级切回 U。HAS_S=n/HAS_U=y（M+U）时 MPP 仍可保存 U，
  // 但 SPP 不存在；M-only 时 MPP 固定为 M。
  private val FieldMask = BigInt("007e19aa", 16)
  private val MppMask = BigInt("00001800", 16)
  private val SppMask = BigInt("00000100", 16)
  // 只有 S/U 模式存在的字段：SIE/SPIE/SPP/SUM/TVM/TW/TSR。
  private val SOnlyFields = BigInt("00740122", 16)
  // SUM 只影响 S/U 页表权限；HAS_U=n 时没有 U 级，直接固定为 0。
  private val SumField = BigInt("00040000", 16)
  private val MachineMpp = BigInt("00001800", 16)
  def resetValue: UInt = MachineMpp.U(32.W)
  def legalize(value: UInt, hasU: Boolean = true,
      hasS: Boolean = true): UInt = {
    val extended = value.pad(32)
    val activeMask =
      if (!hasS) FieldMask & ~SOnlyFields
      else if (!hasU) FieldMask & ~SumField
      else FieldMask
    val masked = extended & activeMask.U(32.W)
    val rawMpp = Mux(masked(12, 11) === 2.U, 0.U(2.W), masked(12, 11))
    val mpp =
      if (hasU && hasS) rawMpp
      else if (hasU) Mux(rawMpp === 1.U, 0.U(2.W), rawMpp)
      else if (hasS) Mux(rawMpp === 0.U, 1.U(2.W), rawMpp)
      else 3.U(2.W)
    val spp =
      if (!hasS) 0.U(1.W)
      else if (hasU) masked(8)
      else 1.U(1.W)
    (masked & (~(MppMask | SppMask).U(32.W))) | (mpp << 11) | (spp << 8)
  }
}
class ysyx_26030103_mstatus(
    val hasS: Boolean = true,
    val hasU: Boolean = true
) extends Module {
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
    RegMstatus := legalize(io.wdata, hasU, hasS)
  }
  io.rdata := RegMstatus
}
