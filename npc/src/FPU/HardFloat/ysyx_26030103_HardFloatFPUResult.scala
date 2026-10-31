package ysyx_26030103.fpu

import chisel3._
import chisel3.util._
import hardfloat._

// HardFloat 组合结果选择：min/max、符号注入、转换结果和 fastResult。
trait ysyx_26030103_HardFloatFPUResult {
  this: ysyx_26030103_HardFloatFPUBase with ysyx_26030103_HardFloatFPUArith =>

  val cvtUNegInvalidS = UnsignedNegInvalid(aS, ExpS, SigS)
  val cvtUNegInvalidD = UnsignedNegInvalid(aD, ExpD, SigD)

  private def MinMaxS(IsMin: Bool): (UInt, UInt) = {
    val bothNaN = IsNaN32(aS) && IsNaN32(bS)
    val aNaN = IsNaN32(aS)
    val bNaN = IsNaN32(bS)
    val bothZero = IsZero32(aS) && IsZero32(bS)
    val zeroSign = Mux(IsMin, aS(31) | bS(31), aS(31) & bS(31))
    val chosen = Mux(
      IsMin,
      Mux(cmpS.io.lt, aS, bS),
      Mux(cmpS.io.gt, aS, bS)
    )
    val result = Mux(
      bothNaN,
      CanonicalNaN32,
      Mux(aNaN, bS, Mux(bNaN, aS,
        Mux(bothZero, Cat(zeroSign, 0.U(31.W)), chosen)))
    )
    val flags = Cat(IsSigNaN32(aS) || IsSigNaN32(bS), 0.U(4.W))
    (result, flags)
  }
  private def MinMaxD(IsMin: Bool): (UInt, UInt) = {
    val bothNaN = IsNaN64(aD) && IsNaN64(bD)
    val aNaN = IsNaN64(aD)
    val bNaN = IsNaN64(bD)
    val bothZero = IsZero64(aD) && IsZero64(bD)
    val zeroSign = Mux(IsMin, aD(63) | bD(63), aD(63) & bD(63))
    val chosen = Mux(
      IsMin,
      Mux(cmpD.io.lt, aD, bD),
      Mux(cmpD.io.gt, aD, bD)
    )
    val result = Mux(
      bothNaN,
      CanonicalNaN64,
      Mux(aNaN, bD, Mux(bNaN, aD,
        Mux(bothZero, Cat(zeroSign, 0.U(63.W)), chosen)))
    )
    val flags = Cat(IsSigNaN64(aD) || IsSigNaN64(bD), 0.U(4.W))
    (result, flags)
  }

  private def SgnjS(Operation: UInt): UInt = {
    val sign = MuxLookup(Operation, bS(31))(
      Seq(
        ysyx_26030103_FpuOp.FSgnj -> bS(31),
        ysyx_26030103_FpuOp.FSgnjn -> (!bS(31)),
        ysyx_26030103_FpuOp.FSgnjx -> (aS(31) ^ bS(31))
      )
    )
    Cat(sign, aS(30, 0))
  }
  private def SgnjD(Operation: UInt): UInt = {
    val sign = MuxLookup(Operation, bD(63))(
      Seq(
        ysyx_26030103_FpuOp.FSgnj -> bD(63),
        ysyx_26030103_FpuOp.FSgnjn -> (!bD(63)),
        ysyx_26030103_FpuOp.FSgnjx -> (aD(63) ^ bD(63))
      )
    )
    Cat(sign, aD(62, 0))
  }

  // 组合结果选择：min/max 实例常量化方向，再由 FpuOp 选结果
  val minS = MinMaxS(true.B)
  val maxS = MinMaxS(false.B)
  val minD = MinMaxD(true.B)
  val maxD = MinMaxD(false.B)
  val mmSResult = Mux(op === ysyx_26030103_FpuOp.FMin, minS._1, maxS._1)
  val mmSFlags = Mux(op === ysyx_26030103_FpuOp.FMin, minS._2, maxS._2)
  val mmDResult = Mux(op === ysyx_26030103_FpuOp.FMin, minD._1, maxD._1)
  val mmDFlags = Mux(op === ysyx_26030103_FpuOp.FMin, minD._2, maxD._2)

