package ysyx_26030103.fpu

import chisel3._
import chisel3.util._
import hardfloat._

// HardFloat 的组合运算单元：加、乘、FMA、比较和格式转换。
trait ysyx_26030103_HardFloatFPUArith {
  this: ysyx_26030103_HardFloatFPUBase =>

  // 组合运算单元
  val addS = Module(new AddRecFN(expS, sigS))
  addS.io.subOp := op === ysyx_26030103_FPUOp.FSub
  addS.io.a := aRecS
  addS.io.b := bRecS
  addS.io.roundingMode := rm
  addS.io.detectTininess := detectTininessBool

  val addD = Module(new AddRecFN(expD, sigD))
  addD.io.subOp := op === ysyx_26030103_FPUOp.FSub
  addD.io.a := aRecD
  addD.io.b := bRecD
  addD.io.roundingMode := rm
  addD.io.detectTininess := detectTininessBool

  val mulS = Module(new MulRecFN(expS, sigS))
  mulS.io.a := aRecS
  mulS.io.b := bRecS
  mulS.io.roundingMode := rm
  mulS.io.detectTininess := detectTininessBool

  val mulD = Module(new MulRecFN(expD, sigD))
  mulD.io.a := aRecD
  mulD.io.b := bRecD
  mulD.io.roundingMode := rm
  mulD.io.detectTininess := detectTininessBool

  val fmaS = if (useFMA) Some(Module(new MulAddRecFN(expS, sigS))) else None
  val fmaD = if (useFMA) Some(Module(new MulAddRecFN(expD, sigD))) else None
  // op[1]=negate product, op[0]=subtract c: FNMSUB=2, FNMADD=3
  private val fmaOpValue = ysyx_26030103_FPUOp.FmaOp2(op)
  if (useFMA) {
    fmaS.get.io.op := fmaOpValue
    fmaS.get.io.a := aRecS
    fmaS.get.io.b := bRecS
    fmaS.get.io.c := cRecS
    fmaS.get.io.roundingMode := rm
    fmaS.get.io.detectTininess := detectTininessUInt
    fmaD.get.io.op := fmaOpValue
    fmaD.get.io.a := aRecD
    fmaD.get.io.b := bRecD
    fmaD.get.io.c := cRecD
    fmaD.get.io.roundingMode := rm
    fmaD.get.io.detectTininess := detectTininessUInt
  }

  // FPU_FMA=n 时改用 ExactFMA：精确乘积 + 定点对齐 + 单次舍入。
  val exactFmaS =
    if (!useFMA)
      Some(Module(new ysyx_26030103_ExactFMA(
        expS, sigS, params.DetectTininessBeforeRounding)))
    else None
  val exactFmaD =
    if (!useFMA)
      Some(Module(new ysyx_26030103_ExactFMA(
        expD, sigD, params.DetectTininessBeforeRounding)))
    else None
  private val fmaNegA = ysyx_26030103_FPUOp.NegateFmaA(op)
  private val fmaNegC = ysyx_26030103_FPUOp.NegateFmaC(op)
  if (!useFMA) {
    exactFmaS.get.io.a := Mux(fmaNegA, ysyx_26030103_FPUFormat.FlipSign(aS, 32), aS)
    exactFmaS.get.io.b := bS
    exactFmaS.get.io.c := Mux(fmaNegC, ysyx_26030103_FPUFormat.FlipSign(cS, 32), cS)
    exactFmaS.get.io.rm := rm
    exactFmaD.get.io.a := Mux(fmaNegA, ysyx_26030103_FPUFormat.FlipSign(aD, 64), aD)
    exactFmaD.get.io.b := bD
    exactFmaD.get.io.c := Mux(fmaNegC, ysyx_26030103_FPUFormat.FlipSign(cD, 64), cD)
    exactFmaD.get.io.rm := rm
  }

