package ysyx_26030103.fpu

import chisel3._
import chisel3.util._
import _root_.fudian.RoundingUnit
import _root_.fudian.utils.{CLZ, Multiplier, ShiftRightJam}

/** 非融合但单次舍入的 FMA 数据通路。
  *
  * `FPU_FMA=n` 时不能用 Fudian/FCMA 或 HardFloat/MulAddRecFN 这类融合
  * 乘加单元冒充“关闭”，因此这里只复用 vendor 的基础模块作为硬件来源：
  *   - `fudian.utils.Multiplier`：精确的 P×P 位无符号尾数乘积；
  *   - `fudian.utils.ShiftRightJam`：对齐小操作数并收集 sticky；
  *   - `fudian.RoundingUnit`：最终一次舍入；
  *   - `fudian.utils.CLZ`：规格化/前导零计数。
  *
  * 数据通路的加法部分由本 wrapper 实现：把精确乘积和第三个操作数对齐到
  * 同一个定点窗口，低位只保留 sticky，再统一规格化并做一次舍入。这样
  * FMADD/FMSUB/FNMADD/FNMSUB 的舍入与异常标志仍是 RISC-V 单次舍入
  * 语义，但结构上不是 vendor 的融合 FMA。
  *
  * 输入是当前格式的 IEEE-754 位模式，输出同样是 IEEE-754 位模式；
  * 四类 FMA 的符号组合由调用方翻转 a/c 的符号位后统一按 a*b+c 计算。
  */
