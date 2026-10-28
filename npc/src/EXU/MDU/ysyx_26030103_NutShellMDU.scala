// Adapted from NutShell's MDU.scala (Mulan PSL v2).
package ysyx_26030103.exu

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common.ysyx_26030103_MDUOp
import _root_.ysyx_26030103.config._

class ysyx_26030103_NutShellMDU(
    override val config: ysyx_26030103_NPCConfig = ysyx_26030103_NPCConfig()
) extends ysyx_26030103_MDU(config) {
  private val xlen = config.XLEN
  private val mul = Module(new ysyx_26030103_NutShellMultiplier(xlen + 1))
  private val div = Module(new ysyx_26030103_NutShellDivider(xlen))
  private val op = io.Req.bits.MDUOp
  private val isDiv = op === ysyx_26030103_MDUOp.DIV ||
    op === ysyx_26030103_MDUOp.DIVU ||
    op === ysyx_26030103_MDUOp.REM ||
    op === ysyx_26030103_MDUOp.REMU
  private val isDivSign = isDiv && !op(0)
  private val isRem = op === ysyx_26030103_MDUOp.REM ||
    op === ysyx_26030103_MDUOp.REMU

  private def signExtend(value: UInt): UInt = Cat(value(xlen - 1), value)
  private def zeroExtend(value: UInt): UInt = Cat(0.U(1.W), value)
  private val lhs = io.Req.bits.LHS
  private val rhs = io.Req.bits.RHS
  private val mulLhs = Mux(op === ysyx_26030103_MDUOp.MULH ||
    op === ysyx_26030103_MDUOp.MULHSU, signExtend(lhs), zeroExtend(lhs))
  private val mulRhs = Mux(op === ysyx_26030103_MDUOp.MULH,
    signExtend(rhs), zeroExtend(rhs))

  mul.io.in.valid := io.Req.valid && !isDiv && !io.Flush
  mul.io.in.bits(0) := mulLhs
  mul.io.in.bits(1) := mulRhs
  mul.io.flush := io.Flush
  mul.io.out.ready := io.Resp.ready && !io.Flush

  div.io.in.valid := io.Req.valid && isDiv && !io.Flush
  div.io.in.bits(0) := lhs
  div.io.in.bits(1) := rhs
  div.io.sign := isDivSign
  div.io.flush := io.Flush
  div.io.out.ready := io.Resp.ready && !io.Flush

  io.Req.ready := Mux(isDiv, div.io.in.ready, mul.io.in.ready) && !io.Flush
  private val divSelected = RegInit(false.B)
  private val opReg = RegInit(0.U(ysyx_26030103_MDUOp.Width.W))
  when(io.Req.fire) {
    divSelected := isDiv
    opReg := op
  }
  private val mulResult = Mux(opReg === ysyx_26030103_MDUOp.MUL,
    mul.io.out.bits(xlen - 1, 0), mul.io.out.bits(2 * xlen - 1, xlen))
  private val divResult = Mux(opReg === ysyx_26030103_MDUOp.REM ||
    opReg === ysyx_26030103_MDUOp.REMU,
    div.io.out.bits(2 * xlen - 1, xlen), div.io.out.bits(xlen - 1, 0))
  io.Resp.valid := Mux(divSelected, div.io.out.valid, mul.io.out.valid) && !io.Flush
  io.Resp.bits.Result := Mux(divSelected, divResult, mulResult)
}