  val classS = classifyRecFN(ExpS, SigS, recFNFromFN(ExpS, SigS, aS))
  val classD = classifyRecFN(ExpD, SigD, recFNFromFN(ExpD, SigD, aD))

  val intFromS32 = toInt32S.io.out
  val intFromS64 = toInt64S.io.out
  val intFromD32 = toInt32D.io.out
  val intFromD64 = toInt64D.io.out
  val intFromS = Mux(intWord, Sext32ToFLEN(intFromS32), intFromS64)
  val intFromD = Mux(intWord, Sext32ToFLEN(intFromD32), intFromD64)
  val intFlagsS = Mux(intWord, IntFlags(toInt32S.io.intExceptionFlags),
    IntFlags(toInt64S.io.intExceptionFlags))
  val intFlagsD = Mux(intWord, IntFlags(toInt32D.io.intExceptionFlags),
    IntFlags(toInt64D.io.intExceptionFlags))

  val intToS = Mux(intWord,
    fNFromRecFN(ExpS, SigS, fromInt32S.io.out),
    fNFromRecFN(ExpS, SigS, fromInt64S.io.out))
  val intToD = Mux(intWord,
    fNFromRecFN(ExpD, SigD, fromInt32D.io.out),
    fNFromRecFN(ExpD, SigD, fromInt64D.io.out))
  val intToFlagsS = Mux(intWord, fromInt32S.io.exceptionFlags,
    fromInt64S.io.exceptionFlags)
  val intToFlagsD = Mux(intWord, fromInt32D.io.exceptionFlags,
    fromInt64D.io.exceptionFlags)

  val cvtToD = fNFromRecFN(ExpD, SigD, cvtStoD.io.out)
  val cvtToS = fNFromRecFN(ExpS, SigS, cvtDtoS.io.out)

  // 先把组合运算结果汇成 (result, flags, isFPResult)，再由 op 选择。
  val fastResult = WireDefault(0.U(FLEN.W))
  val fastFlags = WireDefault(0.U(5.W))
  val isDivSqrt = op === ysyx_26030103_FpuOp.FDiv ||
    op === ysyx_26030103_FpuOp.FSqrt

  val fmaResultS: UInt =
    if (UseFMA) fNFromRecFN(ExpS, SigS, fmaS.get.io.out)
    else exactFmaS.get.io.result
  val fmaResultD: UInt =
    if (UseFMA) fNFromRecFN(ExpD, SigD, fmaD.get.io.out)
    else exactFmaD.get.io.result
  val fmaFlagsS: UInt =
    if (UseFMA) fmaS.get.io.exceptionFlags else exactFmaS.get.io.fflags
  val fmaFlagsD: UInt =
    if (UseFMA) fmaD.get.io.exceptionFlags else exactFmaD.get.io.fflags

