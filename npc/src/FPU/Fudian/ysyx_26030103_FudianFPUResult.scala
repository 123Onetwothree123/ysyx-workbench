package ysyx_26030103.fpu

import chisel3._
import chisel3.util._

// Fudian 组合结果准备与选择：min/max、分类、符号注入、fastResult。
trait ysyx_26030103_FudianFPUResult {
  this: ysyx_26030103_FudianFPUBase with ysyx_26030103_FudianFPUArith =>

  // 比较 / 分类 / 位操作
  private val sMinMax = minMaxBits(aS, bS, sExpWidth, sPrecision, false)
  private val sMaxMax = minMaxBits(aS, bS, sExpWidth, sPrecision, true)
  private val dMinMax =
    if (hasD) Some(minMaxBits(aD, bD, dExpWidth, dPrecision, false)) else None
  private val dMaxMax =
    if (hasD) Some(minMaxBits(aD, bD, dExpWidth, dPrecision, true)) else None

  private val sClass = classifyBits(aS, sExpWidth, sPrecision)
  private val dClass =
    if (hasD) Some(classifyBits(aD, dExpWidth, dPrecision)) else None

  private val sSgnj = boxS(
    Cat(
      Mux(
        op === ysyx_26030103_FPUOp.FSgnj,
        bS(31),
        Mux(
          op === ysyx_26030103_FPUOp.FSgnjn,
          !bS(31),
          aS(31) ^ bS(31)
        )
      ),
      aS(30, 0)
    )
  )
  private val dSgnj: UInt = if (hasD) {
    Cat(
      Mux(
        op === ysyx_26030103_FPUOp.FSgnj,
        bD(63),
        Mux(
          op === ysyx_26030103_FPUOp.FSgnjn,
          !bD(63),
          aD(63) ^ bD(63)
        )
      ),
      aD(62, 0)
    )
  } else {
    0.U(64.W)
  }

  // FMV.X.W / FMV.X.D：S 格式在 XLEN=64 时符号扩展低 32 位。
  private val mvToInt: UInt =
    if (XLEN == 64) {
      Mux(
        fmtD,
        io.request.bits.rs1,
        Cat(Fill(32, io.request.bits.rs1(31)), io.request.bits.rs1(31, 0))
      )
    } else {
      Mux(fmtD, io.request.bits.rs1, io.request.bits.rs1(31, 0))
    }

  private val mvToFPS = boxS(io.request.bits.rs1(31, 0))
  private val mvToFPD = io.request.bits.rs1


  // 快速运算结果（组合），FDIV/FSQRT 走多周期状态机
  protected val fastResult = Wire(UInt(FLEN.W))
  protected val fastFlags = Wire(UInt(ysyx_26030103_FPUFlags.Width.W))
  fastResult := 0.U
  fastFlags := 0.U

  private val cmpResultS = Mux(
    op === ysyx_26030103_FPUOp.FEq,
    sCmp.io.eq,
    Mux(op === ysyx_26030103_FPUOp.FLt, sCmp.io.lt, sCmp.io.le)
  )
  private val cmpResultD: Bool = if (hasD) {
    Mux(
      op === ysyx_26030103_FPUOp.FEq,
      dCmp.get.io.eq,
      Mux(op === ysyx_26030103_FPUOp.FLt, dCmp.get.io.lt, dCmp.get.io.le)
    )
  } else {
    false.B
  }

  private val fmaResultS: UInt =
    if (useFMA) sFma.get.io.result else sExactFma.get.io.result
  private val fmaFlagsS: UInt =
    if (useFMA) sFma.get.io.fflags else sExactFma.get.io.fflags
  private val fmaResultD: UInt =
    if (hasD) {
      if (useFMA) dFma.get.io.result else dExactFma.get.io.result
    } else {
      0.U(64.W)
    }
  private val fmaFlagsD: UInt =
    if (hasD) {
      if (useFMA) dFma.get.io.fflags else dExactFma.get.io.fflags
    } else {
      0.U(ysyx_26030103_FPUFlags.Width.W)
    }

