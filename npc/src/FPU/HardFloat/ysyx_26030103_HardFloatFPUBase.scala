package ysyx_26030103.fpu

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.config._
import hardfloat._

// HardFloat FPU 的公共参数、输入边界和位操作辅助。
abstract class ysyx_26030103_HardFloatFPUBase(
    override val Config: ysyx_26030103_NPCConfig)
    extends ysyx_26030103_FPU(Config) {
  protected val FLEN = Config.FPULen
  protected val params = Config.FPUProfile.HardFloat
  protected val useFMA = Config.FPUProfile.FMA
  require(params.Recode,
    "Berkeley HardFloat 只能使用 recoded 内部格式；FPU_HARDFLOAT_RECODE=n 不受支持")

  protected val expS = 8
  protected val sigS = 24
  protected val expD = 11
  protected val sigD = 53
  protected val canonicalNaN32 = "h7fc00000".U(32.W)
  protected val canonicalNaN64 = "h7ff8000000000000".U(64.W)
  // HardFloat 的 detectTininess：0=before rounding，1=after rounding。
  protected val detectTininessUInt: UInt =
    (if (params.DetectTininessBeforeRounding) 0.U(1.W) else 1.U(1.W))
  protected val detectTininessBool: Bool =
    (if (params.DetectTininessBeforeRounding) false.B else true.B)

  // 输入边界：FLEN=64 时未 NaN-box 的单精度输入视为 canonical NaN
  private def isBoxedS(value: UInt): Bool =
    if (FLEN == 64) value(63, 32) === "hffffffff".U(32.W) else true.B
  private def sInput(value: UInt): UInt =
    if (FLEN == 64) Mux(isBoxedS(value), value(31, 0), canonicalNaN32)
    else value(31, 0)
  private def dInput(value: UInt): UInt =
    if (FLEN == 64) value else 0.U(64.W)

  protected def boxF32(value: UInt): UInt =
    if (FLEN == 64) Cat(Fill(FLEN - 32, 1.U(1.W)), value(31, 0))
    else value(31, 0)
  protected def boxD(value: UInt): UInt =
    if (FLEN == 64) value(63, 0) else 0.U(FLEN.W)
  protected def sext32ToFLEN(value: UInt): UInt =
    if (FLEN == 64) Cat(Fill(FLEN - 32, value(31)), value(31, 0))
    else value(31, 0)

  protected val request = io.req.bits
  protected val aRaw = request.rs1
  protected val bRaw = request.rs2
  protected val cRaw = request.rs3
  protected val aS = sInput(aRaw)
  protected val bS = sInput(bRaw)
  protected val cS = sInput(cRaw)
  protected val aD = dInput(aRaw)
  protected val bD = dInput(bRaw)
  protected val cD = dInput(cRaw)
  protected val fmtS = request.fmt === ysyx_26030103_FPUFmt.S
  protected val fmtD = request.fmt === ysyx_26030103_FPUFmt.D
  protected val srcFmtD = request.srcFmt === ysyx_26030103_FPUFmt.D
  protected val intWord = request.intWord
  protected val rm = request.rm
  protected val op = request.op

  protected val aRecS = recFNFromFN(expS, sigS, aS)
  protected val bRecS = recFNFromFN(expS, sigS, bS)
  protected val cRecS = recFNFromFN(expS, sigS, cS)
  protected val aRecD = recFNFromFN(expD, sigD, aD)
  protected val bRecD = recFNFromFN(expD, sigD, bD)
  protected val cRecD = recFNFromFN(expD, sigD, cD)

  // 辅助位操作
  protected def isNaN32(value: UInt): Bool =
    value(30, 23) === "hff".U(8.W) && value(22, 0) =/= 0.U
  protected def isSigNaN32(value: UInt): Bool =
    isNaN32(value) && !value(22)
  protected def isNaN64(value: UInt): Bool =
    value(62, 52) === "h7ff".U(11.W) && value(51, 0) =/= 0.U
  protected def isSigNaN64(value: UInt): Bool =
    isNaN64(value) && !value(51)
  protected def isZero32(value: UInt): Bool = value(30, 0) === 0.U
  protected def isZero64(value: UInt): Bool = value(62, 0) === 0.U

  // 负数转无符号整数：按舍入模式判断舍入后是否为非零负值，是则裁到 0 并置 NV。
  protected def unsignedNegInvalid(
      value: UInt, expWidth: Int, sigWidth: Int): Bool = {
    val sign = value(expWidth + sigWidth - 1)
    val exp = value(expWidth + sigWidth - 2, sigWidth - 1)
    val frac = value(sigWidth - 2, 0)
    val nonzero = value(expWidth + sigWidth - 2, 0) =/= 0.U
    val bias = (1 << (expWidth - 1)) - 1
    val geHalf = exp >= (bias - 1).U
    val aboveHalf = exp > (bias - 1).U ||
      (exp === (bias - 1).U && frac =/= 0.U)
    val geOne = exp >= bias.U
    // NaN/Inf 的裁剪值和 NV 由 RecFNToIN 负责，这里只补有限负数的 NV。
    sign && nonzero && !exp.andR && MuxLookup(rm, geOne)(
      Seq(
        ysyx_26030103_FPURm.RDN -> true.B,
        ysyx_26030103_FPURm.RMM -> geHalf,
        ysyx_26030103_FPURm.RNE -> aboveHalf
      )
    )
  }

  protected def intFlags(flags: UInt): UInt = {
    // RecFNToIN 的 {invalid, overflow, inexact} 是 HardFloat 自己的编码；
    // 输出侧按 RISC-V 只放到 NV 与 NX。
    val width = ysyx_26030103_FPUFlags.Width
    val nv = (1 << ysyx_26030103_FPUFlags.NV).U(width.W)
    val nx = (1 << ysyx_26030103_FPUFlags.NX).U(width.W)
    Mux(flags(2) || flags(1), nv, 0.U(width.W)) |
      Mux(flags(0), nx, 0.U(width.W))
  }
}