  switch(op) {
    is(ysyx_26030103_FpuOp.FAdd, ysyx_26030103_FpuOp.FSub) {
      fastResult := Mux(fmtS,
        BoxF32(fNFromRecFN(ExpS, SigS, addS.io.out)),
        BoxD(fNFromRecFN(ExpD, SigD, addD.io.out)))
      fastFlags := Mux(fmtS, addS.io.exceptionFlags, addD.io.exceptionFlags)
    }
    is(ysyx_26030103_FpuOp.FMul) {
      fastResult := Mux(fmtS,
        BoxF32(fNFromRecFN(ExpS, SigS, mulS.io.out)),
        BoxD(fNFromRecFN(ExpD, SigD, mulD.io.out)))
      fastFlags := Mux(fmtS, mulS.io.exceptionFlags, mulD.io.exceptionFlags)
    }
    is(ysyx_26030103_FpuOp.FFma, ysyx_26030103_FpuOp.FFms,
      ysyx_26030103_FpuOp.FFnma, ysyx_26030103_FpuOp.FFnms) {
      fastResult := Mux(fmtS, BoxF32(fmaResultS), BoxD(fmaResultD))
      fastFlags := Mux(fmtS, fmaFlagsS, fmaFlagsD)
    }
    is(ysyx_26030103_FpuOp.FSgnj, ysyx_26030103_FpuOp.FSgnjn,
      ysyx_26030103_FpuOp.FSgnjx) {
      fastResult := Mux(fmtS, BoxF32(SgnjS(op)), BoxD(SgnjD(op)))
      fastFlags := 0.U(5.W)
    }
    is(ysyx_26030103_FpuOp.FMin, ysyx_26030103_FpuOp.FMax) {
      fastResult := Mux(fmtS, BoxF32(mmSResult), BoxD(mmDResult))
      fastFlags := Mux(fmtS, mmSFlags, mmDFlags)
    }
    is(ysyx_26030103_FpuOp.FEq, ysyx_26030103_FpuOp.FLt,
      ysyx_26030103_FpuOp.FLe) {
      // CompareRecFN 没有 le 输出；FLE = lt || eq，且 NaN 时两者都为 0。
      val leS = cmpS.io.lt || cmpS.io.eq
      val leD = cmpD.io.lt || cmpD.io.eq
      val finalS = Mux(op === ysyx_26030103_FpuOp.FEq, cmpS.io.eq,
        Mux(op === ysyx_26030103_FpuOp.FLt, cmpS.io.lt, leS))
      val finalD = Mux(op === ysyx_26030103_FpuOp.FEq, cmpD.io.eq,
        Mux(op === ysyx_26030103_FpuOp.FLt, cmpD.io.lt, leD))
      fastResult := Mux(fmtS, finalS, finalD)
      fastFlags := Mux(fmtS, cmpS.io.exceptionFlags, cmpD.io.exceptionFlags)
    }
    is(ysyx_26030103_FpuOp.FCvtFpToInt, ysyx_26030103_FpuOp.FCvtFpToUInt) {
      val negInvalidS =
        op === ysyx_26030103_FpuOp.FCvtFpToUInt && cvtUNegInvalidS
      val negInvalidD =
        op === ysyx_26030103_FpuOp.FCvtFpToUInt && cvtUNegInvalidD
      fastResult := Mux(fmtS,
        Mux(negInvalidS, 0.U(FLEN.W), intFromS),
        Mux(negInvalidD, 0.U(FLEN.W), intFromD))
      // 非法负数转无符号只保留 NV，不额外报 NX。
      fastFlags := Mux(fmtS,
        Mux(negInvalidS, "h10".U(5.W), intFlagsS),
        Mux(negInvalidD, "h10".U(5.W), intFlagsD))
    }
    is(ysyx_26030103_FpuOp.FCvtIntToFp, ysyx_26030103_FpuOp.FCvtUIntToFp) {
      fastResult := Mux(fmtS, BoxF32(intToS), BoxD(intToD))
      fastFlags := Mux(fmtS, intToFlagsS, intToFlagsD)
    }
    is(ysyx_26030103_FpuOp.FMvFpToInt) {
      fastResult := Mux(intWord,
        Sext32ToFLEN(aRaw(31, 0)), aRaw)
      fastFlags := 0.U(5.W)
    }
    is(ysyx_26030103_FpuOp.FMvIntToFp) {
      fastResult := Mux(fmtS,
        BoxF32(aRaw(31, 0)), BoxD(aRaw))
      fastFlags := 0.U(5.W)
    }
    is(ysyx_26030103_FpuOp.FClass) {
      fastResult := Mux(fmtS, classS, classD)
      fastFlags := 0.U(5.W)
    }
    is(ysyx_26030103_FpuOp.FCvtFpToFp) {
      val toDouble = fmtD && !srcFmtD
      val toSingle = fmtS && srcFmtD
      fastResult := Mux(toDouble, BoxD(cvtToD),
        Mux(toSingle, BoxF32(cvtToS), 0.U(FLEN.W)))
      fastFlags := Mux(toDouble, cvtStoD.io.exceptionFlags,
        Mux(toSingle, cvtDtoS.io.exceptionFlags, 0.U(5.W)))
    }
  }
}
