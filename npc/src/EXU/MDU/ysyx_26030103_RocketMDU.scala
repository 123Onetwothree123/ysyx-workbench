package ysyx_26030103.exu

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common.ysyx_26030103_MDUOp
import _root_.ysyx_26030103.config._

class ysyx_26030103_RocketMDU(
    override val config: ysyx_26030103_NPCConfig = ysyx_26030103_NPCConfig()
) extends ysyx_26030103_MDU(config) {
  import ysyx_26030103_RocketALU._

  private val core = Module(new ysyx_26030103_RocketMulDiv(
    config.XLEN,
    config.MDUProfile.Rocket
  ))
  private val fn = MuxLookup(io.Request.bits.MDUOp, FN_MUL)(Seq(
    ysyx_26030103_MDUOp.MUL -> FN_MUL,
    ysyx_26030103_MDUOp.MULH -> FN_MULH,
    ysyx_26030103_MDUOp.MULHSU -> FN_MULHSU,
    ysyx_26030103_MDUOp.MULHU -> FN_MULHU,
    ysyx_26030103_MDUOp.DIV -> FN_DIV,
    ysyx_26030103_MDUOp.DIVU -> FN_DIVU,
    ysyx_26030103_MDUOp.REM -> FN_REM,
    ysyx_26030103_MDUOp.REMU -> FN_REMU
  ))
  core.io.request.valid := io.Request.valid && !io.Flush
  core.io.request.bits.fn := fn
  core.io.request.bits.dw := (if (config.XLEN == 64) DW_64 else DW_32)
  core.io.request.bits.in1 := io.Request.bits.LHS
  core.io.request.bits.in2 := io.Request.bits.RHS
  core.io.request.bits.tag := 0.U
  core.io.kill := io.Flush
  io.Request.ready := core.io.request.ready && !io.Flush
  core.io.response.ready := io.Response.ready && !io.Flush
  io.Response.valid := core.io.response.valid && !io.Flush
  io.Response.bits.Result := core.io.response.bits.data
  io.PerfDivIterations := core.io.DivIterations
  io.PerfDivEarlyOut := core.io.DivEarlyOut
}
