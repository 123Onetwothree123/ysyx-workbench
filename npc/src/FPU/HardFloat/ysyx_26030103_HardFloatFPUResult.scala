package ysyx_26030103.fpu

import chisel3._
import chisel3.util._
import hardfloat._

// HardFloat 组合结果选择：min/max、符号注入、转换结果和 fastResult。
trait ysyx_26030103_HardFloatFPUResult {
  this: ysyx_26030103_HardFloatFPUBase with ysyx_26030103_HardFloatFPUArith =>

  val cvtUNegInvalidS = unsignedNegInvalid(aS, expS, sigS)
  val cvtUNegInvalidD = unsignedNegInvalid(aD, expD, sigD)

  private def minMaxS(isMin: Bool): (UInt, UInt) = {
    val bothNaN = isNaN32(aS) && isNaN32(bS)
    val aNaN = isNaN32(aS)
    val bNaN = isNaN32(bS)
    val bothZero = isZero32(aS) && isZero32(bS)
    val zeroSign = Mux(isMin, aS(31) | bS(31), aS(31) & bS(31))
    val chosen = Mux(
      isMin,
      Mux(cmpS.io.lt, aS, bS),
      Mux(cmpS.io.gt, aS, bS)
    )
    val result = Mux(
      bothNaN,
      canonicalNaN32,
      Mux(aNaN, bS, Mux(bNaN, aS,
        Mux(bothZero, Cat(zeroSign, 0.U(31.W)), chosen)))
    )
    val flags = Cat(isSigNaN32(aS) || isSigNaN32(bS),
      0.U(ysyx_26030103_FPUFlags.NV.W))
    (result, flags)
  }
  private def minMaxD(isMin: Bool): (UInt, UInt) = {
    val bothNaN = isNaN64(aD) && isNaN64(bD)
    val aNaN = isNaN64(aD)
    val bNaN = isNaN64(bD)
    val bothZero = isZero64(aD) && isZero64(bD)
    val zeroSign = Mux(isMin, aD(63) | bD(63), aD(63) & bD(63))
    val chosen = Mux(
      isMin,
      Mux(cmpD.io.lt, aD, bD),
      Mux(cmpD.io.gt, aD, bD)
    )
    val result = Mux(
      bothNaN,
      canonicalNaN64,
      Mux(aNaN, bD, Mux(bNaN, aD,
        Mux(bothZero, Cat(zeroSign, 0.U(63.W)), chosen)))
    )
    val flags = Cat(isSigNaN64(aD) || isSigNaN64(bD),
      0.U(ysyx_26030103_FPUFlags.NV.W))
    (result, flags)
  }

  private def sgnjS(operation: UInt): UInt = {
    val sign = MuxLookup(operation, bS(31))(
      Seq(
        ysyx_26030103_FPUOp.FSgnj -> bS(31),
        ysyx_26030103_FPUOp.FSgnjn -> (!bS(31)),
        ysyx_26030103_FPUOp.FSgnjx -> (aS(31) ^ bS(31))
      )
    )
    Cat(sign, aS(30, 0))
  }
  private def sgnjD(operation: UInt): UInt = {
    val sign = MuxLookup(operation, bD(63))(
      Seq(
        ysyx_26030103_FPUOp.FSgnj -> bD(63),
        ysyx_26030103_FPUOp.FSgnjn -> (!bD(63)),
        ysyx_26030103_FPUOp.FSgnjx -> (aD(63) ^ bD(63))
      )
    )
    Cat(sign, aD(62, 0))
  }

  // 组合结果选择：min/max 实例常量化方向，再由 FpuOp 选结果
  val minS = minMaxS(true.B)
  val maxS = minMaxS(false.B)
  val minD = minMaxD(true.B)
  val maxD = minMaxD(false.B)
  val mmSResult = Mux(op === ysyx_26030103_FPUOp.FMin, minS._1, maxS._1)
  val mmSFlags = Mux(op === ysyx_26030103_FPUOp.FMin, minS._2, maxS._2)
  val mmDResult = Mux(op === ysyx_26030103_FPUOp.FMin, minD._1, maxD._1)
  val mmDFlags = Mux(op === ysyx_26030103_FPUOp.FMin, minD._2, maxD._2)