  // 比较器同时服务 FCMP 与 FMIN/FMAX；signaling 由操作选择。
  val cmpS = Module(new CompareRecFN(expS, sigS))
  cmpS.io.a := aRecS
  cmpS.io.b := bRecS
  cmpS.io.signaling := op =/= ysyx_26030103_FPUOp.FEq
  val cmpD = Module(new CompareRecFN(expD, sigD))
  cmpD.io.a := aRecD
  cmpD.io.b := bRecD
  cmpD.io.signaling := op =/= ysyx_26030103_FPUOp.FEq

  // 浮点 -> 整数。signedOut 是运行时输入，因此每个宽度只需要一个实例。
  val toInt32S = Module(new RecFNToIN(expS, sigS, 32))
  toInt32S.io.in := aRecS
  toInt32S.io.roundingMode := rm
  toInt32S.io.signedOut := op === ysyx_26030103_FPUOp.FCvtFPToInt
  val toInt64S = Module(new RecFNToIN(expS, sigS, 64))
  toInt64S.io.in := aRecS
  toInt64S.io.roundingMode := rm
  toInt64S.io.signedOut := op === ysyx_26030103_FPUOp.FCvtFPToInt
  val toInt32D = Module(new RecFNToIN(expD, sigD, 32))
  toInt32D.io.in := aRecD
  toInt32D.io.roundingMode := rm
  toInt32D.io.signedOut := op === ysyx_26030103_FPUOp.FCvtFPToInt
  val toInt64D = Module(new RecFNToIN(expD, sigD, 64))
  toInt64D.io.in := aRecD
  toInt64D.io.roundingMode := rm
  toInt64D.io.signedOut := op === ysyx_26030103_FPUOp.FCvtFPToInt

  // RV32 只有 W/WU；这里按 FLEN 取位，避免访问不存在的 aRaw(63,0)。
  private def int64Input(value: UInt): UInt =
    if (FLEN == 64) value(63, 0) else value(31, 0).pad(64)

  // 整数 -> 浮点。
  val fromInt32S = Module(new INToRecFN(32, expS, sigS))
  fromInt32S.io.signedIn := op === ysyx_26030103_FPUOp.FCvtIntToFP
  fromInt32S.io.in := aRaw(31, 0)
  fromInt32S.io.roundingMode := rm
  fromInt32S.io.detectTininess := detectTininessUInt
  val fromInt64S = Module(new INToRecFN(64, expS, sigS))
  fromInt64S.io.signedIn := op === ysyx_26030103_FPUOp.FCvtIntToFP
  fromInt64S.io.in := int64Input(aRaw)
  fromInt64S.io.roundingMode := rm
  fromInt64S.io.detectTininess := detectTininessUInt
  val fromInt32D = Module(new INToRecFN(32, expD, sigD))
  fromInt32D.io.signedIn := op === ysyx_26030103_FPUOp.FCvtIntToFP
  fromInt32D.io.in := aRaw(31, 0)
  fromInt32D.io.roundingMode := rm
  fromInt32D.io.detectTininess := detectTininessUInt
  val fromInt64D = Module(new INToRecFN(64, expD, sigD))
  fromInt64D.io.signedIn := op === ysyx_26030103_FPUOp.FCvtIntToFP
  fromInt64D.io.in := int64Input(aRaw)
  fromInt64D.io.roundingMode := rm
  fromInt64D.io.detectTininess := detectTininessUInt

  // S <-> D 精度转换。
  val cvtStoD = Module(new RecFNToRecFN(expS, sigS, expD, sigD))
  cvtStoD.io.in := aRecS
  cvtStoD.io.roundingMode := rm
  cvtStoD.io.detectTininess := detectTininessUInt
  val cvtDtoS = Module(new RecFNToRecFN(expD, sigD, expS, sigS))
  cvtDtoS.io.in := aRecD
  cvtDtoS.io.roundingMode := rm
  cvtDtoS.io.detectTininess := detectTininessUInt

}
