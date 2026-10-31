package ysyx_26030103.fpu
import chisel3._
import chisel3.util._
import _root_.fudian._
trait ysyx_26030103_FudianFPUArith {
  this: ysyx_26030103_FudianFPUBase =>
  // F扩展的
  protected val sAdd = Module(new FADD(SExpWidth, SPrecision)) // 这是例化单精度加法器
  sAdd.io.a := aS
  sAdd.io.b := Mux(isSub, flipSign(bS, 32), bS) // 减法复用加法器，isSub是1的时候就把b的符号位翻转
  sAdd.io.rm := rm // 舍入
  protected val sMul = Module(new FMUL(SExpWidth, SPrecision))
  sMul.io.a := aS
  sMul.io.b := bS
  sMul.io.rm := rm
  protected val sFma =
    if (UseFMA) Some(Module(new FCMA(SExpWidth, SPrecision))) else None
  if (UseFMA) {
    // 忘记了，后面查了下，isNegXXX是看是正数还是负数，看要不要反转A X B的符号
    sFma.get.io.a := Mux(isNegA, flipSign(aS, 32), aS)
    sFma.get.io.b := bS
    // 看要不要翻转加数C的符号
    sFma.get.io.c := Mux(isNegC, flipSign(cS, 32), cS)
    sFma.get.io.rm := rm
  }
  // 这段纯粹是AI写的，我是真的看不懂了
  // FPU_FMA=n 时改用 ExactFMA：精确乘积 + 定点对齐 + 单次舍入。
  protected val sExactFma =
    if (!UseFMA) Some(Module(new ysyx_26030103_ExactFMA(SExpWidth, SPrecision)))
    else None
  if (!UseFMA) {
    sExactFma.get.io.a := Mux(isNegA, flipSign(aS, 32), aS)
    sExactFma.get.io.b := bS
    sExactFma.get.io.c := Mux(isNegC, flipSign(cS, 32), cS)
    sExactFma.get.io.rm := rm
  }
  protected val sCmp = Module(new FCMP(SExpWidth, SPrecision))
  sCmp.io.a := aS
  sCmp.io.b := bS
  // FEQ 是 quiet 比较（仅 sNaN 置 NV），FLT/FLE 对 qNaN 也置 NV。
  sCmp.io.signaling := op =/= ysyx_26030103_FpuOp.FEq
  protected val sFpToInt = Module(new FPToInt(SExpWidth, SPrecision)) // 单转整的
  sFpToInt.io.a := aS
  sFpToInt.io.rm := rm
  sFpToInt.io.op := cvtToIntOp
  protected val sIntToFp = Module(new IntToFP(SExpWidth, SPrecision)) // 整转单
  sIntToFp.io.int := intSrc64
  sIntToFp.io.sign := intToFpSigned
  sIntToFp.io.long := intToFpLong
  sIntToFp.io.rm := rm
  // D
  protected val dAdd =
    if (HasD) Some(Module(new FADD(DExpWidth, DPrecision))) else None
  protected val dMul =
    if (HasD) Some(Module(new FMUL(DExpWidth, DPrecision))) else None
  protected val dFma =
    if (HasD && UseFMA) Some(Module(new FCMA(DExpWidth, DPrecision))) else None
  protected val dExactFma =
    if (HasD && !UseFMA)
      Some(Module(new ysyx_26030103_ExactFMA(DExpWidth, DPrecision)))
    else None
  protected val dCmp =
    if (HasD) Some(Module(new FCMP(DExpWidth, DPrecision))) else None
  protected val dFpToInt =
    if (HasD) Some(Module(new FPToInt(DExpWidth, DPrecision))) else None
  protected val dIntToFp =
    if (HasD) Some(Module(new IntToFP(DExpWidth, DPrecision))) else None
  if (HasD) {
    dAdd.get.io.a := aD
    dAdd.get.io.b := Mux(isSub, flipSign(bD, 64), bD)
    dAdd.get.io.rm := rm
    dMul.get.io.a := aD
    dMul.get.io.b := bD
    dMul.get.io.rm := rm
    dFma.foreach { fma =>
      fma.io.a := Mux(isNegA, flipSign(aD, 64), aD)
      fma.io.b := bD
      fma.io.c := Mux(isNegC, flipSign(cD, 64), cD)
      fma.io.rm := rm
    }
    dExactFma.foreach { fma =>
      fma.io.a := Mux(isNegA, flipSign(aD, 64), aD)
      fma.io.b := bD
      fma.io.c := Mux(isNegC, flipSign(cD, 64), cD)
      fma.io.rm := rm
    }
    dCmp.get.io.a := aD
    dCmp.get.io.b := bD
    // 下面这段是AI补写的，包括64转32
    // 与 F 格式一致：FLT/FLE 对 qNaN 也要置 NV，FEQ 只在 sNaN 时置 NV。
    dCmp.get.io.signaling := op =/= ysyx_26030103_FpuOp.FEq
    dFpToInt.get.io.a := aD
    dFpToInt.get.io.rm := rm
    dFpToInt.get.io.op := cvtToIntOp
    dIntToFp.get.io.int := intSrc64
    dIntToFp.get.io.sign := intToFpSigned
    dIntToFp.get.io.long := intToFpLong
    dIntToFp.get.io.rm := rm
  }
  // FCVT.S.D / FCVT.D.S
  protected val f64To32 =
    if (HasD)
      Some(Module(new FPToFP(DExpWidth, DPrecision, SExpWidth, SPrecision)))
    else None
  protected val f32To64 =
    if (HasD)
      Some(Module(new FPToFP(SExpWidth, SPrecision, DExpWidth, DPrecision)))
    else None
  if (HasD) {
    f64To32.get.io.in := io.req.bits.rs1
    f64To32.get.io.rm := rm
    f32To64.get.io.in := aS
    f32To64.get.io.rm := rm
  }
}
