package ysyx_26030103.fpu

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.config._
import hardfloat._

/** Berkeley HardFloat 的 NPC 适配层。
  *
  * 该模块是 NPC 唯一允许引用 vendor 的边界：
  *   - 输入/输出始终是 IEEE-754 位模式（FLEN=32/64）；
  *   - 内部只在组合运算前后做 IEEE <-> RecFN 转换；
  *   - RISC-V 的 frm 编码与 HardFloat 的 roundingMode 编码一一对应；
  *   - tininess 由 FPUProfile.HardFloat 选择，默认 after-rounding；
  *   - HardFloat 的 5 位 exceptionFlags 位序与 RISC-V fflags 相同。
  *
  * 加/乘/FMA/比较/转换使用 vendor 的组合模块并在本层寄存结果；
  * `FPU_FMA=y` 时 FMA 使用 `MulAddRecFN`，`=n` 时改用基于 vendor
  * 基础模块的单次舍入非融合 ExactFMA；DIV/SQRT 使用
  * DivSqrtRecFN_small 的多周期握手。请求/响应均遵守 Decoupled 反压，
  * `FPU_PIPELINE=N` 在响应边界增加 N 级 valid/ready 寄存器，CPU 不
  * 依赖固定 latency。
  */
class ysyx_26030103_HardFloatFPU(override val Config: ysyx_26030103_NPCConfig)
    extends ysyx_26030103_FPU(Config) {
  private val FLEN = Config.FPULen
  private val HasD = Config.UseD && FLEN == 64
  private val Params = Config.FPUProfile.HardFloat
  private val UseFMA = Config.FPUProfile.FMA
  require(Params.Recode,
    "Berkeley HardFloat 只能使用 recoded 内部格式；FPU_HARDFLOAT_RECODE=n 不受支持")

  private val ExpS = 8
  private val SigS = 24
  private val ExpD = 11
  private val SigD = 53
  private val CanonicalNaN32 = "h7fc00000".U(32.W)
  private val CanonicalNaN64 = "h7ff8000000000000".U(64.W)
  // HardFloat 的 detectTininess：0=before rounding，1=after rounding。
  private val DetectTininessUInt: UInt =
    (if (Params.DetectTininessBeforeRounding) 0.U(1.W) else 1.U(1.W))
  private val DetectTininessBool: Bool =
    (if (Params.DetectTininessBeforeRounding) false.B else true.B)

  // ---------------------------------------------------------------- 输入边界
  // FLEN=64 时，单精度寄存器必须 NaN-box；未 box 的值按 RISC-V 规定当作
  // canonical NaN，而不是静默使用低 32 位。
  private def IsBoxedS(Value: UInt): Bool =
    if (FLEN == 64) Value(63, 32) === "hffffffff".U(32.W) else true.B
  private def SInput(Value: UInt): UInt =
    if (FLEN == 64) Mux(IsBoxedS(Value), Value(31, 0), CanonicalNaN32)
    else Value(31, 0)
  private def DInput(Value: UInt): UInt =
    if (FLEN == 64) Value else 0.U(64.W)

  private def BoxF32(Value: UInt): UInt =
    if (FLEN == 64) Cat(Fill(FLEN - 32, 1.U(1.W)), Value(31, 0))
    else Value(31, 0)
  private def BoxD(Value: UInt): UInt =
    if (FLEN == 64) Value(63, 0) else 0.U(FLEN.W)
  private def Sext32ToFLEN(Value: UInt): UInt =
    if (FLEN == 64) Cat(Fill(FLEN - 32, Value(31)), Value(31, 0))
    else Value(31, 0)

  val req = io.req.bits
  val aRaw = req.rs1
  val bRaw = req.rs2
  val cRaw = req.rs3
  val aS = SInput(aRaw)
  val bS = SInput(bRaw)
  val cS = SInput(cRaw)
  val aD = DInput(aRaw)
  val bD = DInput(bRaw)
  val cD = DInput(cRaw)
  val fmtS = req.fmt === ysyx_26030103_FpuFmt.S
  val fmtD = req.fmt === ysyx_26030103_FpuFmt.D
  val srcFmtD = req.srcFmt === ysyx_26030103_FpuFmt.D
  val intWord = req.intWord
  val rm = req.rm
  val op = req.op

  private val aRecS = recFNFromFN(ExpS, SigS, aS)
  private val bRecS = recFNFromFN(ExpS, SigS, bS)
  private val cRecS = recFNFromFN(ExpS, SigS, cS)
  private val aRecD = recFNFromFN(ExpD, SigD, aD)
  private val bRecD = recFNFromFN(ExpD, SigD, bD)
  private val cRecD = recFNFromFN(ExpD, SigD, cD)

  // ------------------------------------------------------------ 组合运算单元
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
      // op[1]=negate product, op[0]=subtract c:
      //   FNMSUB = -(a*b)+c -> 2, FNMADD = -(a*b)-c -> 3
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

  // FPU_FMA=n：不使用 vendor 融合乘加，改用 ExactFMA 的“精确乘积 +
  // 定点对齐加法 + 单次舍入”数据通路。两种结构在生成 RTL 中模块名不同。
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

  // RV32 只有 W/WU 形式，L/LU 在译码阶段即判非法；这里仍按 FLEN
  // 安全取位，避免 elaboration 访问不存在的 aRaw(63,0)。
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

  // ------------------------------------------------------------ 辅助位操作
  private def IsNaN32(Value: UInt): Bool =
    Value(30, 23) === "hff".U(8.W) && Value(22, 0) =/= 0.U
  private def IsSigNaN32(Value: UInt): Bool =
    IsNaN32(Value) && !Value(22)
  private def IsNaN64(Value: UInt): Bool =
    Value(62, 52) === "h7ff".U(11.W) && Value(51, 0) =/= 0.U
  private def IsSigNaN64(Value: UInt): Bool =
    IsNaN64(Value) && !Value(51)
  private def IsZero32(Value: UInt): Bool = Value(30, 0) === 0.U
  private def IsZero64(Value: UInt): Bool = Value(62, 0) === 0.U

  /** 负数转无符号整数的 invalid 判定。
    *
    * Berkeley RecFNToIN 对 |x|<2、呈负号的输入只产生 NX，不会把“舍入后
    * 仍为负”的 -1.0 等值判成 NV。RISC-V 要求舍入结果不在 [0, 2^N-1]
    * 时裁剪到 0 并置 NV。这里按舍入模式判断负数是否会舍入到非零负值：
    *   RDN：任意非零负数都会向 -inf 舍入到 <= -1；
    *   RMM：|x|>=0.5 会远离零舍入到 -1；
    *   RNE：|x|>0.5 才舍入到 -1（恰好 -0.5 舍入到 0）；
    *   RTZ/RUP：|x|>=1 才得到非零负值。
    */
  private def UnsignedNegInvalid(
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
    // NaN/Inf 由 RecFNToIN 自己产生正确的裁剪值（NaN 为全 1、Inf 为 0）
    // 和 NV，这里只修正有限负数的 NV 遗漏。
    sign && nonzero && !exp.andR && MuxLookup(rm, geOne)(
      Seq(
        ysyx_26030103_FpuRm.RDN -> true.B,
        ysyx_26030103_FpuRm.RMM -> geHalf,
        ysyx_26030103_FpuRm.RNE -> aboveHalf
      )
    )
  }
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

  private def IntFlags(Flags: UInt): UInt =
    // RecFNToIN: {invalid, overflow, inexact} -> RISC-V {NV, -, -, -, NX}
    Cat(Flags(2) || Flags(1), 0.U(3.W), Flags(0))

  // ------------------------------------------------------------ 组合结果选择
  // 两个实例都常量化选择方向：min 用 true，max 用 false，再由 FpuOp 选结果。
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

  // 先把所有组合运算的结果汇成一个 (result, flags, isFPResult) 三元组。
  // isFPResult 只用于选择 IEEE 结果还是整数结果；接口本身统一 64 位。
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
      // 非法负数转无符号只保留 NV；HardFloat 可能额外报 NX，但
      // RISC-V/NEMU 在裁剪到 0 时只要求 invalid。
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

  // ------------------------------------------------------------ 多周期 DIV/SQRT
  val divS = Module(new DivSqrtRecFN_small(ExpS, SigS, 0))
  val divD = Module(new DivSqrtRecFN_small(ExpD, SigD, 0))
  private def SqrtOp: Bool = op === ysyx_26030103_FpuOp.FSqrt
  divS.io.inValid := false.B
  divS.io.sqrtOp := SqrtOp
  divS.io.a := aRecS
  divS.io.b := bRecS
  divS.io.roundingMode := rm
  divS.io.detectTininess := DetectTininessUInt
  divD.io.inValid := false.B
  divD.io.sqrtOp := SqrtOp
  divD.io.a := aRecD
  divD.io.b := bRecD
  divD.io.roundingMode := rm
  divD.io.detectTininess := DetectTininessUInt

  val respValid = RegInit(false.B)
  val respResult = RegInit(0.U(FLEN.W))
  val respFlags = RegInit(0.U(5.W))
  val divActive = RegInit(false.B)
  val divAbandoned = RegInit(false.B)
  val divStartedS = RegInit(false.B)

  // 公开的 FPU_PIPELINE：0=原有组合/单拍路径，N>0 在响应边界插入 N 级
  // valid/ready 寄存器。反压会沿 ready 链传回 respValid；flush 清空。
  val respPipe =
    Module(new ysyx_26030103_FpuResponsePipeline(FLEN, Config.FPUProfile.Pipeline))
  respPipe.io.flush := io.flush
  respPipe.io.in.valid := respValid
  respPipe.io.in.bits.result := respResult
  respPipe.io.in.bits.fflags := respFlags
  io.resp <> respPipe.io.out
  val respPipeReady = respPipe.io.in.ready

  val selectedDivReady = Mux(fmtS, divS.io.inReady, divD.io.inReady)
  // io.req.ready 不能组合依赖 io.flush：EXU 的 FpuFlush 又依赖
  // CSRUnit.ExceptionTaken，而后者经过 Enable 回到 FpuReq.ready，会形成
  // 组合环。保持 ready 只看内部空闲；flush 同拍不启动 DIV/SQRT，
  // divActive 不会被置位，EXU 侧的 FpPending 由 FpuFlush 当拍清掉。
  io.req.ready := !respValid && !divActive &&
    Mux(isDivSqrt, selectedDivReady, true.B)
  val divRequestFire = io.req.valid && io.req.ready && isDivSqrt && !io.flush
  divS.io.inValid := divRequestFire && fmtS && !io.flush
  divD.io.inValid := divRequestFire && fmtD && !io.flush

  when(divRequestFire) {
    divActive := true.B
    divStartedS := fmtS
    divAbandoned := false.B
  }

  val divOutValid = Mux(divStartedS,
    divS.io.outValid_div || divS.io.outValid_sqrt,
    divD.io.outValid_div || divD.io.outValid_sqrt)
  val divOutResult = Mux(divStartedS,
    BoxF32(fNFromRecFN(ExpS, SigS, divS.io.out)),
    BoxD(fNFromRecFN(ExpD, SigD, divD.io.out)))
  val divOutFlags = Mux(divStartedS, divS.io.exceptionFlags,
    divD.io.exceptionFlags)
  when(divActive && divOutValid) {
    divActive := false.B
    divAbandoned := false.B
    when(!divAbandoned) {
      respValid := true.B
      respResult := divOutResult
      respFlags := divOutFlags
    }
  }

  // 非 DIV/SQRT 在接收请求的下一拍给出寄存结果。
  when(io.req.valid && io.req.ready && !isDivSqrt && !io.flush) {
    respValid := true.B
    respResult := fastResult
    respFlags := fastFlags
  }
  when(respValid && respPipeReady) {
    respValid := false.B
  }
  when(io.flush) {
    respValid := false.B
    when(divActive) { divAbandoned := true.B }
  }

  // io.resp 已由响应流水级驱动，不再直接暴露 respValid。

  // 未使用的 compare 结果显式消费，避免 lint 误报。
  dontTouch(cmpS.io.gt)
  dontTouch(cmpD.io.gt)
}
