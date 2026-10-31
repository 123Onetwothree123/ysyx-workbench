package ysyx_26030103.fpu

import chisel3._
import chisel3.util._
import hardfloat._

// HardFloat 的组合运算单元：加、乘、FMA、比较和格式转换。
trait ysyx_26030103_HardFloatFPUArith {
  this: ysyx_26030103_HardFloatFPUBase =>

  // 组合运算单元
  val addS = Module(new AddRecFN(ExpS, SigS))
  addS.io.subOp := op === ysyx_26030103_FpuOp.FSub
  addS.io.a := aRecS
  addS.io.b := bRecS
  addS.io.roundingMode := rm
  addS.io.detectTininess := DetectTininessBool

  val addD = Module(new AddRecFN(ExpD, SigD))
  addD.io.subOp := op === ysyx_26030103_FpuOp.FSub
  addD.io.a := aRecD
  addD.io.b := bRecD
  addD.io.roundingMode := rm
  addD.io.detectTininess := DetectTininessBool

  val mulS = Module(new MulRecFN(ExpS, SigS))
  mulS.io.a := aRecS
  mulS.io.b := bRecS
  mulS.io.roundingMode := rm
  mulS.io.detectTininess := DetectTininessBool

  val mulD = Module(new MulRecFN(ExpD, SigD))
  mulD.io.a := aRecD
  mulD.io.b := bRecD
  mulD.io.roundingMode := rm
  mulD.io.detectTininess := DetectTininessBool

  val fmaS = if (UseFMA) Some(Module(new MulAddRecFN(ExpS, SigS))) else None
  val fmaD = if (UseFMA) Some(Module(new MulAddRecFN(ExpD, SigD))) else None
  private def FmaOp(Operation: UInt): UInt = MuxLookup(Operation, 0.U(2.W))(
    Seq(
      ysyx_26030103_FpuOp.FFma -> 0.U(2.W),
      ysyx_26030103_FpuOp.FFms -> 1.U(2.W),
      // op[1]=negate product, op[0]=subtract c: FNMSUB=2, FNMADD=3。
      ysyx_26030103_FpuOp.FFnms -> 2.U(2.W),
      ysyx_26030103_FpuOp.FFnma -> 3.U(2.W)
    )
  )
  if (UseFMA) {
    fmaS.get.io.op := FmaOp(op)
    fmaS.get.io.a := aRecS
    fmaS.get.io.b := bRecS
    fmaS.get.io.c := cRecS
    fmaS.get.io.roundingMode := rm
    fmaS.get.io.detectTininess := DetectTininessUInt
    fmaD.get.io.op := FmaOp(op)
    fmaD.get.io.a := aRecD
    fmaD.get.io.b := bRecD
    fmaD.get.io.c := cRecD
    fmaD.get.io.roundingMode := rm
    fmaD.get.io.detectTininess := DetectTininessUInt
  }

  // FPU_FMA=n 时改用 ExactFMA：精确乘积 + 定点对齐 + 单次舍入。
  val exactFmaS =
    if (!UseFMA)
      Some(Module(new ysyx_26030103_ExactFMA(
        ExpS, SigS, Params.DetectTininessBeforeRounding)))
    else None
  val exactFmaD =
    if (!UseFMA)
      Some(Module(new ysyx_26030103_ExactFMA(
        ExpD, SigD, Params.DetectTininessBeforeRounding)))
    else None
  private val FlipFmaA = op === ysyx_26030103_FpuOp.FFnms ||
    op === ysyx_26030103_FpuOp.FFnma
  private val FlipFmaC = op === ysyx_26030103_FpuOp.FFms ||
    op === ysyx_26030103_FpuOp.FFnma
  private def FlipSign(Value: UInt, Width: Int): UInt =
    Value ^ Cat(1.U(1.W), 0.U((Width - 1).W))
  if (!UseFMA) {
    exactFmaS.get.io.a := Mux(FlipFmaA, FlipSign(aS, 32), aS)
    exactFmaS.get.io.b := bS
    exactFmaS.get.io.c := Mux(FlipFmaC, FlipSign(cS, 32), cS)
    exactFmaS.get.io.rm := rm
    exactFmaD.get.io.a := Mux(FlipFmaA, FlipSign(aD, 64), aD)
    exactFmaD.get.io.b := bD
    exactFmaD.get.io.c := Mux(FlipFmaC, FlipSign(cD, 64), cD)
    exactFmaD.get.io.rm := rm
  }

