package ysyx_26030103.fpu

import chisel3._
import chisel3.util._
import _root_.fudian._
import _root_.ysyx_26030103.config._

// Fudian FPU 的公共参数、输入边界和位操作辅助。
abstract class ysyx_26030103_FudianFPUBase(
    override val Config: ysyx_26030103_NPCConfig)
    extends ysyx_26030103_FPU(Config) {
  protected val FLEN = Config.FPULen
  protected val XLEN = Config.XLEN
  protected val hasD = Config.UseD && FLEN == 64

  // FPU_FUDIAN_SHARED_DIVSQRT=y 且存在 D 时 F/D 共用一套 D 精度 FDIV。
  protected val sharedDivSqrt = Config.FPUProfile.Fudian.SharedDivSqrt

  protected val sExpWidth = 8
  protected val sPrecision = 24
  protected val dExpWidth = 11
  protected val dPrecision = 53

  protected val useFMA = Config.FPUProfile.FMA

  protected def flipSign(value: UInt, width: Int): UInt =
    ysyx_26030103_FPUFormat.FlipSign(value, width)

  // F 格式输入的 NaN-boxing 检查：未 box 的输入按规范视为规范 NaN。
  private def unboxS(value: UInt): UInt =
    ysyx_26030103_FPUFormat.unboxS(value, FLEN)

  // F 格式结果写回时统一 NaN-box。
  protected def boxS(value: UInt): UInt =
    ysyx_26030103_FPUFormat.boxS(value, FLEN)

  // 把任意宽度结果截断/扩展成响应位宽。
  protected def asResponse(value: UInt): UInt =
    if (value.getWidth == FLEN) value
    else if (value.getWidth > FLEN) value(FLEN - 1, 0)
    else value.pad(FLEN)

  // DYN 与保留值按 RNE 处理；上层负责在进入后端前解析 frm。
  private def resolveRm(rm: UInt): UInt =
    Mux(rm <= ysyx_26030103_FPURm.RMM, rm, ysyx_26030103_FPURm.RNE)

  // F 格式输入的 signaling NaN 检测（共享 DIV/SQRT 回舍时需要保留 NV）。
  protected def isSNaN32(value: UInt): Bool =
    FloatPoint.fromUInt(value, sExpWidth, sPrecision).decode.isSNaN

  protected def classifyBits(value: UInt, expWidth: Int, precision: Int): UInt = {
    val FP = FloatPoint.fromUInt(value, expWidth, precision)
    val decode = FP.decode
    val cls = Wire(UInt(10.W))
    cls := 0.U
    when(decode.isInf) {
      cls := Mux(FP.sign, 1.U(10.W), (BigInt(1) << 7).U(10.W))
    }.elsewhen(decode.isNaN) {
      cls := Mux(
        decode.isSNaN,
        (BigInt(1) << 8).U(10.W),
        (BigInt(1) << 9).U(10.W)
      )
    }.elsewhen(decode.isZero) {
      cls := Mux(FP.sign, 8.U(10.W), 16.U(10.W))
    }.elsewhen(decode.expIsZero) {
      cls := Mux(FP.sign, 4.U(10.W), 32.U(10.W))
    }.otherwise {
      cls := Mux(FP.sign, 2.U(10.W), 64.U(10.W))
    }
    cls
  }

  // RISC-V FMIN/FMAX：NaN 传播规则、-0/+0 顺序与 NV 标志。
  protected def minMaxBits(
      lhs: UInt,
      rhs: UInt,
      expWidth: Int,
      precision: Int,
      isMax: Boolean
  ): (UInt, UInt) = {
    val FPX = FloatPoint.fromUInt(lhs, expWidth, precision)
    val FPY = FloatPoint.fromUInt(rhs, expWidth, precision)
    val DecodeX = FPX.decode
    val DecodeY = FPY.decode

    val xAbs = Cat(FPX.exp, FPX.sig)
    val yAbs = Cat(FPY.exp, FPY.sig)
    val xLtY = Mux(
      FPX.sign === FPY.sign,
      Mux(FPX.sign, xAbs > yAbs, xAbs < yAbs),
      FPX.sign
    )

    val bothNaN = DecodeX.isNaN && DecodeY.isNaN
    val ordered = Mux(
      isMax.B,
      Mux(xLtY, rhs, lhs),
      Mux(xLtY, lhs, rhs)
    )
    val result = Mux(
      bothNaN,
      FloatPoint.defaultNaNUInt(expWidth, precision),
      Mux(DecodeX.isNaN, rhs, Mux(DecodeY.isNaN, lhs, ordered))
    )
    val fflags = ysyx_26030103_FPUFlags.Only(
      ysyx_26030103_FPUFlags.NV,
      DecodeX.isSNaN || DecodeY.isSNaN
    )
    (result, fflags)
  }

  // 操作数与舍入模式
  protected val op = io.request.bits.op
  protected val fmtD = hasD.B && io.request.bits.fmt === ysyx_26030103_FPUFmt.D
  protected val rm = resolveRm(io.request.bits.rm)

  protected val aS = unboxS(io.request.bits.rs1)
  protected val bS = unboxS(io.request.bits.rs2)
  protected val cS = unboxS(io.request.bits.rs3)
  protected val aD = io.request.bits.rs1
  protected val bD = io.request.bits.rs2
  protected val cD = io.request.bits.rs3

  protected val isSub = op === ysyx_26030103_FPUOp.FSub

  // intWord=true 只用低 32 位，false 用完整 XLEN；RV32 只允许 W/WU。
  private val cvtSigned = op === ysyx_26030103_FPUOp.FCvtFPToInt ||
    op === ysyx_26030103_FPUOp.FCvtIntToFP
  private val cvtLong = (XLEN == 64).B && !io.request.bits.intWord
  protected val cvtToIntOp = Cat(cvtLong, cvtSigned)

  protected val IntToFPSigned = op === ysyx_26030103_FPUOp.FCvtIntToFP
  protected val IntToFPLong: Bool =
    if (XLEN == 64) !io.request.bits.intWord else false.B
  private val IntSourceWord: UInt =
    Cat(0.U(32.W), io.request.bits.rs1(31, 0))
  protected val intSrc64: UInt =
    if (XLEN == 64) Mux(io.request.bits.intWord, IntSourceWord, io.request.bits.rs1)
    else IntSourceWord

  // 按运行时 fmt 在两个精度的结果之间选择。
  protected def byFmt(singleValue: UInt, doubleValue: => UInt): UInt =
    if (hasD) Mux(fmtD, doubleValue, singleValue) else singleValue
}