  val classS = classifyRecFN(expS, sigS, aRecS)
  val classD = classifyRecFN(expD, sigD, aRecD)

  val intFromS32 = toInt32S.io.out
  val intFromS64 = toInt64S.io.out
  val intFromD32 = toInt32D.io.out
  val intFromD64 = toInt64D.io.out
  val intFromS = Mux(intWord, sext32ToFLEN(intFromS32), intFromS64)
  val intFromD = Mux(intWord, sext32ToFLEN(intFromD32), intFromD64)
  val intFlagsS = Mux(intWord, intFlags(toInt32S.io.intExceptionFlags),
    intFlags(toInt64S.io.intExceptionFlags))
  val intFlagsD = Mux(intWord, intFlags(toInt32D.io.intExceptionFlags),
    intFlags(toInt64D.io.intExceptionFlags))

  val intToS = Mux(intWord,
    fNFromRecFN(expS, sigS, fromInt32S.io.out),
    fNFromRecFN(expS, sigS, fromInt64S.io.out))
  val intToD = Mux(intWord,
    fNFromRecFN(expD, sigD, fromInt32D.io.out),
    fNFromRecFN(expD, sigD, fromInt64D.io.out))
  val intToFlagsS = Mux(intWord, fromInt32S.io.exceptionFlags,
    fromInt64S.io.exceptionFlags)
  val intToFlagsD = Mux(intWord, fromInt32D.io.exceptionFlags,
    fromInt64D.io.exceptionFlags)

  val cvtToD = fNFromRecFN(expD, sigD, cvtStoD.io.out)
  val cvtToS = fNFromRecFN(expS, sigS, cvtDtoS.io.out)

  // 先把组合运算结果汇成 (result, flags, isFPResult)，再由 op 选择。
  val fastResult = WireDefault(0.U(FLEN.W))
  val fastFlags = WireDefault(0.U(ysyx_26030103_FPUFlags.Width.W))
  val isDivSqrt = op === ysyx_26030103_FPUOp.FDiv ||
    op === ysyx_26030103_FPUOp.FSqrt

  val fmaResultS: UInt =
    if (useFMA) fNFromRecFN(expS, sigS, fmaS.get.io.out)
    else exactFmaS.get.io.result
  val fmaResultD: UInt =
    if (useFMA) fNFromRecFN(expD, sigD, fmaD.get.io.out)
    else exactFmaD.get.io.result
  val fmaFlagsS: UInt =
    if (useFMA) fmaS.get.io.exceptionFlags else exactFmaS.get.io.fflags
  val fmaFlagsD: UInt =
    if (useFMA) fmaD.get.io.exceptionFlags else exactFmaD.get.io.fflags

