package ysyx_26030103.fpu

import chisel3._
import chisel3.util._
import _root_.fudian._
import _root_.ysyx_26030103.config._

/** 基于 Fudian 的可选 F/D FPU 后端。
  *
  * 该模块只实现 `ysyx_26030103_FPUInterface` 的语义，不涉及 CPU 的
  * IDU/EXU/CSR/浮点寄存器堆，也不做 HartFloat 兼容。顶层直接使用
  * IEEE-754 位模式：F 格式为 32 位，D 格式为 64 位。
  *
  * 结构：
  *   - 组合运算（FADD/FMUL/FMA/FCMP/转换/比较等）直接复用 Fudian 顶层；
  *   - FDIV/FSQRT 复用 Fudian 的多周期 `FDIV`，由外层 valid/ready 状态机
  *     适配成统一的 `Decoupled` 响应；
  *   - FMIN/FMAX/FCLASS/FSGNJ 以及 NaN-boxing 在 wrapper 内完成。
  *
  * 注意：
  *   - Fudian 的 `FDIV` 内部固定使用 after-rounding tininess，符合 RISC-V；
  *   - `FpuRequest.rm` 的 DYN(7) 需要上层先用 `frm` 解析。本接口没有独立
  *     的动态舍入输入，因此这里对 DYN/保留值统一按 RNE 处理；CPU 集成时
  *     必须在送入本后端前解析 `frm`；
  *   - 整数结果受 `FpuResponse.FLEN` 限制。`XLEN > FLEN` 时（例如 RV64
  *     只启用 F 且 FLEN=32）64 位整数结果会被截断，需要上层先扩展接口；
  *   - F 格式结果统一做 NaN-boxing；F 格式输入未 NaN-box 时按规范视为
  *     规范 NaN（FSGNJ/FMV 等位操作除外）；
  *   - `FPU_FUDIAN_SHARED_DIVSQRT` 当前不改变 RTL：Fudian 的 FDIV 精度
  *     在 elaboration 时固定，S/D 各需要一个引擎；每个引擎内部已共享
  *     FDIV/FSQRT。跨 S/D 共享会引入双舍入，未实现。
  */