  switch(op) {
    is(ysyx_26030103_FPUOp.FAdd) {
      fastResult := byFmt(boxS(sAdd.io.result), dAdd.get.io.result)
      fastFlags := byFmt(sAdd.io.fflags, dAdd.get.io.fflags)
    }
    is(ysyx_26030103_FPUOp.FSub) {
      fastResult := byFmt(boxS(sAdd.io.result), dAdd.get.io.result)
      fastFlags := byFmt(sAdd.io.fflags, dAdd.get.io.fflags)
    }
    is(ysyx_26030103_FPUOp.FMul) {
      fastResult := byFmt(boxS(sMul.io.result), dMul.get.io.result)
      fastFlags := byFmt(sMul.io.fflags, dMul.get.io.fflags)
    }
    is(ysyx_26030103_FPUOp.FFma, ysyx_26030103_FPUOp.FFms,
      ysyx_26030103_FPUOp.FFnma, ysyx_26030103_FPUOp.FFnms) {
      fastResult := byFmt(boxS(fmaResultS), fmaResultD)
      fastFlags := byFmt(fmaFlagsS, fmaFlagsD)
    }
    is(ysyx_26030103_FPUOp.FSgnj) {
      fastResult := byFmt(sSgnj, dSgnj)
    }
    is(ysyx_26030103_FPUOp.FSgnjn) {
      fastResult := byFmt(sSgnj, dSgnj)
    }
    is(ysyx_26030103_FPUOp.FSgnjx) {
      fastResult := byFmt(sSgnj, dSgnj)
    }
    is(ysyx_26030103_FPUOp.FMin) {
      fastResult := byFmt(boxS(sMinMax._1), dMinMax.get._1)
      fastFlags := byFmt(sMinMax._2, dMinMax.get._2)
    }
    is(ysyx_26030103_FPUOp.FMax) {
      fastResult := byFmt(boxS(sMaxMax._1), dMaxMax.get._1)
      fastFlags := byFmt(sMaxMax._2, dMaxMax.get._2)
    }
    is(ysyx_26030103_FPUOp.FEq) {
      fastResult := Mux(
        Mux(fmtD, cmpResultD, cmpResultS),
        1.U(FLEN.W),
        0.U(FLEN.W)
      )
      fastFlags := byFmt(sCmp.io.fflags, dCmp.get.io.fflags)
    }
    is(ysyx_26030103_FPUOp.FLt) {
      fastResult := Mux(
        Mux(fmtD, cmpResultD, cmpResultS),
        1.U(FLEN.W),
        0.U(FLEN.W)
      )
      fastFlags := byFmt(sCmp.io.fflags, dCmp.get.io.fflags)
    }
    is(ysyx_26030103_FPUOp.FLe) {
      fastResult := Mux(
        Mux(fmtD, cmpResultD, cmpResultS),
        1.U(FLEN.W),
        0.U(FLEN.W)
      )
      fastFlags := byFmt(sCmp.io.fflags, dCmp.get.io.fflags)
    }
    is(ysyx_26030103_FPUOp.FClass) {
      fastResult := asResponse(byFmt(sClass, dClass.get))
    }
    is(ysyx_26030103_FPUOp.FMvFPToInt) {
      fastResult := asResponse(mvToInt)
      fastFlags := 0.U
    }
    is(ysyx_26030103_FPUOp.FMvIntToFP) {
      fastResult := Mux(fmtD, mvToFPD, mvToFPS)
      fastFlags := 0.U
    }
    is(ysyx_26030103_FPUOp.FCvtFPToInt) {
      val raw = asResponse(byFmt(SFPToInt.io.result, DFPToInt.get.io.result))
      // RV64 的 FCVT.W[U]：有符号 W 符号扩展，WU 零扩展；FPToInt 只产低 32 位。
      fastResult := (if (XLEN == 64)
        Mux(io.request.bits.intWord,
          Cat(Fill(32, raw(31)), raw(31, 0)), raw)
      else raw)
      fastFlags := byFmt(SFPToInt.io.fflags, DFPToInt.get.io.fflags)
    }
    is(ysyx_26030103_FPUOp.FCvtFPToUInt) {
      val raw = asResponse(byFmt(SFPToInt.io.result, DFPToInt.get.io.result))
      // RV64 的 FCVT.WU 也按 W 型规则把 32 位结果符号扩展到 XLEN；
      // FPToInt 对 WU 只产生低 32 位（高半清零），必须在这里补齐。
      fastResult := (if (XLEN == 64)
        Mux(io.request.bits.intWord,
          Cat(Fill(32, raw(31)), raw(31, 0)), raw)
      else raw)
      fastFlags := byFmt(SFPToInt.io.fflags, DFPToInt.get.io.fflags)
    }
    is(ysyx_26030103_FPUOp.FCvtIntToFP) {
      fastResult := byFmt(boxS(SIntToFP.io.result), DIntToFP.get.io.result)
      fastFlags := byFmt(SIntToFP.io.fflags, DIntToFP.get.io.fflags)
    }
    is(ysyx_26030103_FPUOp.FCvtUIntToFP) {
      fastResult := byFmt(boxS(SIntToFP.io.result), DIntToFP.get.io.result)
      fastFlags := byFmt(SIntToFP.io.fflags, DIntToFP.get.io.fflags)
    }
    is(ysyx_26030103_FPUOp.FCvtFPToFP) {
      if (hasD) {
        val down = boxS(f64To32.get.io.result)
        val up = f32To64.get.io.result
        fastResult := Mux(fmtD, up, down)
        fastFlags := Mux(fmtD, f32To64.get.io.fflags, f64To32.get.io.fflags)
      }
    }
    // FDIV/FSQRT 由下面的多周期路径产生响应。
    is(ysyx_26030103_FPUOp.FDiv) {}
    is(ysyx_26030103_FPUOp.FSqrt) {}
  }
}
