package ysyx_26030103.fpu
import chisel3._
import _root_.fudian._
trait ysyx_26030103_FudianFPUArith {
  this: ysyx_26030103_FudianFPUBase =>
  private val fmaNegA = ysyx_26030103_FPUOp.NegateFmaA(op)
  private val fmaNegC = ysyx_26030103_FPUOp.NegateFmaC(op)
  // F扩展的
  protected val sAdd = Module(new FADD(sExpWidth, sPrecision)) // 这是例化单精度加法器
  sAdd.io.a := aS
  sAdd.io.b := Mux(isSub, flipSign(bS, 32), bS) // 减法复用加法器，isSub是1的时候就把b的符号位翻转
  sAdd.io.rm := rm // 舍入
  protected val sMul = Module(new FMUL(sExpWidth, sPrecision))
  sMul.io.a := aS
  sMul.io.b := bS
  sMul.io.rm := rm
  protected val sFma =
    if (useFMA) Some(Module(new FCMA(sExpWidth, sPrecision))) else None
  if (useFMA) {
    sFma.get.io.a := Mux(fmaNegA, flipSign(aS, 32), aS)
    sFma.get.io.b := bS
    sFma.get.io.c := Mux(fmaNegC, flipSign(cS, 32), cS)
    sFma.get.io.rm := rm
  }
  // 这段纯粹是AI写的，我是真的看不懂了
  // FPU_FMA=n 时改用 ExactFMA：精确乘积 + 定点对齐 + 单次舍入。
  protected val sExactFma =
    if (!useFMA) Some(Module(new ysyx_26030103_ExactFMA(sExpWidth, sPrecision)))
    else None
  if (!useFMA) {
    sExactFma.get.io.a := Mux(fmaNegA, flipSign(aS, 32), aS)
    sExactFma.get.io.b := bS
    sExactFma.get.io.c := Mux(fmaNegC, flipSign(cS, 32), cS)
    sExactFma.get.io.rm := rm
  }
  protected val sCmp = Module(new FCMP(sExpWidth, sPrecision))
  sCmp.io.a := aS
  sCmp.io.b := bS
  // FEQ 是 quiet 比较（仅 sNaN 置 NV），FLT/FLE 对 qNaN 也置 NV。
  sCmp.io.signaling := op =/= ysyx_26030103_FPUOp.FEq
  protected val SFPToInt = Module(new FPToInt(sExpWidth, sPrecision)) // 单转整的
  SFPToInt.io.a := aS
  SFPToInt.io.rm := rm
  SFPToInt.io.op := cvtToIntOp
  protected val SIntToFP = Module(new IntToFP(sExpWidth, sPrecision)) // 整转单
  SIntToFP.io.int := intSrc64
  SIntToFP.io.sign := IntToFPSigned
  SIntToFP.io.long := IntToFPLong
  SIntToFP.io.rm := rm
  // D
  protected val dAdd =
    if (hasD) Some(Module(new FADD(dExpWidth, dPrecision))) else None
  protected val dMul =
    if (hasD) Some(Module(new FMUL(dExpWidth, dPrecision))) else None
  protected val dFma =
    if (hasD && useFMA) Some(Module(new FCMA(dExpWidth, dPrecision))) else None
  protected val dExactFma =
    if (hasD && !useFMA)
      Some(Module(new ysyx_26030103_ExactFMA(dExpWidth, dPrecision)))
    else None
  protected val dCmp =
    if (hasD) Some(Module(new FCMP(dExpWidth, dPrecision))) else None
  protected val DFPToInt =
    if (hasD) Some(Module(new FPToInt(dExpWidth, dPrecision))) else None
  protected val DIntToFP =
    if (hasD) Some(Module(new IntToFP(dExpWidth, dPrecision))) else None
  if (hasD) {
    dAdd.get.io.a := aD
    dAdd.get.io.b := Mux(isSub, flipSign(bD, 64), bD)
    dAdd.get.io.rm := rm
    dMul.get.io.a := aD
    dMul.get.io.b := bD
    dMul.get.io.rm := rm
    dFma.foreach { fma =>
      fma.io.a := Mux(fmaNegA, flipSign(aD, 64), aD)
      fma.io.b := bD
      fma.io.c := Mux(fmaNegC, flipSign(cD, 64), cD)
      fma.io.rm := rm
    }
    dExactFma.foreach { fma =>
      fma.io.a := Mux(fmaNegA, flipSign(aD, 64), aD)
      fma.io.b := bD
      fma.io.c := Mux(fmaNegC, flipSign(cD, 64), cD)
      fma.io.rm := rm
    }
    dCmp.get.io.a := aD
    dCmp.get.io.b := bD
    // 下面这段是AI补写的，包括64转32
    // 与 F 格式一致：FLT/FLE 对 qNaN 也要置 NV，FEQ 只在 sNaN 时置 NV。
    dCmp.get.io.signaling := op =/= ysyx_26030103_FPUOp.FEq
    DFPToInt.get.io.a := aD
    DFPToInt.get.io.rm := rm
    DFPToInt.get.io.op := cvtToIntOp
    DIntToFP.get.io.int := intSrc64
    DIntToFP.get.io.sign := IntToFPSigned
    DIntToFP.get.io.long := IntToFPLong
    DIntToFP.get.io.rm := rm
  }
  // FCVT.S.D / FCVT.D.S
  protected val f64To32 =
    if (hasD)
      Some(Module(new FPToFP(dExpWidth, dPrecision, sExpWidth, sPrecision)))
    else None
  protected val f32To64 =
    if (hasD)
      Some(Module(new FPToFP(sExpWidth, sPrecision, dExpWidth, dPrecision)))
    else None
  if (hasD) {
    f64To32.get.io.in := io.request.bits.rs1
    f64To32.get.io.rm := rm
    f32To64.get.io.in := aS
    f32To64.get.io.rm := rm
  }
}
