package ysyx_26030103.fpu

import chisel3._
import chisel3.util._

// Fudian 组合结果准备与选择：min/max、分类、符号注入、fastResult。
trait ysyx_26030103_FudianFPUResult {
  this: ysyx_26030103_FudianFPUBase with ysyx_26030103_FudianFPUArith =>

  // 比较 / 分类 / 位操作
  private val sMinMax = minMaxBits(aS, bS, SExpWidth, SPrecision, false)
  private val sMaxMax = minMaxBits(aS, bS, SExpWidth, SPrecision, true)
  private val dMinMax =
    if (HasD) Some(minMaxBits(aD, bD, DExpWidth, DPrecision, false)) else None
  private val dMaxMax =
    if (HasD) Some(minMaxBits(aD, bD, DExpWidth, DPrecision, true)) else None

  private val sClass = classifyBits(aS, SExpWidth, SPrecision)
  private val dClass =
    if (HasD) Some(classifyBits(aD, DExpWidth, DPrecision)) else None

  private val sSgnj = boxS(
    Cat(
      Mux(
        op === ysyx_26030103_FpuOp.FSgnj,
        bS(31),
        Mux(
          op === ysyx_26030103_FpuOp.FSgnjn,
          !bS(31),
          aS(31) ^ bS(31)
        )
      ),
      aS(30, 0)
    )
  )
  private val dSgnj: UInt = if (HasD) {
    Cat(
      Mux(
        op === ysyx_26030103_FpuOp.FSgnj,
        bD(63),
        Mux(
          op === ysyx_26030103_FpuOp.FSgnjn,
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
        io.req.bits.rs1,
        Cat(Fill(32, io.req.bits.rs1(31)), io.req.bits.rs1(31, 0))
      )
    } else {
      Mux(fmtD, io.req.bits.rs1, io.req.bits.rs1(31, 0))
    }

  private val mvToFpS = boxS(io.req.bits.rs1(31, 0))
  private val mvToFpD = io.req.bits.rs1


  // 快速运算结果（组合），FDIV/FSQRT 走多周期状态机
  protected val fastResult = Wire(UInt(FLEN.W))
  protected val fastFlags = Wire(UInt(5.W))
  fastResult := 0.U
  fastFlags := 0.U

  private val cmpResultS = Mux(
    op === ysyx_26030103_FpuOp.FEq,
    sCmp.io.eq,
    Mux(op === ysyx_26030103_FpuOp.FLt, sCmp.io.lt, sCmp.io.le)
  )
  private val cmpResultD: Bool = if (HasD) {
    Mux(
      op === ysyx_26030103_FpuOp.FEq,
      dCmp.get.io.eq,
      Mux(op === ysyx_26030103_FpuOp.FLt, dCmp.get.io.lt, dCmp.get.io.le)
    )
  } else {
    false.B
  }

  private val fmaResultS: UInt =
    if (UseFMA) sFma.get.io.result else sExactFma.get.io.result
  private val fmaFlagsS: UInt =
    if (UseFMA) sFma.get.io.fflags else sExactFma.get.io.fflags
  private val fmaResultD: UInt =
    if (HasD) {
      if (UseFMA) dFma.get.io.result else dExactFma.get.io.result
    } else {
      0.U(64.W)
    }
  private val fmaFlagsD: UInt =
    if (HasD) {
      if (UseFMA) dFma.get.io.fflags else dExactFma.get.io.fflags
    } else {
      0.U(5.W)
    }

  switch(op) {
    is(ysyx_26030103_FpuOp.FAdd) {
      fastResult := byFmt(boxS(sAdd.io.result), dAdd.get.io.result)
      fastFlags := byFmt(sAdd.io.fflags, dAdd.get.io.fflags)
    }
    is(ysyx_26030103_FpuOp.FSub) {
      fastResult := byFmt(boxS(sAdd.io.result), dAdd.get.io.result)
      fastFlags := byFmt(sAdd.io.fflags, dAdd.get.io.fflags)
    }
    is(ysyx_26030103_FpuOp.FMul) {
      fastResult := byFmt(boxS(sMul.io.result), dMul.get.io.result)
      fastFlags := byFmt(sMul.io.fflags, dMul.get.io.fflags)
    }
    is(ysyx_26030103_FpuOp.FFma, ysyx_26030103_FpuOp.FFms,
      ysyx_26030103_FpuOp.FFnma, ysyx_26030103_FpuOp.FFnms) {
      fastResult := byFmt(boxS(fmaResultS), fmaResultD)
      fastFlags := byFmt(fmaFlagsS, fmaFlagsD)
    }
    is(ysyx_26030103_FpuOp.FSgnj) {
      fastResult := byFmt(sSgnj, dSgnj)
    }
    is(ysyx_26030103_FpuOp.FSgnjn) {
      fastResult := byFmt(sSgnj, dSgnj)
    }
    is(ysyx_26030103_FpuOp.FSgnjx) {
      fastResult := byFmt(sSgnj, dSgnj)
    }
    is(ysyx_26030103_FpuOp.FMin) {
      fastResult := byFmt(boxS(sMinMax._1), dMinMax.get._1)
      fastFlags := byFmt(sMinMax._2, dMinMax.get._2)
    }
    is(ysyx_26030103_FpuOp.FMax) {
      fastResult := byFmt(boxS(sMaxMax._1), dMaxMax.get._1)
      fastFlags := byFmt(sMaxMax._2, dMaxMax.get._2)
    }
    is(ysyx_26030103_FpuOp.FEq) {
      fastResult := Mux(
        Mux(fmtD, cmpResultD, cmpResultS),
        1.U(FLEN.W),
        0.U(FLEN.W)
      )
      fastFlags := byFmt(sCmp.io.fflags, dCmp.get.io.fflags)
    }
    is(ysyx_26030103_FpuOp.FLt) {
      fastResult := Mux(
        Mux(fmtD, cmpResultD, cmpResultS),
        1.U(FLEN.W),
        0.U(FLEN.W)
      )
      fastFlags := byFmt(sCmp.io.fflags, dCmp.get.io.fflags)
    }
    is(ysyx_26030103_FpuOp.FLe) {
      fastResult := Mux(
        Mux(fmtD, cmpResultD, cmpResultS),
        1.U(FLEN.W),
        0.U(FLEN.W)
      )
      fastFlags := byFmt(sCmp.io.fflags, dCmp.get.io.fflags)
    }
    is(ysyx_26030103_FpuOp.FClass) {
      fastResult := asResp(byFmt(sClass, dClass.get))
    }
    is(ysyx_26030103_FpuOp.FMvFpToInt) {
      fastResult := asResp(mvToInt)
      fastFlags := 0.U
    }
    is(ysyx_26030103_FpuOp.FMvIntToFp) {
      fastResult := Mux(fmtD, mvToFpD, mvToFpS)
      fastFlags := 0.U
    }
    is(ysyx_26030103_FpuOp.FCvtFpToInt) {
      val raw = asResp(byFmt(sFpToInt.io.result, dFpToInt.get.io.result))
      // RV64 的 FCVT.W[U]：有符号 W 符号扩展，WU 零扩展；FPToInt 只产低 32 位。
      fastResult := (if (XLEN == 64)
        Mux(io.req.bits.intWord,
          Cat(Fill(32, raw(31)), raw(31, 0)), raw)
      else raw)
      fastFlags := byFmt(sFpToInt.io.fflags, dFpToInt.get.io.fflags)
    }
    is(ysyx_26030103_FpuOp.FCvtFpToUInt) {
      val raw = asResp(byFmt(sFpToInt.io.result, dFpToInt.get.io.result))
      // RV64 的 FCVT.WU 也按 W 型规则把 32 位结果符号扩展到 XLEN；
      // FPToInt 对 WU 只产生低 32 位（高半清零），必须在这里补齐。
      fastResult := (if (XLEN == 64)
        Mux(io.req.bits.intWord,
          Cat(Fill(32, raw(31)), raw(31, 0)), raw)
      else raw)
      fastFlags := byFmt(sFpToInt.io.fflags, dFpToInt.get.io.fflags)
    }
    is(ysyx_26030103_FpuOp.FCvtIntToFp) {
      fastResult := byFmt(boxS(sIntToFp.io.result), dIntToFp.get.io.result)
      fastFlags := byFmt(sIntToFp.io.fflags, dIntToFp.get.io.fflags)
    }
    is(ysyx_26030103_FpuOp.FCvtUIntToFp) {
      fastResult := byFmt(boxS(sIntToFp.io.result), dIntToFp.get.io.result)
      fastFlags := byFmt(sIntToFp.io.fflags, dIntToFp.get.io.fflags)
    }
    is(ysyx_26030103_FpuOp.FCvtFpToFp) {
      if (HasD) {
        val down = boxS(f64To32.get.io.result)
        val up = f32To64.get.io.result
        fastResult := Mux(fmtD, up, down)
        fastFlags := Mux(fmtD, f32To64.get.io.fflags, f64To32.get.io.fflags)
      }
    }
    // FDIV/FSQRT 由下面的多周期路径产生响应。
    is(ysyx_26030103_FpuOp.FDiv) {}
    is(ysyx_26030103_FpuOp.FSqrt) {}
  }
}
