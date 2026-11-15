package ysyx_26030103.fpu

import chisel3._
import chisel3.util._
import _root_.fudian.RoundingUnit
import _root_.fudian.utils.{CLZ, Multiplier, ShiftRightJam}

/** 非融合但单次舍入的 FMA 数据通路。
  *
  * `FPU_FMA=n` 时不能用 Fudian/FCMA 或 HardFloat/MulAddRecFN 这类融合
  * 乘加单元冒充“关闭”。这里只复用 fudian 的 `Multiplier`/`ShiftRightJam`/
  * `RoundingUnit`/`CLZ` 作为基础模块，加法部分由本 wrapper 把精确乘积与
  * 第三操作数对齐到同一定点窗口，低位只留 sticky 后统一规格化，保持
  * RISC-V 单次舍入语义与异常标志。四类 FMA 的符号组合由调用方翻转
  * a/c 符号位后统一按 a*b+c 计算。
  */
class ysyx_26030103_ExactFMA(
    val expWidth: Int,
    val precision: Int,
    val tininessBeforeRounding: Boolean = false
) extends Module {
  require(expWidth >= 2 && precision >= 2,
    s"ExactFMA 参数非法: expWidth=$expWidth precision=$precision")

  private val precisionBits = precision
  private val exponentBias = (1 << (expWidth - 1)) - 1
  private val extendedExpWidth = expWidth + 3
  private val productWidth = 2 * precisionBits
  // 定点窗口在精确乘积之外保留 4 个保护位，再留 1 位进位。
  private val guardBits = 4
  private val alignWidth = productWidth + guardBits + 1
  private val totalWidth = expWidth + precisionBits

  val io = IO(new Bundle {
    val a = Input(UInt(totalWidth.W))
    val b = Input(UInt(totalWidth.W))
    val c = Input(UInt(totalWidth.W))
    val rm = Input(UInt(3.W))
    val result = Output(UInt(totalWidth.W))
    val fflags = Output(UInt(ysyx_26030103_FPUFlags.Width.W))
  })

  private def signBit(value: UInt): Bool = value(totalWidth - 1)
  private def expField(value: UInt): UInt = value(totalWidth - 2, precisionBits - 1)
  private def fracField(value: UInt): UInt = value(precisionBits - 2, 0)
  private def expAllOnes(value: UInt): Bool = expField(value).andR
  private def isNaN(value: UInt): Bool = expAllOnes(value) && fracField(value).orR
  private def isSNaN(value: UInt): Bool = isNaN(value) && !fracField(value)(precisionBits - 2)
  private def isInf(value: UInt): Bool = expAllOnes(value) && !fracField(value).orR
  private def isZero(value: UInt): Bool = !expField(value).orR && !fracField(value).orR
  private def isSubnormal(value: UInt): Bool = !expField(value).orR && fracField(value).orR

  // 把有限数规格化为 P 位尾数（隐藏位在最高位）和最高位权指数。
  private def normalize(value: UInt): (UInt, SInt) = {
    val zero = isZero(value)
    val sub = isSubnormal(value)
    val raw = Cat(!sub && !zero, fracField(value))
    val lz = CLZ(raw)
    val shifted = (raw << lz)(precisionBits - 1, 0)
    val sig = Mux(sub, shifted, raw)

    val expSub = Wire(SInt(extendedExpWidth.W))
    expSub := (1 - exponentBias).S(extendedExpWidth.W) - lz.zext.asSInt
    val expNorm = Wire(SInt(extendedExpWidth.W))
    expNorm := expField(value).zext.asSInt - exponentBias.S(extendedExpWidth.W)
    val expZero = 0.S(extendedExpWidth.W)
    val exp = Mux(zero, expZero, Mux(sub, expSub, expNorm))
    (sig, exp)
  }

  private val aNaN = isNaN(io.a)
  private val bNaN = isNaN(io.b)
  private val cNaN = isNaN(io.c)
  private val anyNaN = aNaN || bNaN || cNaN
  private val anySNaN = isSNaN(io.a) || isSNaN(io.b) || isSNaN(io.c)

  private val aZero = isZero(io.a)
  private val bZero = isZero(io.b)
  private val cZero = isZero(io.c)
  private val aInf = isInf(io.a)
  private val bInf = isInf(io.b)
  private val cInf = isInf(io.c)

  private val prodSign = signBit(io.a) ^ signBit(io.b)
  private val cSign = signBit(io.c)

  // 0×Inf 与 Inf-Inf 产生 invalid；其余 Inf 直接传播。
  private val invalidMul = (aInf && bZero) || (aZero && bInf)
  private val prodInf = (aInf && !bZero) || (bInf && !aZero)
  private val prodZero = !invalidMul && !prodInf && (aZero || bZero)
  private val infMinusInf = prodInf && cInf && (prodSign =/= cSign)
  private val invalid = invalidMul || infMinusInf
  private val nanResult = anyNaN || invalid
  private val nv = anySNaN || invalid

  private val (aSig, aExp) = normalize(io.a)
  private val (bSig, bExp) = normalize(io.b)
  private val (cSig, cExp) = normalize(io.c)

  // 精确乘积：两个 P 位尾数的乘积最多 2P 位，这里不进行任何舍入。
  // vendor Multiplier 的 len 包含符号位语义，FMUL 也使用 P+1 并在高位补 0；
  // 直接用 len=P 会把最高位为 1 的正常尾数当成负数。
  private val multiplier = Module(new Multiplier(precisionBits + 1, Seq()))
  multiplier.io.a := aSig.pad(precisionBits + 1)
  multiplier.io.b := bSig.pad(precisionBits + 1)
  private val prodRaw = multiplier.io.result(productWidth - 1, 0)
  private val prodMsb = prodRaw(productWidth - 1)
  private val prodSig = Mux(prodMsb, prodRaw, (prodRaw << 1)(productWidth - 1, 0))
  private val prodExp = Wire(SInt(extendedExpWidth.W))
  prodExp := aExp + bExp + prodMsb.asUInt.zext.asSInt

  // 把 c 的 P 位尾数左对齐到 2P 位，使两个操作数共享同一个 MSB 位置。
  private val cSigAligned = (cSig << precisionBits)(productWidth - 1, 0)

  private val swap = !cZero && (
    (prodExp < cExp) ||
      (prodExp === cExp && prodSig < cSigAligned)
  )
  private val lExp = Mux(cZero, prodExp, Mux(swap, cExp, prodExp))
  private val sExp = Mux(cZero, prodExp, Mux(swap, prodExp, cExp))
  private val lSig = Mux(cZero, prodSig, Mux(swap, cSigAligned, prodSig))
  private val sSig = Mux(cZero, 0.U(productWidth.W), Mux(swap, prodSig, cSigAligned))
  private val lSign = Mux(cZero, prodSign, Mux(swap, cSign, prodSign))

  private val shift = (lExp - sExp).asUInt
  private val (sKept, sSticky) = ShiftRightJam(sSig, shift)

  private val lFixed = (lSig << guardBits)(productWidth + guardBits - 1, 0)
  private val sFixed = (sKept << guardBits)(productWidth + guardBits - 1, 0)
  private val lExt = lFixed.pad(alignWidth)
  private val sExt = sFixed.pad(alignWidth)
  private val subtraction = lSign =/= Mux(cZero, prodSign, Mux(swap, prodSign, cSign))
  // 减法时，被截掉的小操作数尾巴让精确值落在 D-1 与 D 之间；用 -1 和
  // 正 remainder 表示，等价于“当前整数加一个 (0,1) ulp 的正尾数”。
  private val dSum = Wire(UInt(alignWidth.W))
  dSum := Mux(
    subtraction,
    lExt - sExt - (subtraction && sSticky).asUInt,
    lExt + sExt
  )
  private val remainder = sSticky

  private val dZero = dSum === 0.U
  private val lzD = CLZ(dSum)
  private val dn = (dSum << lzD)(alignWidth - 1, 0)
  private val eRes = Wire(SInt(extendedExpWidth.W))
  eRes := lExp + 1.S(extendedExpWidth.W) - lzD.zext.asSInt

  // 正常数舍入
  private val normalRounder = Module(new RoundingUnit(precisionBits - 1))
  normalRounder.io.in := dn(alignWidth - 2, alignWidth - precisionBits)
  normalRounder.io.roundIn := dn(alignWidth - precisionBits - 1)
  normalRounder.io.stickyIn := dn(alignWidth - precisionBits - 2, 0).orR || remainder
  normalRounder.io.signIn := lSign
  normalRounder.io.rm := io.rm

  private val normalExp = Wire(SInt(extendedExpWidth.W))
  normalExp := eRes + normalRounder.io.cout.asUInt.zext.asSInt
  private val normalExpField = normalExp + exponentBias.S(extendedExpWidth.W)
  private val normalOverflow = normalExp > exponentBias.S(extendedExpWidth.W)
  private val normalNx = normalRounder.io.inexact || normalOverflow
  private val normalBits = Cat(
    lSign,
    (normalExpField.asUInt)(expWidth - 1, 0),
    normalRounder.io.out
  )

  // 次正规数舍入
  private val subLsbExp = (1 - exponentBias) - (precisionBits - 1)
  private val subShiftS = Wire(SInt(extendedExpWidth.W))
  subShiftS := subLsbExp.S(extendedExpWidth.W) -
    (eRes - (alignWidth - 1).S(extendedExpWidth.W))
  private val subShift = subShiftS.asUInt
  // 先右移 sh-1 位：最低位正好是 round bit，其余被截掉的是 sticky。
  private val (subOut, subOutSticky) = ShiftRightJam(dn, subShift - 1.U)
  private val subKept = subOut(precisionBits - 1, 1)
  private val subRound = subOut(0)
  private val subSticky =
    subOutSticky || subOut(alignWidth - 1, precisionBits).orR || remainder

  private val subRounder = Module(new RoundingUnit(precisionBits - 1))
  subRounder.io.in := subKept
  subRounder.io.roundIn := subRound
  subRounder.io.stickyIn := subSticky
  subRounder.io.signIn := lSign
  subRounder.io.rm := io.rm

  private val subCarry = subRounder.io.cout
  private val subBits = Mux(
    subCarry,
    Cat(lSign, 1.U(expWidth.W), 0.U((precisionBits - 1).W)),
    Cat(lSign, 0.U(expWidth.W), subRounder.io.out)
  )
  private val subNx = subRounder.io.inexact
  // tininess after rounding（RISC-V 默认）只在舍入后指数域仍为 0 时置 UF；
  // HardFloat 的 tininess-before 选项则在舍入前的小值上就置 UF。
  private val subUf =
    if (tininessBeforeRounding) subRounder.io.inexact
    else subRounder.io.inexact && !subCarry

  private val normalPath = eRes >= (1 - exponentBias).S(extendedExpWidth.W)
  private val rmin = RoundingUnit.is_rmin(io.rm, lSign)
  private val ofExp = Mux(
    rmin,
    ((1 << expWidth) - 2).U(expWidth.W),
    ((1 << expWidth) - 1).U(expWidth.W)
  )
  private val ofSig = Mux(
    rmin,
    Fill(precisionBits - 1, 1.U(1.W)),
    0.U((precisionBits - 1).W)
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
    0.U((totalWidth - 1).W)
  )
  private val exactResult = Mux(dZero, cancelZero, exactBits)
  private val exactFlags = {
    val width = ysyx_26030103_FPUFlags.Width
    val zero = 0.U(width.W)
    Mux(exactOf, (1 << ysyx_26030103_FPUFlags.OF).U(width.W), zero) |
      Mux(exactUf, (1 << ysyx_26030103_FPUFlags.UF).U(width.W), zero) |
      Mux(exactNx, (1 << ysyx_26030103_FPUFlags.NX).U(width.W), zero)
  }

  // 特殊值结果
  private val canonicalNaN = Cat(
    0.U(1.W),
    Fill(expWidth + 1, 1.U(1.W)),
    0.U((precisionBits - 2).W)
  )
  private val prodInfBits = Cat(
    prodSign,
    Fill(expWidth, 1.U(1.W)),
    0.U((precisionBits - 1).W)
  )
  private val cInfBits = Cat(cSign, Fill(expWidth, 1.U(1.W)), 0.U((precisionBits - 1).W))
  private val prodZeroBits = Mux(
    cZero,
    Cat(
      Mux(
        prodSign === cSign,
        prodSign,
        Mux(io.rm === _root_.fudian.RDN, 1.U(1.W), 0.U(1.W))
      ),
      0.U((totalWidth - 1).W)
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
    Cat(nv, 0.U(ysyx_26030103_FPUFlags.NV.W)),
    Mux(
      prodInf || cInf || prodZero,
      0.U(ysyx_26030103_FPUFlags.Width.W),
      exactFlags
    )
  )
  io.result := result
  io.fflags := flags
}