class ysyx_26030103_ExactFMA(
    val expWidth: Int,
    val precision: Int,
    val tininessBeforeRounding: Boolean = false
) extends Module {
  require(expWidth >= 2 && precision >= 2,
    s"ExactFMA 参数非法: expWidth=$expWidth precision=$precision")

  private val P = precision
  private val Bias = (1 << (expWidth - 1)) - 1
  private val ExpW = expWidth + 3
  private val W2 = 2 * P
  // 定点窗口在精确乘积之外保留 4 个保护位，再留 1 位进位。
  private val G = 4
  private val A = W2 + G + 1
  private val Total = expWidth + P

  val io = IO(new Bundle {
    val a = Input(UInt(Total.W))
    val b = Input(UInt(Total.W))
    val c = Input(UInt(Total.W))
    val rm = Input(UInt(3.W))
    val result = Output(UInt(Total.W))
    val fflags = Output(UInt(5.W))
  })

  private def Sign(x: UInt): Bool = x(Total - 1)
  private def ExpF(x: UInt): UInt = x(Total - 2, P - 1)
  private def Frac(x: UInt): UInt = x(P - 2, 0)
  private def ExpOnes(x: UInt): Bool = ExpF(x).andR
  private def IsNaN(x: UInt): Bool = ExpOnes(x) && Frac(x).orR
  private def IsSNaN(x: UInt): Bool = IsNaN(x) && !Frac(x)(P - 2)
  private def IsInf(x: UInt): Bool = ExpOnes(x) && !Frac(x).orR
  private def IsZero(x: UInt): Bool = !ExpF(x).orR && !Frac(x).orR
  private def IsSub(x: UInt): Bool = !ExpF(x).orR && Frac(x).orR

  /** 把有限数规格化为 P 位尾数（隐藏位在最高位）和最高位权指数。 */
  private def Normalize(x: UInt): (UInt, SInt) = {
    val zero = IsZero(x)
    val sub = IsSub(x)
    val raw = Cat(!sub && !zero, Frac(x))
    val lz = CLZ(raw)
    val shifted = (raw << lz)(P - 1, 0)
    val sig = Mux(sub, shifted, raw)

    val expSub = Wire(SInt(ExpW.W))
    expSub := (1 - Bias).S(ExpW.W) - lz.zext.asSInt
    val expNorm = Wire(SInt(ExpW.W))
    expNorm := ExpF(x).zext.asSInt - Bias.S(ExpW.W)
    val expZero = 0.S(ExpW.W)
    val exp = Mux(zero, expZero, Mux(sub, expSub, expNorm))
    (sig, exp)
  }

  private val aNaN = IsNaN(io.a)
  private val bNaN = IsNaN(io.b)
  private val cNaN = IsNaN(io.c)
  private val anyNaN = aNaN || bNaN || cNaN
  private val anySNaN = IsSNaN(io.a) || IsSNaN(io.b) || IsSNaN(io.c)

  private val aZero = IsZero(io.a)
  private val bZero = IsZero(io.b)
  private val cZero = IsZero(io.c)
  private val aInf = IsInf(io.a)
  private val bInf = IsInf(io.b)
  private val cInf = IsInf(io.c)

  private val prodSign = Sign(io.a) ^ Sign(io.b)
  private val cSign = Sign(io.c)

  // 0×Inf 与 Inf-Inf 产生 invalid；其余 Inf 直接传播。
  private val invalidMul = (aInf && bZero) || (aZero && bInf)
  private val prodInf = (aInf && !bZero) || (bInf && !aZero)
  private val prodZero = !invalidMul && !prodInf && (aZero || bZero)
  private val infMinusInf = prodInf && cInf && (prodSign =/= cSign)
  private val invalid = invalidMul || infMinusInf
  private val nanResult = anyNaN || invalid
  private val nv = anySNaN || invalid

  private val (aSig, aExp) = Normalize(io.a)
  private val (bSig, bExp) = Normalize(io.b)
  private val (cSig, cExp) = Normalize(io.c)

  // 精确乘积：两个 P 位尾数的乘积最多 2P 位，这里不进行任何舍入。
  // vendor Multiplier 的 len 包含符号位语义，FMUL 也使用 P+1 并在高位补 0；
  // 直接用 len=P 会把最高位为 1 的正常尾数当成负数。
  private val multiplier = Module(new Multiplier(P + 1, Seq()))
  multiplier.io.a := aSig.pad(P + 1)
  multiplier.io.b := bSig.pad(P + 1)
  private val prodRaw = multiplier.io.result(W2 - 1, 0)
  private val prodMsb = prodRaw(W2 - 1)
  private val prodSig = Mux(prodMsb, prodRaw, (prodRaw << 1)(W2 - 1, 0))
  private val prodExp = Wire(SInt(ExpW.W))
  prodExp := aExp + bExp + prodMsb.asUInt.zext.asSInt

  // 把 c 的 P 位尾数左对齐到 2P 位，使两个操作数共享同一个 MSB 位置。
  private val cSigAligned = (cSig << P)(W2 - 1, 0)

  private val swap = !cZero && (
    (prodExp < cExp) ||
      (prodExp === cExp && prodSig < cSigAligned)
  )
  private val lExp = Mux(cZero, prodExp, Mux(swap, cExp, prodExp))
  private val sExp = Mux(cZero, prodExp, Mux(swap, prodExp, cExp))
  private val lSig = Mux(cZero, prodSig, Mux(swap, cSigAligned, prodSig))
  private val sSig = Mux(cZero, 0.U(W2.W), Mux(swap, prodSig, cSigAligned))
  private val lSign = Mux(cZero, prodSign, Mux(swap, cSign, prodSign))

  private val shift = (lExp - sExp).asUInt
  private val (sKept, sSticky) = ShiftRightJam(sSig, shift)

  private val lFixed = (lSig << G)(W2 + G - 1, 0)
  private val sFixed = (sKept << G)(W2 + G - 1, 0)
  private val lExt = lFixed.pad(A)
  private val sExt = sFixed.pad(A)
  private val subtraction = lSign =/= Mux(cZero, prodSign, Mux(swap, prodSign, cSign))
  // 减法时，被截掉的小操作数尾巴让精确值落在 D-1 与 D 之间；用 -1 和
  // 正 remainder 表示，等价于“当前整数加一个 (0,1) ulp 的正尾数”。
  private val dSum = Wire(UInt(A.W))
  dSum := Mux(
    subtraction,
    lExt - sExt - (subtraction && sSticky).asUInt,
    lExt + sExt
  )
  private val remainder = sSticky

  private val dZero = dSum === 0.U
  private val lzD = CLZ(dSum)
  private val dn = (dSum << lzD)(A - 1, 0)
  private val eRes = Wire(SInt(ExpW.W))
  eRes := lExp + 1.S(ExpW.W) - lzD.zext.asSInt

  // 正常数舍入
  private val normalRounder = Module(new RoundingUnit(P - 1))
  normalRounder.io.in := dn(A - 2, A - P)
  normalRounder.io.roundIn := dn(A - P - 1)
  normalRounder.io.stickyIn := dn(A - P - 2, 0).orR || remainder
  normalRounder.io.signIn := lSign
  normalRounder.io.rm := io.rm

  private val normalExp = Wire(SInt(ExpW.W))
  normalExp := eRes + normalRounder.io.cout.asUInt.zext.asSInt
  private val normalExpField = normalExp + Bias.S(ExpW.W)
  private val normalOverflow = normalExp > Bias.S(ExpW.W)
  private val normalNx = normalRounder.io.inexact || normalOverflow
  private val normalBits = Cat(
    lSign,
    (normalExpField.asUInt)(expWidth - 1, 0),
    normalRounder.io.out
  )

  // 次正规数舍入
  private val subLsbExp = (1 - Bias) - (P - 1)
  private val subShiftS = Wire(SInt(ExpW.W))
  subShiftS := subLsbExp.S(ExpW.W) -
    (eRes - (A - 1).S(ExpW.W))
  private val subShift = subShiftS.asUInt
  // 先右移 sh-1 位：最低位正好是 round bit，其余被截掉的是 sticky。
  private val (subOut, subOutSticky) = ShiftRightJam(dn, subShift - 1.U)
  private val subKept = subOut(P - 1, 1)
  private val subRound = subOut(0)
  private val subSticky =
    subOutSticky || subOut(A - 1, P).orR || remainder

  private val subRounder = Module(new RoundingUnit(P - 1))
  subRounder.io.in := subKept
  subRounder.io.roundIn := subRound
  subRounder.io.stickyIn := subSticky
  subRounder.io.signIn := lSign
  subRounder.io.rm := io.rm

  private val subCarry = subRounder.io.cout
  private val subBits = Mux(
    subCarry,
    Cat(lSign, 1.U(expWidth.W), 0.U((P - 1).W)),
    Cat(lSign, 0.U(expWidth.W), subRounder.io.out)
  )
  private val subNx = subRounder.io.inexact
  // tininess after rounding（RISC-V 默认）只在舍入后指数域仍为 0 时置 UF；
  // HardFloat 的 tininess-before 选项则在舍入前的小值上就置 UF。
  private val subUf =
    if (tininessBeforeRounding) subRounder.io.inexact
    else subRounder.io.inexact && !subCarry

  private val normalPath = eRes >= (1 - Bias).S(ExpW.W)
  private val rmin = RoundingUnit.is_rmin(io.rm, lSign)
  private val ofExp = Mux(
    rmin,
    ((1 << expWidth) - 2).U(expWidth.W),
    ((1 << expWidth) - 1).U(expWidth.W)
  )
  private val ofSig = Mux(
    rmin,
    Fill(P - 1, 1.U(1.W)),
    0.U((P - 1).W)
  )
  private val overflowBits = Cat(lSign, ofExp, ofSig)

  private val exactBits = Mux(
    normalPath,
    Mux(normalOverflow, overflowBits, normalBits),
    subBits
  )
  private val exactNx = Mux(normalPath, normalNx, subNx)
  private val exactOf = normalPath && normalOverflow
  private val exactUf = !normalPath && subUf

  private val cancelZero = Cat(
    Mux(io.rm === _root_.fudian.RDN, 1.U(1.W), 0.U(1.W)),
    0.U((Total - 1).W)
  )
  private val exactResult = Mux(dZero, cancelZero, exactBits)
  private val exactFlags = Cat(exactOf, exactUf, exactNx)

  // 特殊值结果
  private val canonicalNaN = Cat(
    0.U(1.W),
    Fill(expWidth + 1, 1.U(1.W)),
    0.U((P - 2).W)
  )
  private val prodInfBits = Cat(
    prodSign,
    Fill(expWidth, 1.U(1.W)),
    0.U((P - 1).W)
  )
  private val cInfBits = Cat(cSign, Fill(expWidth, 1.U(1.W)), 0.U((P - 1).W))
  private val prodZeroBits = Mux(
    cZero,
    Cat(
      Mux(
        prodSign === cSign,
        prodSign,
        Mux(io.rm === _root_.fudian.RDN, 1.U(1.W), 0.U(1.W))
      ),
      0.U((Total - 1).W)
    ),
    io.c
  )

  private val result = Mux(
    nanResult,
    canonicalNaN,
    Mux(
      prodInf,
      prodInfBits,
      Mux(cInf, cInfBits, Mux(prodZero, prodZeroBits, exactResult))
    )
  )
  private val flags = Mux(
    nanResult,
    Cat(nv, 0.U(1.W), 0.U(3.W)),
    Mux(
      prodInf || cInf || prodZero,
      0.U(5.W),
      Cat(0.U(2.W), exactFlags)
    )
  )
  io.result := result
  io.fflags := flags
}