class ysyx_26030103_FudianFPU(override val Config: ysyx_26030103_NPCConfig)
    extends ysyx_26030103_FPU(Config) {

  private val FLEN = Config.FPULen
  private val XLEN = Config.XLEN
  private val HasD = FLEN == 64

  // 公开 profile 参数。Fudian FDIV 的 expWidth/precision 在 elaboration
  // 时固定，无法用一个引擎同时处理 F 和 D；当前实现按格式各例化一个
  // FDIV，并在每个格式内共享 FDIV/FSQRT（specialIO.isSqrt）。跨精度
  // 共享需要格式转换与双舍入修正，本任务不实现；该参数保留为公开配置，
  // true/false 当前都不改变 RTL。
  private val SharedDivSqrt = Config.FPUProfile.Fudian.SharedDivSqrt

  private val SExpWidth = 8
  private val SPrecision = 24
  private val DExpWidth = 11
  private val DPrecision = 53

  private val canonicalNaN32 = "h7fc00000".U(32.W)
  private val allOnes32 = "hffffffff".U(32.W)

  private def flipSign(x: UInt, width: Int): UInt =
    x ^ (BigInt(1) << (width - 1)).U(width.W)

  /** F 格式输入的 NaN-boxing 检查：未 box 的输入按规范视为规范 NaN。 */
  private def unboxS(x: UInt): UInt =
    if (FLEN == 32) {
      x
    } else {
      Mux(x(63, 32) === allOnes32, x(31, 0), canonicalNaN32)
    }

  /** F 格式结果写回时统一 NaN-box。 */
  private def boxS(x: UInt): UInt =
    if (FLEN == 32) x else Cat(allOnes32, x)

  /** 把任意宽度结果截断/扩展成响应位宽。 */
  private def asResp(x: UInt): UInt =
    if (x.getWidth == FLEN) x
    else if (x.getWidth > FLEN) x(FLEN - 1, 0)
    else x.pad(FLEN)

  /** DYN 与保留值按 RNE 处理；上层负责在进入后端前解析 frm。 */
  private def resolveRm(rm: UInt): UInt =
    Mux(rm <= ysyx_26030103_FpuRm.RMM, rm, ysyx_26030103_FpuRm.RNE)

  private def classifyBits(x: UInt, expWidth: Int, precision: Int): UInt = {
    val fp = FloatPoint.fromUInt(x, expWidth, precision)
    val decode = fp.decode
    val cls = Wire(UInt(10.W))
    cls := 0.U
    when(decode.isInf) {
      cls := Mux(fp.sign, 1.U(10.W), (BigInt(1) << 7).U(10.W))
    }.elsewhen(decode.isNaN) {
      cls := Mux(
        decode.isSNaN,
        (BigInt(1) << 8).U(10.W),
        (BigInt(1) << 9).U(10.W)
      )
    }.elsewhen(decode.isZero) {
      cls := Mux(fp.sign, 8.U(10.W), 16.U(10.W))
    }.elsewhen(decode.expIsZero) {
      cls := Mux(fp.sign, 4.U(10.W), 32.U(10.W))
    }.otherwise {
      cls := Mux(fp.sign, 2.U(10.W), 64.U(10.W))
    }
    cls
  }

  /** RISC-V FMIN/FMAX：NaN 传播规则、-0/+0 顺序与 NV 标志。 */
  private def minMaxBits(
      x: UInt,
      y: UInt,
      expWidth: Int,
      precision: Int,
      IsMax: Boolean
  ): (UInt, UInt) = {
    val fpX = FloatPoint.fromUInt(x, expWidth, precision)
    val fpY = FloatPoint.fromUInt(y, expWidth, precision)
    val decX = fpX.decode
    val decY = fpY.decode

    val xAbs = Cat(fpX.exp, fpX.sig)
    val yAbs = Cat(fpY.exp, fpY.sig)
    val xLtY = Mux(
      fpX.sign === fpY.sign,
      Mux(fpX.sign, xAbs > yAbs, xAbs < yAbs),
      fpX.sign
    )

    val bothNaN = decX.isNaN && decY.isNaN
    val ordered = Mux(
      IsMax.B,
      Mux(xLtY, y, x),
      Mux(xLtY, x, y)
    )
    val result = Mux(
      bothNaN,
      FloatPoint.defaultNaNUInt(expWidth, precision),
      Mux(decX.isNaN, y, Mux(decY.isNaN, x, ordered))
    )
    val fflags = Cat(decX.isSNaN || decY.isSNaN, 0.U(4.W))
    (result, fflags)
  }

  // ---------------------------------------------------------------------
  // 操作数与舍入模式
  // ---------------------------------------------------------------------
  private val op = io.req.bits.op
  private val fmtD = HasD.B && io.req.bits.fmt === ysyx_26030103_FpuFmt.D
  private val rm = resolveRm(io.req.bits.rm)

  private val aS = unboxS(io.req.bits.rs1)
  private val bS = unboxS(io.req.bits.rs2)
  private val cS = unboxS(io.req.bits.rs3)
  private val aD = io.req.bits.rs1
  private val bD = io.req.bits.rs2
  private val cD = io.req.bits.rs3

  private val isSub = op === ysyx_26030103_FpuOp.FSub
  private val isNegA =
    op === ysyx_26030103_FpuOp.FFnma || op === ysyx_26030103_FpuOp.FFnms
  private val isNegC =
    op === ysyx_26030103_FpuOp.FFms || op === ysyx_26030103_FpuOp.FFnma

  // ---------------------------------------------------------------------
  // F 格式组合运算单元
  // ---------------------------------------------------------------------
  private val sAdd = Module(new FADD(SExpWidth, SPrecision))
  sAdd.io.a := aS
  sAdd.io.b := Mux(isSub, flipSign(bS, 32), bS)
  sAdd.io.rm := rm

  private val sMul = Module(new FMUL(SExpWidth, SPrecision))
  sMul.io.a := aS
  sMul.io.b := bS
  sMul.io.rm := rm

  private val sFma = Module(new FCMA(SExpWidth, SPrecision))
  sFma.io.a := Mux(isNegA, flipSign(aS, 32), aS)
  sFma.io.b := bS
  sFma.io.c := Mux(isNegC, flipSign(cS, 32), cS)
  sFma.io.rm := rm

  private val sCmp = Module(new FCMP(SExpWidth, SPrecision))
  sCmp.io.a := aS
  sCmp.io.b := bS
  // FEQ/FLT/FLE 是 quiet 比较：只有 sNaN 置 NV。
  sCmp.io.signaling := false.B

  // 整数转换的宽度由 XLEN 固定（接口没有单独的 W/L 字段）。
  private val cvtSigned = op === ysyx_26030103_FpuOp.FCvtFpToInt ||
    op === ysyx_26030103_FpuOp.FCvtIntToFp
  private val cvtLong = (XLEN == 64).B
  private val cvtToIntOp = Cat(cvtLong, cvtSigned)

  private val sFpToInt = Module(new FPToInt(SExpWidth, SPrecision))
  sFpToInt.io.a := aS
  sFpToInt.io.rm := rm
  sFpToInt.io.op := cvtToIntOp

  private val intToFpSigned = op === ysyx_26030103_FpuOp.FCvtIntToFp
  private val intToFpLong = XLEN == 64
  private val intSrc = if (XLEN == 64) {
    io.req.bits.rs1
  } else {
    io.req.bits.rs1(31, 0)
  }
  private val intSrc64: UInt = if (XLEN == 64) {
    intSrc
  } else {
    Mux(
      intToFpSigned,
      Cat(Fill(32, intSrc(31)), intSrc),
      Cat(0.U(32.W), intSrc)
    )
  }

  private val sIntToFp = Module(new IntToFP(SExpWidth, SPrecision))
  sIntToFp.io.int := intSrc64
  sIntToFp.io.sign := intToFpSigned
  sIntToFp.io.long := intToFpLong.B
  sIntToFp.io.rm := rm

  // ---------------------------------------------------------------------
  // D 格式组合运算单元（FLEN=32 时不生成）
  // ---------------------------------------------------------------------
  private val dAdd = if (HasD) Some(Module(new FADD(DExpWidth, DPrecision))) else None
  private val dMul = if (HasD) Some(Module(new FMUL(DExpWidth, DPrecision))) else None
  private val dFma = if (HasD) Some(Module(new FCMA(DExpWidth, DPrecision))) else None
  private val dCmp = if (HasD) Some(Module(new FCMP(DExpWidth, DPrecision))) else None
  private val dFpToInt =
    if (HasD) Some(Module(new FPToInt(DExpWidth, DPrecision))) else None
  private val dIntToFp =
    if (HasD) Some(Module(new IntToFP(DExpWidth, DPrecision))) else None

  if (HasD) {
    dAdd.get.io.a := aD
    dAdd.get.io.b := Mux(isSub, flipSign(bD, 64), bD)
    dAdd.get.io.rm := rm

    dMul.get.io.a := aD
    dMul.get.io.b := bD
    dMul.get.io.rm := rm

    dFma.get.io.a := Mux(isNegA, flipSign(aD, 64), aD)
    dFma.get.io.b := bD
    dFma.get.io.c := Mux(isNegC, flipSign(cD, 64), cD)
    dFma.get.io.rm := rm

    dCmp.get.io.a := aD
    dCmp.get.io.b := bD
    dCmp.get.io.signaling := false.B

    dFpToInt.get.io.a := aD
    dFpToInt.get.io.rm := rm
    dFpToInt.get.io.op := cvtToIntOp

    dIntToFp.get.io.int := intSrc64
    dIntToFp.get.io.sign := intToFpSigned
    dIntToFp.get.io.long := intToFpLong.B
    dIntToFp.get.io.rm := rm
  }

  /** 按运行时 fmt 在两个精度的结果之间选择。 */
  private def byFmt(s: UInt, d: => UInt): UInt =
    if (HasD) Mux(fmtD, d, s) else s

  // FCVT.S.D / FCVT.D.S
  private val f64To32 =
    if (HasD) Some(Module(new FPToFP(DExpWidth, DPrecision, SExpWidth, SPrecision)))
    else None
  private val f32To64 =
    if (HasD) Some(Module(new FPToFP(SExpWidth, SPrecision, DExpWidth, DPrecision)))
    else None
  if (HasD) {
    f64To32.get.io.in := io.req.bits.rs1
    f64To32.get.io.rm := rm
    f32To64.get.io.in := aS
    f32To64.get.io.rm := rm
  }

  // ---------------------------------------------------------------------
  // 比较 / 分类 / 位操作
  // ---------------------------------------------------------------------
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

  // ---------------------------------------------------------------------
  // 快速运算结果（组合），FDIV/FSQRT 走多周期状态机
  // ---------------------------------------------------------------------
  private val fastResult = Wire(UInt(FLEN.W))
  private val fastFlags = Wire(UInt(5.W))
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
    is(ysyx_26030103_FpuOp.FFma) {
      fastResult := byFmt(boxS(sFma.io.result), dFma.get.io.result)
      fastFlags := byFmt(sFma.io.fflags, dFma.get.io.fflags)
    }
    is(ysyx_26030103_FpuOp.FFms) {
      fastResult := byFmt(boxS(sFma.io.result), dFma.get.io.result)
      fastFlags := byFmt(sFma.io.fflags, dFma.get.io.fflags)
    }
    is(ysyx_26030103_FpuOp.FFnma) {
      fastResult := byFmt(boxS(sFma.io.result), dFma.get.io.result)
      fastFlags := byFmt(sFma.io.fflags, dFma.get.io.fflags)
    }
    is(ysyx_26030103_FpuOp.FFnms) {
      fastResult := byFmt(boxS(sFma.io.result), dFma.get.io.result)
      fastFlags := byFmt(sFma.io.fflags, dFma.get.io.fflags)
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
      fastResult := asResp(byFmt(sFpToInt.io.result, dFpToInt.get.io.result))
      fastFlags := byFmt(sFpToInt.io.fflags, dFpToInt.get.io.fflags)
    }
    is(ysyx_26030103_FpuOp.FCvtFpToUInt) {
      fastResult := asResp(byFmt(sFpToInt.io.result, dFpToInt.get.io.result))
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

  // ---------------------------------------------------------------------
  // FDIV/FSQRT：多周期 valid/ready
  // ---------------------------------------------------------------------
  private val isSlowOp =
    op === ysyx_26030103_FpuOp.FDiv || op === ysyx_26030103_FpuOp.FSqrt
  private val isSqrtOp = op === ysyx_26030103_FpuOp.FSqrt

  private val sDiv = Module(new FDIV(SExpWidth, SPrecision)).suggestName(
    if (SharedDivSqrt) "sDivSharedDivSqrt" else "sDivDedicatedDivSqrt"
  )
  sDiv.io.a := aS
  sDiv.io.b := bS
  sDiv.io.rm := rm
  sDiv.io.specialIO.isSqrt := isSqrtOp
  sDiv.io.specialIO.kill := false.B
  sDiv.io.specialIO.in_valid := false.B
  sDiv.io.specialIO.out_ready := false.B

  private val dDiv = if (HasD) {
    Some(
      Module(new FDIV(DExpWidth, DPrecision)).suggestName(
        if (SharedDivSqrt) "dDivSharedDivSqrt" else "dDivDedicatedDivSqrt"
      )
    )
  } else {
    None
  }
  if (HasD) {
    dDiv.get.io.a := aD
    dDiv.get.io.b := bD
    dDiv.get.io.rm := rm
    dDiv.get.io.specialIO.isSqrt := isSqrtOp
    dDiv.get.io.specialIO.kill := false.B
    dDiv.get.io.specialIO.in_valid := false.B
    dDiv.get.io.specialIO.out_ready := false.B
  }

  private val busy = RegInit(false.B)
  private val respValid = RegInit(false.B)
  private val activeDivIsD = RegInit(false.B)
  // Fudian FDIV 在 post_0 状态才把最终 result/fflags 写入寄存器；out_valid
  // 在进入 post_0 时已拉高。先等一拍让结果寄存器落定，再在下一拍采样并
  // 拉高 out_ready，避免把上一笔运算的残留值当成结果。
  private val divSeenValid = RegInit(false.B)
  private val respResult = RegInit(0.U(FLEN.W))
  private val respFlags = RegInit(0.U(5.W))

  private val slowReady: Bool = if (HasD) {
    Mux(fmtD, dDiv.get.io.specialIO.in_ready, sDiv.io.specialIO.in_ready)
  } else {
    sDiv.io.specialIO.in_ready
  }

  io.req.ready := !busy && !respValid && (!isSlowOp || slowReady)
  private val reqFire = io.req.valid && io.req.ready

  sDiv.io.specialIO.in_valid := reqFire && isSlowOp && !fmtD
  if (HasD) {
    dDiv.get.io.specialIO.in_valid := reqFire && isSlowOp && fmtD
  }

  private val divOutValid: Bool = if (HasD) {
    Mux(activeDivIsD, dDiv.get.io.specialIO.out_valid, sDiv.io.specialIO.out_valid)
  } else {
    sDiv.io.specialIO.out_valid
  }
  private val divFlags: UInt = if (HasD) {
    Mux(activeDivIsD, dDiv.get.io.fflags, sDiv.io.fflags)
  } else {
    sDiv.io.fflags
  }

  sDiv.io.specialIO.out_ready := busy && !activeDivIsD && divSeenValid && divOutValid
  if (HasD) {
    dDiv.get.io.specialIO.out_ready := busy && activeDivIsD && divSeenValid && divOutValid
  }

  when(respValid && io.resp.ready) {
    respValid := false.B
  }

  when(reqFire) {
    when(isSlowOp) {
      busy := true.B
      divSeenValid := false.B
      activeDivIsD := fmtD
    }.otherwise {
      respValid := true.B
      respResult := fastResult
      respFlags := fastFlags
    }
  }

  when(busy && divOutValid) {
    when(!divSeenValid) {
      divSeenValid := true.B
    }.otherwise {
      busy := false.B
      respValid := true.B
      respResult := asResp(
        if (HasD) Mux(activeDivIsD, dDiv.get.io.result, boxS(sDiv.io.result))
        else boxS(sDiv.io.result)
      )
      respFlags := divFlags
    }
  }

  io.resp.valid := respValid
  io.resp.bits.result := respResult
  io.resp.bits.fflags := respFlags
}
