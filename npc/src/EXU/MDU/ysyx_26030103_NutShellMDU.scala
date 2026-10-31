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

  // 单笔在途 + 结果保持：NutShell 的 DIV 只在 s_finish 一拍拉高 valid，
  // 不能把核心的 out.ready 直接接到 io.Resp.ready。这里先捕获结果，
  // 等 io.Resp.ready 真正握手后再释放请求槽。
  private val divSelected = RegInit(false.B)
  private val opReg = RegInit(0.U(ysyx_26030103_MDUOp.Width.W))
  private val busy = RegInit(false.B)
  private val pending = RegInit(false.B)
  private val resultReg = RegInit(0.U(xlen.W))
  private val mulResult = Mux(opReg === ysyx_26030103_MDUOp.MUL,
    mul.io.out.bits(xlen - 1, 0), mul.io.out.bits(2 * xlen - 1, xlen))
  private val divResult = Mux(opReg === ysyx_26030103_MDUOp.REM ||
    opReg === ysyx_26030103_MDUOp.REMU,
    div.io.out.bits(2 * xlen - 1, xlen), div.io.out.bits(xlen - 1, 0))
  private val selectedOutValid = Mux(divSelected, div.io.out.valid, mul.io.out.valid)
  private val selectedResult = Mux(divSelected, divResult, mulResult)

  io.Req.ready := !io.Flush && !busy && !pending &&
    Mux(isDiv, div.io.in.ready, mul.io.in.ready)
  mul.io.in.valid := io.Req.fire && !isDiv && !io.Flush
  mul.io.in.bits(0) := mulLhs
  mul.io.in.bits(1) := mulRhs
  mul.io.flush := io.Flush
  mul.io.out.ready := !io.Flush && !pending

  div.io.in.valid := io.Req.fire && isDiv && !io.Flush
  div.io.in.bits(0) := lhs
  div.io.in.bits(1) := rhs
  div.io.sign := isDivSign
  div.io.flush := io.Flush
  div.io.out.ready := !io.Flush && !pending

  when(io.Req.fire) {
    divSelected := isDiv
    opReg := op
    busy := true.B
  }
  when(selectedOutValid && !pending && !io.Flush) {
    pending := true.B
    resultReg := selectedResult
  }
  io.Resp.valid := pending && !io.Flush
  io.Resp.bits.Result := resultReg
  when(io.Resp.fire) {
    pending := false.B
    busy := false.B
  }
  when(io.Flush) {
    pending := false.B
    busy := false.B
  }
}