  switch(op) {
    is(ysyx_26030103_FPUOp.FAdd, ysyx_26030103_FPUOp.FSub) {
      fastResult := Mux(fmtS,
        boxF32(fNFromRecFN(expS, sigS, addS.io.out)),
        boxD(fNFromRecFN(expD, sigD, addD.io.out)))
      fastFlags := Mux(fmtS, addS.io.exceptionFlags, addD.io.exceptionFlags)
    }
    is(ysyx_26030103_FPUOp.FMul) {
      fastResult := Mux(fmtS,
        boxF32(fNFromRecFN(expS, sigS, mulS.io.out)),
        boxD(fNFromRecFN(expD, sigD, mulD.io.out)))
      fastFlags := Mux(fmtS, mulS.io.exceptionFlags, mulD.io.exceptionFlags)
    }
    is(ysyx_26030103_FPUOp.FFma, ysyx_26030103_FPUOp.FFms,
      ysyx_26030103_FPUOp.FFnma, ysyx_26030103_FPUOp.FFnms) {
      fastResult := Mux(fmtS, boxF32(fmaResultS), boxD(fmaResultD))
      fastFlags := Mux(fmtS, fmaFlagsS, fmaFlagsD)
    }
    is(ysyx_26030103_FPUOp.FSgnj, ysyx_26030103_FPUOp.FSgnjn,
      ysyx_26030103_FPUOp.FSgnjx) {
      fastResult := Mux(fmtS, boxF32(sgnjS(op)), boxD(sgnjD(op)))
      fastFlags := 0.U(ysyx_26030103_FPUFlags.Width.W)
    }
    is(ysyx_26030103_FPUOp.FMin, ysyx_26030103_FPUOp.FMax) {
      fastResult := Mux(fmtS, boxF32(mmSResult), boxD(mmDResult))
      fastFlags := Mux(fmtS, mmSFlags, mmDFlags)
    }
    is(ysyx_26030103_FPUOp.FEq, ysyx_26030103_FPUOp.FLt,
      ysyx_26030103_FPUOp.FLe) {
      // CompareRecFN 没有 le 输出；FLE = lt || eq，且 NaN 时两者都为 0。
      val leS = cmpS.io.lt || cmpS.io.eq
      val leD = cmpD.io.lt || cmpD.io.eq
      val finalS = Mux(op === ysyx_26030103_FPUOp.FEq, cmpS.io.eq,
        Mux(op === ysyx_26030103_FPUOp.FLt, cmpS.io.lt, leS))
      val finalD = Mux(op === ysyx_26030103_FPUOp.FEq, cmpD.io.eq,
        Mux(op === ysyx_26030103_FPUOp.FLt, cmpD.io.lt, leD))
      fastResult := Mux(fmtS, finalS, finalD)
      fastFlags := Mux(fmtS, cmpS.io.exceptionFlags, cmpD.io.exceptionFlags)
    }
    is(ysyx_26030103_FPUOp.FCvtFPToInt, ysyx_26030103_FPUOp.FCvtFPToUInt) {
      val negInvalidS =
        op === ysyx_26030103_FPUOp.FCvtFPToUInt && cvtUNegInvalidS
      val negInvalidD =
        op === ysyx_26030103_FPUOp.FCvtFPToUInt && cvtUNegInvalidD
      fastResult := Mux(fmtS,
        Mux(negInvalidS, 0.U(FLEN.W), intFromS),
        Mux(negInvalidD, 0.U(FLEN.W), intFromD))
      // 非法负数转无符号只保留 NV，不额外报 NX。
      val nvOnly = (1 << ysyx_26030103_FPUFlags.NV).U(
        ysyx_26030103_FPUFlags.Width.W)
      fastFlags := Mux(fmtS,
        Mux(negInvalidS, nvOnly, intFlagsS),
        Mux(negInvalidD, nvOnly, intFlagsD))
    }
    is(ysyx_26030103_FPUOp.FCvtIntToFP, ysyx_26030103_FPUOp.FCvtUIntToFP) {
      fastResult := Mux(fmtS, boxF32(intToS), boxD(intToD))
      fastFlags := Mux(fmtS, intToFlagsS, intToFlagsD)
    }
    is(ysyx_26030103_FPUOp.FMvFPToInt) {
      fastResult := Mux(intWord,
        sext32ToFLEN(aRaw(31, 0)), aRaw)
      fastFlags := 0.U(ysyx_26030103_FPUFlags.Width.W)
    }
    is(ysyx_26030103_FPUOp.FMvIntToFP) {
      fastResult := Mux(fmtS,
        boxF32(aRaw(31, 0)), boxD(aRaw))
      fastFlags := 0.U(ysyx_26030103_FPUFlags.Width.W)
    }
    is(ysyx_26030103_FPUOp.FClass) {
      fastResult := Mux(fmtS, classS, classD)
      fastFlags := 0.U(ysyx_26030103_FPUFlags.Width.W)
    }
    is(ysyx_26030103_FPUOp.FCvtFPToFP) {
      val toDouble = fmtD && !SourceFmtDouble
      val toSingle = fmtS && SourceFmtDouble
      fastResult := Mux(toDouble, boxD(cvtToD),
        Mux(toSingle, boxF32(cvtToS), 0.U(FLEN.W)))
      fastFlags := Mux(toDouble, cvtStoD.io.exceptionFlags,
        Mux(toSingle, cvtDtoS.io.exceptionFlags,
          0.U(ysyx_26030103_FPUFlags.Width.W)))
    }
  }
}