  // 比较器同时服务 FCMP 与 FMIN/FMAX；signaling 由操作选择。
  val cmpS = Module(new CompareRecFN(ExpS, SigS))
  cmpS.io.a := aRecS
  cmpS.io.b := bRecS
  cmpS.io.signaling := op =/= ysyx_26030103_FpuOp.FEq
  val cmpD = Module(new CompareRecFN(ExpD, SigD))
  cmpD.io.a := aRecD
  cmpD.io.b := bRecD
  cmpD.io.signaling := op =/= ysyx_26030103_FpuOp.FEq

  // 浮点 -> 整数。signedOut 是运行时输入，因此每个宽度只需要一个实例。
  val toInt32S = Module(new RecFNToIN(ExpS, SigS, 32))
  toInt32S.io.in := aRecS
  toInt32S.io.roundingMode := rm
  toInt32S.io.signedOut := op === ysyx_26030103_FpuOp.FCvtFpToInt
  val toInt64S = Module(new RecFNToIN(ExpS, SigS, 64))
  toInt64S.io.in := aRecS
  toInt64S.io.roundingMode := rm
  toInt64S.io.signedOut := op === ysyx_26030103_FpuOp.FCvtFpToInt
  val toInt32D = Module(new RecFNToIN(ExpD, SigD, 32))
  toInt32D.io.in := aRecD
  toInt32D.io.roundingMode := rm
  toInt32D.io.signedOut := op === ysyx_26030103_FpuOp.FCvtFpToInt
  val toInt64D = Module(new RecFNToIN(ExpD, SigD, 64))
  toInt64D.io.in := aRecD
  toInt64D.io.roundingMode := rm
  toInt64D.io.signedOut := op === ysyx_26030103_FpuOp.FCvtFpToInt

  // RV32 只有 W/WU；这里按 FLEN 取位，避免访问不存在的 aRaw(63,0)。
  private def Int64Input(Value: UInt): UInt =
    if (FLEN == 64) Value(63, 0) else Value(31, 0).pad(64)

  // 整数 -> 浮点。
  val fromInt32S = Module(new INToRecFN(32, ExpS, SigS))
  fromInt32S.io.signedIn := op === ysyx_26030103_FpuOp.FCvtIntToFp
  fromInt32S.io.in := aRaw(31, 0)
  fromInt32S.io.roundingMode := rm
  fromInt32S.io.detectTininess := DetectTininessUInt
  val fromInt64S = Module(new INToRecFN(64, ExpS, SigS))
  fromInt64S.io.signedIn := op === ysyx_26030103_FpuOp.FCvtIntToFp
  fromInt64S.io.in := Int64Input(aRaw)
  fromInt64S.io.roundingMode := rm
  fromInt64S.io.detectTininess := DetectTininessUInt
  val fromInt32D = Module(new INToRecFN(32, ExpD, SigD))
  fromInt32D.io.signedIn := op === ysyx_26030103_FpuOp.FCvtIntToFp
  fromInt32D.io.in := aRaw(31, 0)
  fromInt32D.io.roundingMode := rm
  fromInt32D.io.detectTininess := DetectTininessUInt
  val fromInt64D = Module(new INToRecFN(64, ExpD, SigD))
  fromInt64D.io.signedIn := op === ysyx_26030103_FpuOp.FCvtIntToFp
  fromInt64D.io.in := Int64Input(aRaw)
  fromInt64D.io.roundingMode := rm
  fromInt64D.io.detectTininess := DetectTininessUInt

  // S <-> D 精度转换。
  val cvtStoD = Module(new RecFNToRecFN(ExpS, SigS, ExpD, SigD))
  cvtStoD.io.in := aRecS
  cvtStoD.io.roundingMode := rm
  cvtStoD.io.detectTininess := DetectTininessUInt
  val cvtDtoS = Module(new RecFNToRecFN(ExpD, SigD, ExpS, SigS))
  cvtDtoS.io.in := aRecD
  cvtDtoS.io.roundingMode := rm
  cvtDtoS.io.detectTininess := DetectTininessUInt

}
