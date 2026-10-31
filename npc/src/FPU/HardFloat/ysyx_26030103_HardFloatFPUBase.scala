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
  private val HasD = Config.UseD && FLEN == 64
  protected val Params = Config.FPUProfile.HardFloat
  protected val UseFMA = Config.FPUProfile.FMA
  require(Params.Recode,
    "Berkeley HardFloat 只能使用 recoded 内部格式；FPU_HARDFLOAT_RECODE=n 不受支持")

  protected val ExpS = 8
  protected val SigS = 24
  protected val ExpD = 11
  protected val SigD = 53
  protected val CanonicalNaN32 = "h7fc00000".U(32.W)
  protected val CanonicalNaN64 = "h7ff8000000000000".U(64.W)
  // HardFloat 的 detectTininess：0=before rounding，1=after rounding。
  protected val DetectTininessUInt: UInt =
    (if (Params.DetectTininessBeforeRounding) 0.U(1.W) else 1.U(1.W))
  protected val DetectTininessBool: Bool =
    (if (Params.DetectTininessBeforeRounding) false.B else true.B)

  // 输入边界：FLEN=64 时未 NaN-box 的单精度输入视为 canonical NaN
  private def IsBoxedS(Value: UInt): Bool =
    if (FLEN == 64) Value(63, 32) === "hffffffff".U(32.W) else true.B
  private def SInput(Value: UInt): UInt =
    if (FLEN == 64) Mux(IsBoxedS(Value), Value(31, 0), CanonicalNaN32)
    else Value(31, 0)
  private def DInput(Value: UInt): UInt =
    if (FLEN == 64) Value else 0.U(64.W)

  protected def BoxF32(Value: UInt): UInt =
    if (FLEN == 64) Cat(Fill(FLEN - 32, 1.U(1.W)), Value(31, 0))
    else Value(31, 0)
  protected def BoxD(Value: UInt): UInt =
    if (FLEN == 64) Value(63, 0) else 0.U(FLEN.W)
  protected def Sext32ToFLEN(Value: UInt): UInt =
    if (FLEN == 64) Cat(Fill(FLEN - 32, Value(31)), Value(31, 0))
    else Value(31, 0)

  protected val req = io.req.bits
  protected val aRaw = req.rs1
  protected val bRaw = req.rs2
  protected val cRaw = req.rs3
  protected val aS = SInput(aRaw)
  protected val bS = SInput(bRaw)
  protected val cS = SInput(cRaw)
  protected val aD = DInput(aRaw)
  protected val bD = DInput(bRaw)
  protected val cD = DInput(cRaw)
  protected val fmtS = req.fmt === ysyx_26030103_FpuFmt.S
  protected val fmtD = req.fmt === ysyx_26030103_FpuFmt.D
  protected val srcFmtD = req.srcFmt === ysyx_26030103_FpuFmt.D
  protected val intWord = req.intWord
  protected val rm = req.rm
  protected val op = req.op

  protected val aRecS = recFNFromFN(ExpS, SigS, aS)
  protected val bRecS = recFNFromFN(ExpS, SigS, bS)
  protected val cRecS = recFNFromFN(ExpS, SigS, cS)
  protected val aRecD = recFNFromFN(ExpD, SigD, aD)
  protected val bRecD = recFNFromFN(ExpD, SigD, bD)
  protected val cRecD = recFNFromFN(ExpD, SigD, cD)

  // 辅助位操作
  protected def IsNaN32(Value: UInt): Bool =
    Value(30, 23) === "hff".U(8.W) && Value(22, 0) =/= 0.U
  protected def IsSigNaN32(Value: UInt): Bool =
    IsNaN32(Value) && !Value(22)
  protected def IsNaN64(Value: UInt): Bool =
    Value(62, 52) === "h7ff".U(11.W) && Value(51, 0) =/= 0.U
  protected def IsSigNaN64(Value: UInt): Bool =
    IsNaN64(Value) && !Value(51)
  protected def IsZero32(Value: UInt): Bool = Value(30, 0) === 0.U
  protected def IsZero64(Value: UInt): Bool = Value(62, 0) === 0.U

  // 负数转无符号整数：按舍入模式判断舍入后是否为非零负值，是则裁到 0 并置 NV。
  protected def UnsignedNegInvalid(
      Value: UInt, ExpWidth: Int, SigWidth: Int): Bool = {
    val sign = Value(ExpWidth + SigWidth - 1)
    val exp = Value(ExpWidth + SigWidth - 2, SigWidth - 1)
    val frac = Value(SigWidth - 2, 0)
    val nonzero = Value(ExpWidth + SigWidth - 2, 0) =/= 0.U
    val bias = (1 << (ExpWidth - 1)) - 1
    val geHalf = exp >= (bias - 1).U
    val aboveHalf = exp > (bias - 1).U ||
      (exp === (bias - 1).U && frac =/= 0.U)
    val geOne = exp >= bias.U
    // NaN/Inf 的裁剪值和 NV 由 RecFNToIN 负责，这里只补有限负数的 NV。
    sign && nonzero && !exp.andR && MuxLookup(rm, geOne)(
      Seq(
        ysyx_26030103_FpuRm.RDN -> true.B,
        ysyx_26030103_FpuRm.RMM -> geHalf,
        ysyx_26030103_FpuRm.RNE -> aboveHalf
      )
    )
  }

  protected def IntFlags(Flags: UInt): UInt =
    // RecFNToIN: {invalid, overflow, inexact} -> RISC-V {NV, -, -, -, NX}
    Cat(Flags(2) || Flags(1), 0.U(3.W), Flags(0))
}
