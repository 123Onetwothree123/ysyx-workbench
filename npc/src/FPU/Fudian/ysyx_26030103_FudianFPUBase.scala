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
  protected val HasD = Config.UseD && FLEN == 64

  // FPU_FUDIAN_SHARED_DIVSQRT=y 且存在 D 时 F/D 共用一套 D 精度 FDIV。
  protected val SharedDivSqrt = Config.FPUProfile.Fudian.SharedDivSqrt

  protected val SExpWidth = 8
  protected val SPrecision = 24
  protected val DExpWidth = 11
  protected val DPrecision = 53

  private val canonicalNaN32 = "h7fc00000".U(32.W)
  private val allOnes32 = "hffffffff".U(32.W)
  protected val UseFMA = Config.FPUProfile.FMA

  protected def flipSign(x: UInt, width: Int): UInt =
    x ^ (BigInt(1) << (width - 1)).U(width.W)

  // F 格式输入的 NaN-boxing 检查：未 box 的输入按规范视为规范 NaN。
  private def unboxS(x: UInt): UInt =
    if (FLEN == 32) {
      x
    } else {
      Mux(x(63, 32) === allOnes32, x(31, 0), canonicalNaN32)
    }

  // F 格式结果写回时统一 NaN-box。
  protected def boxS(x: UInt): UInt =
    if (FLEN == 32) x else Cat(allOnes32, x)

  // 把任意宽度结果截断/扩展成响应位宽。
  protected def asResp(x: UInt): UInt =
    if (x.getWidth == FLEN) x
    else if (x.getWidth > FLEN) x(FLEN - 1, 0)
    else x.pad(FLEN)

  // DYN 与保留值按 RNE 处理；上层负责在进入后端前解析 frm。
  private def resolveRm(rm: UInt): UInt =
    Mux(rm <= ysyx_26030103_FpuRm.RMM, rm, ysyx_26030103_FpuRm.RNE)

  // F 格式输入的 signaling NaN 检测（共享 DIV/SQRT 回舍时需要保留 NV）。
  protected def isSNaN32(x: UInt): Bool =
    FloatPoint.fromUInt(x, SExpWidth, SPrecision).decode.isSNaN

  protected def classifyBits(x: UInt, expWidth: Int, precision: Int): UInt = {
    val fp = FloatPoint.fromUInt(x, expWidth, precision)
    val decode = fp.decode
    val cls = Wire(UInt(10.W))
    cls := 0.U
    when(decode.isInf) {
      cls := Mux(fp.sign, 1.U(10.W), (BigInt(1) << 7).U(10.W))
    }.elsewhen(decode.isNaN) {
      cls := Mux(
        decode.isSNaN,
        (BigInt(1) << 8).U(10.W),
        (BigInt(1) << 9).U(10.W)
      )
    }.elsewhen(decode.isZero) {
      cls := Mux(fp.sign, 8.U(10.W), 16.U(10.W))
    }.elsewhen(decode.expIsZero) {
      cls := Mux(fp.sign, 4.U(10.W), 32.U(10.W))
    }.otherwise {
      cls := Mux(fp.sign, 2.U(10.W), 64.U(10.W))
    }
    cls
  }

  // RISC-V FMIN/FMAX：NaN 传播规则、-0/+0 顺序与 NV 标志。
  protected def minMaxBits(
      x: UInt,
      y: UInt,
      expWidth: Int,
      precision: Int,
      IsMax: Boolean
  ): (UInt, UInt) = {
    val fpX = FloatPoint.fromUInt(x, expWidth, precision)
    val fpY = FloatPoint.fromUInt(y, expWidth, precision)
    val decX = fpX.decode
    val decY = fpY.decode

    val xAbs = Cat(fpX.exp, fpX.sig)
    val yAbs = Cat(fpY.exp, fpY.sig)
    val xLtY = Mux(
      fpX.sign === fpY.sign,
      Mux(fpX.sign, xAbs > yAbs, xAbs < yAbs),
      fpX.sign
    )

    val bothNaN = decX.isNaN && decY.isNaN
    val ordered = Mux(
      IsMax.B,
      Mux(xLtY, y, x),
      Mux(xLtY, x, y)
    )
    val result = Mux(
      bothNaN,
      FloatPoint.defaultNaNUInt(expWidth, precision),
      Mux(decX.isNaN, y, Mux(decY.isNaN, x, ordered))
    )
    val fflags = Cat(decX.isSNaN || decY.isSNaN, 0.U(4.W))
    (result, fflags)
  }

  // 操作数与舍入模式
  protected val op = io.req.bits.op
  protected val fmtD = HasD.B && io.req.bits.fmt === ysyx_26030103_FpuFmt.D
  protected val rm = resolveRm(io.req.bits.rm)

  protected val aS = unboxS(io.req.bits.rs1)
  protected val bS = unboxS(io.req.bits.rs2)
  protected val cS = unboxS(io.req.bits.rs3)
  protected val aD = io.req.bits.rs1
  protected val bD = io.req.bits.rs2
  protected val cD = io.req.bits.rs3

  protected val isSub = op === ysyx_26030103_FpuOp.FSub
  protected val isNegA =
    op === ysyx_26030103_FpuOp.FFnma || op === ysyx_26030103_FpuOp.FFnms
  protected val isNegC =
    op === ysyx_26030103_FpuOp.FFms || op === ysyx_26030103_FpuOp.FFnma

  // intWord=true 只用低 32 位，false 用完整 XLEN；RV32 只允许 W/WU。
  private val cvtSigned = op === ysyx_26030103_FpuOp.FCvtFpToInt ||
    op === ysyx_26030103_FpuOp.FCvtIntToFp
  private val cvtLong = (XLEN == 64).B && !io.req.bits.intWord
  protected val cvtToIntOp = Cat(cvtLong, cvtSigned)

  protected val intToFpSigned = op === ysyx_26030103_FpuOp.FCvtIntToFp
  protected val intToFpLong: Bool =
    if (XLEN == 64) !io.req.bits.intWord else false.B
  private val intSrcWord: UInt =
    Cat(0.U(32.W), io.req.bits.rs1(31, 0))
  protected val intSrc64: UInt =
    if (XLEN == 64) Mux(io.req.bits.intWord, intSrcWord, io.req.bits.rs1)
    else intSrcWord

  // 按运行时 fmt 在两个精度的结果之间选择。
  protected def byFmt(s: UInt, d: => UInt): UInt =
    if (HasD) Mux(fmtD, d, s) else s
}
