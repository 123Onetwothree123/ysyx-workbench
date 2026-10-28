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
  private val fn = MuxLookup(io.Req.bits.MDUOp, FN_MUL)(Seq(
    ysyx_26030103_MDUOp.MUL -> FN_MUL,
    ysyx_26030103_MDUOp.MULH -> FN_MULH,
    ysyx_26030103_MDUOp.MULHSU -> FN_MULHSU,
    ysyx_26030103_MDUOp.MULHU -> FN_MULHU,
    ysyx_26030103_MDUOp.DIV -> FN_DIV,
    ysyx_26030103_MDUOp.DIVU -> FN_DIVU,
    ysyx_26030103_MDUOp.REM -> FN_REM,
    ysyx_26030103_MDUOp.REMU -> FN_REMU
  ))
  core.io.req.valid := io.Req.valid && !io.Flush
  core.io.req.bits.fn := fn
  core.io.req.bits.dw := (if (config.XLEN == 64) DW_64 else DW_32)
  core.io.req.bits.in1 := io.Req.bits.LHS
  core.io.req.bits.in2 := io.Req.bits.RHS
  core.io.req.bits.tag := 0.U
  core.io.kill := io.Flush
  io.Req.ready := core.io.req.ready && !io.Flush
  core.io.resp.ready := io.Resp.ready && !io.Flush
  io.Resp.valid := core.io.resp.valid && !io.Flush
  io.Resp.bits.Result := core.io.resp.bits.data
}
