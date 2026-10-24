package ysyx_26030103.fpu

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.circt.stage.ChiselStage
import _root_.ysyx_26030103.config._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable.ArrayBuffer

/** FPU 公开参数的行为回归。
  *
  * 覆盖：
  *   - ExactFMA（FPU_FMA=n）与精确有理数 oracle 的逐位/标志比对；
  *   - 两种后端在 FPU_FMA=n 时通过统一接口执行四类 FMA；
  *   - FPU_PIPELINE 的流水延迟、反压、flush；
  *   - Fudian F/D 共享除法/开方与独立引擎、精确 oracle 的一致性；
  *   - 生成 RTL 中 fused/exact FMA 与 shared/dedicated FDIV 的结构区分。
  */
class ysyx_26030103_FPUFeatureTest extends AnyFlatSpec with Matchers {

  private val NX = 0x01
  private val UF = 0x02
  private val OF = 0x04
  private val DZ = 0x08
  private val NV = 0x10

  // ------------------------------------------------------------------
  // 精确有理数参考模型
  // ------------------------------------------------------------------
  private case class Fmt(expWidth: Int, precision: Int) {
    val bias: Int = (1 << (expWidth - 1)) - 1
    val total: Int = expWidth + precision
    val maxExpField: Int = (1 << expWidth) - 1
    val fracMask: BigInt = (BigInt(1) << (precision - 1)) - 1

    def sign(bits: BigInt): Int =
      ((bits >> (total - 1)) & 1).toInt
    def expField(bits: BigInt): Int =
      ((bits >> (precision - 1)) & maxExpField).toInt
    def frac(bits: BigInt): BigInt = bits & fracMask
    def isNaN(bits: BigInt): Boolean =
      expField(bits) == maxExpField && frac(bits) != 0
    def isSNaN(bits: BigInt): Boolean =
      isNaN(bits) && ((frac(bits) >> (precision - 2)) & 1) == 0
    def isInf(bits: BigInt): Boolean =
      expField(bits) == maxExpField && frac(bits) == 0
    def isZero(bits: BigInt): Boolean =
      expField(bits) == 0 && frac(bits) == 0
    def canonicalNaN: BigInt =
      (BigInt(maxExpField) << (precision - 1)) |
        (BigInt(1) << (precision - 2))
    def inf(sign: Int): BigInt =
      (BigInt(sign) << (total - 1)) |
        (BigInt(maxExpField) << (precision - 1))
    def maxFinite(sign: Int): BigInt =
      (BigInt(sign) << (total - 1)) |
        (BigInt(maxExpField - 1) << (precision - 1)) | fracMask
    def zero(sign: Int): BigInt = BigInt(sign) << (total - 1)
    def pack(sign: Int, expField: Int, frac: BigInt): BigInt =
      (BigInt(sign) << (total - 1)) |
        (BigInt(expField) << (precision - 1)) | frac

    /** 有限非零数：value = sig * 2^exp，sig 为整数尾数。 */
    def decompose(bits: BigInt): (Int, BigInt, Int) = {
      val s = sign(bits)
      val e = expField(bits)
      val f = frac(bits)
      if (e == 0) {
        (s, f, 1 - bias - (precision - 1))
      } else {
        (s, (BigInt(1) << (precision - 1)) | f,
          e - bias - (precision - 1))
      }
    }

    private def rmin(rm: Int, sign: Int): Boolean =
      rm == 1 || (rm == 2 && sign == 0) || (rm == 3 && sign == 1)

    /** 把 sign * mag * 2^exp 舍入到当前格式；返回 (bits, fflags)。 */
    def round(
        sign: Int,
        mag: BigInt,
        exp: Int,
        rm: Int,
        stickyIn: Boolean = false
    ): (BigInt, Int) = {
      require(mag > 0, "round 只处理非零有限值")
      val msb = mag.bitLength - 1
      val eMsb = exp + msb
      val minSubLsb = 1 - bias - (precision - 1)
      var lsb = math.max(minSubLsb, eMsb - (precision - 1))

      val shift = lsb - exp
      var q = if (shift <= 0) mag << (-shift) else mag >> shift
      val roundBit = if (shift >= 1) ((mag >> (shift - 1)) & 1) == 1 else false
      val sticky = stickyIn || (if (shift >= 2)
        (mag & ((BigInt(1) << (shift - 1)) - 1)) != 0
      else false)
      val inexact0 = shift > 0 && mag != (q << shift) || stickyIn
      val up = rm match {
        case 0 => roundBit && (sticky || q.testBit(0))
        case 4 => roundBit
        case 3 => inexact0 && sign == 0
        case 2 => inexact0 && sign == 1
        case _ => false
      }
      if (up) q += 1
      if (q == (BigInt(1) << precision)) {
        q >>= 1
        lsb += 1
      }

      val inexact = inexact0
      if (q == 0) {
        (zero(sign), (if (inexact) UF | NX else 0))
      } else if (q < (BigInt(1) << (precision - 1))) {
        val flags = if (inexact) UF | NX else 0
        (pack(sign, 0, q), flags)
      } else {
        val expField = lsb + (precision - 1) + bias
        if (expField > maxExpField - 1) {
          val bits =
            if (rmin(rm, sign)) maxFinite(sign) else inf(sign)
          (bits, OF | NX)
        } else {
          val frac = q - (BigInt(1) << (precision - 1))
          val flags = if (inexact) NX else 0
          (pack(sign, expField, frac), flags)
        }
      }
    }

    def fma(a: BigInt, b: BigInt, c: BigInt, rm: Int): (BigInt, Int) = {
      val anyNaN = isNaN(a) || isNaN(b) || isNaN(c)
      val anySNaN = isSNaN(a) || isSNaN(b) || isSNaN(c)
      val aInf = isInf(a); val bInf = isInf(b); val cInf = isInf(c)
      val aZero = isZero(a); val bZero = isZero(b); val cZero = isZero(c)
      val prodSign = sign(a) ^ sign(b)
      val cSign = sign(c)
      val invalidMul = (aInf && bZero) || (aZero && bInf)
      val prodInf = (aInf && !bZero) || (bInf && !aZero)
      val prodZero = !invalidMul && !prodInf && (aZero || bZero)
      val infMinusInf = prodInf && cInf && prodSign != cSign
      val invalid = invalidMul || infMinusInf
      val nanResult = anyNaN || invalid
      val nv = anySNaN || invalid

      if (nanResult) {
        (canonicalNaN, if (nv) NV else 0)
      } else if (prodInf) {
        (inf(prodSign), 0)
      } else if (cInf) {
        (inf(cSign), 0)
      } else if (prodZero) {
        if (cZero) {
          val zs =
            if (prodSign == cSign) prodSign else if (rm == 2) 1 else 0
          (zero(zs), 0)
        } else {
          (c, 0)
        }
      } else {
        val (sa, ma, ea) = decompose(a)
        val (sb, mb, eb) = decompose(b)
        val (sc, mc, ec) = decompose(c)
        val mp = ma * mb
        val ep = ea + eb
        val minE =
          if (mc == 0) ep else if (mp == 0) ec else math.min(ep, ec)
        def signed(s: Int, m: BigInt): BigInt =
          if (s == 0) m else -m
        val total = signed(sa ^ sb, mp << (ep - minE)) +
          signed(sc, mc << (ec - minE))
        if (total == 0) {
          (zero(if (rm == 2) 1 else 0), 0)
        } else {
          round(if (total < 0) 1 else 0, total.abs, minE, rm)
        }
      }
    }

    def divide(a: BigInt, b: BigInt, rm: Int): (BigInt, Int) = {
      val anyNaN = isNaN(a) || isNaN(b)
      val anySNaN = isSNaN(a) || isSNaN(b)
      val aInf = isInf(a); val bInf = isInf(b)
      val aZero = isZero(a); val bZero = isZero(b)
      val s = sign(a) ^ sign(b)
      if (anyNaN) {
        (canonicalNaN, if (anySNaN) NV else 0)
      } else if (aInf && bInf) {
        (canonicalNaN, NV)
      } else if (aInf) {
        (inf(s), 0)
      } else if (bInf) {
        (zero(s), 0)
      } else if (bZero) {
        if (aZero) (canonicalNaN, NV) else (inf(s), DZ)
      } else if (aZero) {
        (zero(s), 0)
      } else {
        val (_, ma, ea) = decompose(a)
        val (_, mb, eb) = decompose(b)
        val k = 4 * precision + 20
        val num = ma << k
        val q = num / mb
        val rem = num - q * mb
        round(s, q, ea - eb - k, rm, stickyIn = rem != 0)
      }
    }

    def sqrt(a: BigInt, rm: Int): (BigInt, Int) = {
      val anyNaN = isNaN(a)
      val sNaN = isSNaN(a)
      if (anyNaN) {
        (canonicalNaN, if (sNaN) NV else 0)
      } else if (isZero(a)) {
        (zero(sign(a)), 0)
      } else if (sign(a) == 1) {
        (canonicalNaN, NV)
      } else if (isInf(a)) {
        (inf(0), 0)
      } else {
        val (_, m0, e0) = decompose(a)
        var m = m0
        var e = e0
        if ((e & 1) != 0) {
          m <<= 1
          e -= 1
        }
        val k = 4 * precision + 20
        val scaled = m << (2 * k)
        val q = BigInt(new java.math.BigInteger(scaled.toString).sqrt())
        val exact = q * q == scaled
        round(0, q, e / 2 - k, rm, stickyIn = !exact)
      }
    }
  }

  private val F32 = Fmt(8, 24)
  private val F64 = Fmt(11, 53)

  private def mask(bits: Int): BigInt = (BigInt(1) << bits) - 1
  private def bits32(x: BigInt): BigInt = x & mask(32)
  private def bits64(x: BigInt): BigInt = x & mask(64)
  private def boxS(x: BigInt): BigInt =
    (BigInt(0xffffffffL) << 32) | bits32(x)

  private def opVal(op: UInt): BigInt = op.litValue
  private val FAdd = opVal(ysyx_26030103_FpuOp.FAdd)
  private val FMul = opVal(ysyx_26030103_FpuOp.FMul)
  private val FDiv = opVal(ysyx_26030103_FpuOp.FDiv)
  private val FSqrt = opVal(ysyx_26030103_FpuOp.FSqrt)
  private val FFma = opVal(ysyx_26030103_FpuOp.FFma)
  private val FFms = opVal(ysyx_26030103_FpuOp.FFms)
  private val FFnma = opVal(ysyx_26030103_FpuOp.FFnma)
  private val FFnms = opVal(ysyx_26030103_FpuOp.FFnms)
  private val RNE = 0
  private val RTZ = 1
  private val RDN = 2
  private val RUP = 3
  private val RMM = 4
  private val S = 0
  private val D = 1

  private def fmaOracle(
      op: BigInt,
      a: BigInt,
      b: BigInt,
      c: BigInt,
      rm: Int,
      fmt: Fmt
  ): (BigInt, Int) = {
    val (ea, ec) =
      if (op == FFma) (a, c)
      else if (op == FFms) (a, c ^ (BigInt(1) << (fmt.total - 1)))
      else if (op == FFnms) (a ^ (BigInt(1) << (fmt.total - 1)), c)
      else (a ^ (BigInt(1) << (fmt.total - 1)),
        c ^ (BigInt(1) << (fmt.total - 1)))
    fmt.fma(ea, b, ec, rm)
  }

  // ------------------------------------------------------------------
  // 通用接口驱动
  // ------------------------------------------------------------------
  private def waitUntil(
      dut: ysyx_26030103_FPU,
      cond: => Boolean,
      maxCycles: Int = 4000
  ): Int = {
    var cycles = 0
    while (!cond && cycles < maxCycles) {
      dut.clock.step()
      cycles += 1
    }
    withClue(s"等待条件超时（$maxCycles 拍）: ") {
      cond should be(true)
    }
    cycles
  }

  private def send(
      dut: ysyx_26030103_FPU,
      flen: Int,
      op: BigInt,
      fmt: Int,
      rm: Int,
      a: BigInt,
      b: BigInt = 0,
      c: BigInt = 0
  ): (BigInt, Int) = {
    waitUntil(dut, dut.io.req.ready.peek().litToBoolean)
    dut.io.req.bits.op.poke(op.U(ysyx_26030103_FpuOp.Width.W))
    dut.io.req.bits.fmt.poke(fmt.U(ysyx_26030103_FpuFmt.Width.W))
    dut.io.req.bits.srcFmt.poke(fmt.U(ysyx_26030103_FpuFmt.Width.W))
    dut.io.req.bits.rm.poke(rm.U(ysyx_26030103_FpuRm.Width.W))
    dut.io.req.bits.intWord.poke(true.B)
    dut.io.req.bits.rs1.poke(a.U(flen.W))
    dut.io.req.bits.rs2.poke(b.U(flen.W))
    dut.io.req.bits.rs3.poke(c.U(flen.W))
    dut.io.req.valid.poke(true.B)
    dut.clock.step()
    dut.io.req.valid.poke(false.B)

    waitUntil(dut, dut.io.resp.valid.peek().litToBoolean)
    val result = dut.io.resp.bits.result.peek().litValue
    val flags = dut.io.resp.bits.fflags.peek().litValue.toInt
    dut.io.resp.ready.poke(true.B)
    dut.clock.step()
    dut.io.resp.ready.poke(false.B)
    (result, flags)
  }

  private def initDut(dut: ysyx_26030103_FPU): Unit = {
    dut.reset.poke(true.B)
    dut.clock.step(3)
    dut.reset.poke(false.B)
    dut.io.req.valid.poke(false.B)
    dut.io.resp.ready.poke(false.B)
    dut.io.flush.poke(false.B)
  }

  private def fudianConfig(
      fma: Boolean,
      pipeline: Int = 0,
      shared: Boolean = true
  ): ysyx_26030103_NPCConfig =
    ysyx_26030103_NPCConfig(
      XLEN = 64,
      AddressWidth = 64,
      UseF = true,
      UseD = true,
      FPUImpl = ysyx_26030103_FPUImpl.Fudian,
      FPULen = 64,
      FPUProfile = ysyx_26030103_FPUProfile(
        Pipeline = pipeline,
        FMA = fma,
        Fudian = ysyx_26030103_FudianParams(SharedDivSqrt = shared)
      )
    )

  private def hardfloatConfig(fma: Boolean): ysyx_26030103_NPCConfig =
    ysyx_26030103_NPCConfig(
      XLEN = 64,
      AddressWidth = 64,
      UseF = true,
      UseD = true,
      FPUImpl = ysyx_26030103_FPUImpl.HardFloat,
      FPULen = 64,
      FPUProfile = ysyx_26030103_FPUProfile(FMA = fma)
    )

  // ------------------------------------------------------------------
  // ExactFMA 单元测试
  // ------------------------------------------------------------------
  "ExactFMA" should "与精确 oracle 在定向/随机 F 用例上逐位一致" in {
    val rng = new scala.util.Random(0x5eed)
    val directed = Seq(
      // (a, b, c, rm, 说明)
      (0x3f800000L, 0x40000000L, 0x40400000L, RNE, "1*2+3"),
      (0x00000000L, 0x7f800000L, 0x3f800000L, RNE, "0*inf"),
      (0x7f800000L, 0x3f800000L, 0xff800000L, RNE, "inf-inf"),
      (0x7fc00000L, 0x3f800000L, 0x3f800000L, RNE, "qNaN"),
      (0x7f800001L, 0x3f800000L, 0x3f800000L, RNE, "sNaN"),
      (0x3f800000L, 0x3f800000L, 0xbf800000L, RNE, "cancel RNE"),
      (0x3f800000L, 0x3f800000L, 0xbf800000L, RDN, "cancel RDN"),
      (0x00800000L, 0x3f000000L, 0x00000000L, RNE, "subnormal exact"),
      (0x7f7fffffL, 0x40000000L, 0x00000000L, RNE, "overflow"),
      // 乘积恰好落在 S 精度的 tie 上，再加一个极小 c，验证不会双舍入。
      (0x3c8d8d8eL, 0x3f28c000L, 0x0c000000L, RNE, "tie+tiny")
    )
    val cases = ArrayBuffer[(BigInt, BigInt, BigInt, Int, String)]()
    directed.foreach { case (a, b, c, rm, tag) =>
      cases += ((bits32(BigInt(a)), bits32(BigInt(b)), bits32(BigInt(c)), rm, tag))
    }
    var i = 0
    while (i < 2500) {
      val a = bits32(BigInt(rng.nextInt()) & 0xffffffffL)
      val b = bits32(BigInt(rng.nextInt()) & 0xffffffffL)
      val c = bits32(BigInt(rng.nextInt()) & 0xffffffffL)
      cases += ((a, b, c, rng.nextInt(5), s"random$i"))
      i += 1
    }

    simulate(new ysyx_26030103_ExactFMA(8, 24)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      cases.foreach { case (a, b, c, rm, tag) =>
        dut.io.a.poke(a.U)
        dut.io.b.poke(b.U)
        dut.io.c.poke(c.U)
        dut.io.rm.poke(rm.U)
        dut.clock.step()
        val got = (dut.io.result.peek().litValue, dut.io.fflags.peek().litValue.toInt)
        val (wantR, wantF) = F32.fma(a, b, c, rm)
        withClue(s"$tag a=${a.toString(16)} b=${b.toString(16)} c=${c.toString(16)} rm=$rm: ") {
          got should be((wantR, wantF))
        }
      }
    }
  }

  it should "与精确 oracle 在 D 格式边界/随机用例上一致" in {
    val rng = new scala.util.Random(0xd00d)
    val cases = ArrayBuffer[(BigInt, BigInt, BigInt, Int)]()
    val edges = Seq(
      BigInt("3ff0000000000000", 16),
      BigInt("4000000000000000", 16),
      BigInt("0010000000000000", 16),
      BigInt("7fefffffffffffff", 16),
      BigInt("0000000000000001", 16),
      BigInt("7ff0000000000000", 16),
      BigInt("fff0000000000000", 16),
      BigInt("7ff8000000000000", 16)
    )
    for (a <- edges; b <- edges; c <- edges; rm <- 0 to 4) {
      cases += ((a, b, c, rm))
    }
    var i = 0
    while (i < 1200) {
      def rand64(): BigInt = {
        val hi = BigInt(rng.nextInt()) & 0xffffffffL
        val lo = BigInt(rng.nextInt()) & 0xffffffffL
        (hi << 32) | lo
      }
      cases += ((rand64(), rand64(), rand64(), rng.nextInt(5)))
      i += 1
    }

    simulate(new ysyx_26030103_ExactFMA(11, 53)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      cases.foreach { case (a, b, c, rm) =>
        dut.io.a.poke(a.U)
        dut.io.b.poke(b.U)
        dut.io.c.poke(c.U)
        dut.io.rm.poke(rm.U)
        dut.clock.step()
        val got = (dut.io.result.peek().litValue, dut.io.fflags.peek().litValue.toInt)
        val (wantR, wantF) = F64.fma(a, b, c, rm)
        withClue(s"a=${a.toString(16)} b=${b.toString(16)} c=${c.toString(16)} rm=$rm: ") {
          got should be((wantR, wantF))
        }
      }
    }
  }

  // ------------------------------------------------------------------
  // FPU_FMA=n 的两种后端
  // ------------------------------------------------------------------
  private def fmaBackendCheck(dut: ysyx_26030103_FPU): Unit = {
    val fmaOps = Seq(FFma, FFms, FFnma, FFnms)
    val rng = new scala.util.Random(0xf00d)
    // 定向：四类符号组合、特殊值、溢出/下溢、tie+tiny。
    for (op <- fmaOps; rm <- 0 to 4) {
      val probeS = Seq(
        (boxS(0x40000000L), boxS(0x40400000L), boxS(0x40800000L)),
        (boxS(0x3f800000L), boxS(0x3f800000L), boxS(0xbf800000L)),
        (boxS(0x00000000L), boxS(0x7f800000L), boxS(0x3f800000L)),
        (boxS(0x7f800000L), boxS(0x3f800000L), boxS(0xff800000L)),
        (boxS(0x7f7fffffL), boxS(0x40000000L), boxS(0x3f800000L)),
        (boxS(0x00800000L), boxS(0x3f000000L), boxS(0x00000000L))
      )
      probeS.foreach { case (a, b, c) =>
        val got = send(dut, 64, op, S, rm, a, b, c)
        val (wantR, wantF) = fmaOracle(op, bits32(a), bits32(b), bits32(c), rm, F32)
        got should be((boxS(wantR), wantF))
      }
    }
    var i = 0
    while (i < 120) {
      val a = boxS(BigInt(rng.nextInt()) & 0xffffffffL)
      val b = boxS(BigInt(rng.nextInt()) & 0xffffffffL)
      val c = boxS(BigInt(rng.nextInt()) & 0xffffffffL)
      val op = fmaOps(rng.nextInt(4))
      val rm = rng.nextInt(5)
      val got = send(dut, 64, op, S, rm, a, b, c)
      val (wantR, wantF) = fmaOracle(op, bits32(a), bits32(b), bits32(c), rm, F32)
      got should be((boxS(wantR), wantF))
      i += 1
    }

    // D 格式：定向 + 随机。
    val edges = Seq(
      BigInt("3ff0000000000000", 16),
      BigInt("4000000000000000", 16),
      BigInt("0010000000000000", 16),
      BigInt("7fefffffffffffff", 16),
      BigInt("0000000000000001", 16),
      BigInt("7ff0000000000000", 16),
      BigInt("fff0000000000000", 16)
    )
    var j = 0
    while (j < 80) {
      val a = if (j < edges.size) edges(j) else {
        (BigInt(rng.nextInt()) & 0xffffffffL) << 32 |
          (BigInt(rng.nextInt()) & 0xffffffffL)
      }
      val b = edges((j + 2) % edges.size)
      val c = edges((j + 4) % edges.size)
      val op = fmaOps(j % 4)
      val rm = j % 5
      val got = send(dut, 64, op, D, rm, a, b, c)
      val (wantR, wantF) = fmaOracle(op, a, b, c, rm, F64)
      withClue(s"D fma j=$j op=$op rm=$rm: ") {
        got should be((wantR, wantF))
      }
      j += 1
    }
  }

  "HardFloat FPU" should "在 FPU_FMA=n 时走非融合 ExactFMA 并保持语义" in {
    simulate(new ysyx_26030103_HardFloatFPU(hardfloatConfig(fma = false))) { dut =>
      initDut(dut)
      fmaBackendCheck(dut)
    }
  }

  "Fudian FPU" should "在 FPU_FMA=n 时走非融合 ExactFMA 并保持语义" in {
    simulate(new ysyx_26030103_FudianFPU(fudianConfig(fma = false))) { dut =>
      initDut(dut)
      fmaBackendCheck(dut)
    }
  }

  // ------------------------------------------------------------------
  // FPU_PIPELINE
  // ------------------------------------------------------------------
  private def pipelineBackpressureAndFlush(depth: Int): Unit = {
    simulate(new ysyx_26030103_FudianFPU(fudianConfig(fma = true, pipeline = depth))) {
      dut =>
        initDut(dut)

        // 先发一笔普通加法，保持下游不 ready，验证 valid/数据稳定。
        waitUntil(dut, dut.io.req.ready.peek().litToBoolean)
        dut.io.req.bits.op.poke(FAdd.U)
        dut.io.req.bits.fmt.poke(S.U)
        dut.io.req.bits.srcFmt.poke(S.U)
        dut.io.req.bits.rm.poke(RNE.U)
        dut.io.req.bits.intWord.poke(true.B)
        dut.io.req.bits.rs1.poke(boxS(BigInt(0x3fc00000L)).U(64.W))
        dut.io.req.bits.rs2.poke(boxS(BigInt(0x40100000L)).U(64.W))
        dut.io.req.bits.rs3.poke(0.U(64.W))
        dut.io.req.valid.poke(true.B)
        dut.clock.step()
        dut.io.req.valid.poke(false.B)

        waitUntil(dut, dut.io.resp.valid.peek().litToBoolean)
        val stable = dut.io.resp.bits.result.peek().litValue
        stable should be(boxS(BigInt(0x40700000L)))
        dut.clock.step(depth + 3)
        dut.io.resp.valid.peek().litToBoolean should be(true)
        dut.io.resp.bits.result.peek().litValue should be(stable)
        dut.io.resp.ready.poke(true.B)
        dut.clock.step()
        dut.io.resp.ready.poke(false.B)
        dut.io.resp.valid.peek().litToBoolean should be(false)

        // flush 清掉在途响应，随后仍能接受新请求。
        waitUntil(dut, dut.io.req.ready.peek().litToBoolean)
        dut.io.req.bits.op.poke(FMul.U)
        dut.io.req.bits.rs1.poke(boxS(BigInt(0x40400000L)).U(64.W))
        dut.io.req.bits.rs2.poke(boxS(BigInt(0x40000000L)).U(64.W))
        dut.io.req.valid.poke(true.B)
        dut.clock.step()
        dut.io.req.valid.poke(false.B)
        dut.io.flush.poke(true.B)
        dut.clock.step()
        dut.io.flush.poke(false.B)
        dut.clock.step(depth + 3)
        dut.io.resp.valid.peek().litToBoolean should be(false)
        // flush 后重新发一笔加法，确认流水级和状态机都能继续工作。
        val afterFlush = send(
          dut, 64, FAdd, S, RNE,
          boxS(BigInt(0x3fc00000L)), boxS(BigInt(0x40100000L)))
        afterFlush should be((boxS(BigInt(0x40700000L)), 0))
    }
  }

  "FPU_PIPELINE" should "在 1..8 级都保持结果、反压与 flush 语义" in {
    for (depth <- 1 to 8) {
      withClue(s"pipeline=$depth: ") {
        pipelineBackpressureAndFlush(depth)
      }
    }
  }

  "FPU flush" should "丢弃在途 DIV 并在后续请求上恢复" in {
    def issueDivAndFlush(dut: ysyx_26030103_FPU, fmt: Int): Unit = {
      waitUntil(dut, dut.io.req.ready.peek().litToBoolean)
      dut.io.req.bits.op.poke(FDiv.U)
      dut.io.req.bits.fmt.poke(fmt.U)
      dut.io.req.bits.srcFmt.poke(fmt.U)
      dut.io.req.bits.rm.poke(RNE.U)
      dut.io.req.bits.intWord.poke(true.B)
      dut.io.req.bits.rs1.poke(
        (if (fmt == S) boxS(BigInt(0x3f800000L))
         else BigInt("3ff0000000000000", 16)).U(64.W))
      dut.io.req.bits.rs2.poke(
        (if (fmt == S) boxS(BigInt(0x40400000L))
         else BigInt("4008000000000000", 16)).U(64.W))
      dut.io.req.bits.rs3.poke(0.U(64.W))
      dut.io.req.valid.poke(true.B)
      dut.clock.step()
      dut.io.req.valid.poke(false.B)
      dut.io.req.ready.peek().litToBoolean should be(false)
      dut.clock.step(5)
      dut.io.flush.poke(true.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)
      dut.clock.step(20)
      dut.io.resp.valid.peek().litToBoolean should be(false)
      val after = send(
        dut, 64, FAdd, S, RNE,
        boxS(BigInt(0x3fc00000L)), boxS(BigInt(0x40100000L)))
      after should be((boxS(BigInt(0x40700000L)), 0))
    }

    simulate(new ysyx_26030103_FudianFPU(
      fudianConfig(fma = true, pipeline = 2, shared = true))) { dut =>
      initDut(dut)
      issueDivAndFlush(dut, S)
      issueDivAndFlush(dut, D)
    }
    simulate(new ysyx_26030103_HardFloatFPU(hardfloatConfig(fma = true))) {
      dut =>
        initDut(dut)
        issueDivAndFlush(dut, S)
        issueDivAndFlush(dut, D)
    }
  }

  // ------------------------------------------------------------------
  // Fudian 共享 DIV/SQRT
  // ------------------------------------------------------------------
  private def randomSFinite(rng: scala.util.Random): BigInt = {
    // 偏向正常数/次正规数/零，偶尔 inf/NaN 由定向用例覆盖。
    val kind = rng.nextInt(10)
    if (kind == 0) BigInt(0)
    else if (kind == 1) BigInt(rng.nextInt(1 << 23)) // 次正规
    else if (kind == 2) BigInt(0x00800000L + rng.nextInt(1 << 23))
    else bits32(BigInt(rng.nextInt()) & 0xffffffffL)
  }

  private def randomDFinite(rng: scala.util.Random): BigInt = {
    val hi = BigInt(rng.nextInt()) & 0xffffffffL
    val lo = BigInt(rng.nextInt()) & 0xffffffffL
    val bits = (hi << 32) | lo
    if (rng.nextInt(8) == 0) bits & BigInt("800fffffffffffff", 16)
    else bits
  }

  private case class DivCase(op: BigInt, fmt: Int, rm: Int, a: BigInt, b: BigInt)
  private def makeDivCases(): Seq[DivCase] = {
    val rng = new scala.util.Random(0x1234)
    val out = ArrayBuffer[DivCase]()
    val specialsS = Seq(
      boxS(0), boxS(0x80000000L), boxS(0x7f800000L), boxS(0xff800000L),
      boxS(0x7fc00000L), boxS(0x7f800001L), boxS(0x00800000L),
      boxS(0x00000001L), boxS(0x7f7fffffL), boxS(0x3f800000L),
      boxS(0x40400000L)
    )
    for (rm <- 0 to 4; a <- specialsS; b <- specialsS.take(5)) {
      out += DivCase(FDiv, S, rm, a, b)
    }
    for (rm <- 0 to 4; a <- specialsS.take(6)) {
      out += DivCase(FSqrt, S, rm, a, a)
    }
    var i = 0
    while (i < 120) {
      val a = boxS(randomSFinite(rng))
      val b = boxS(randomSFinite(rng))
      out += DivCase(FDiv, S, i % 5, a, b)
      i += 1
    }
    i = 0
    while (i < 60) {
      out += DivCase(FSqrt, S, i % 5, boxS(randomSFinite(rng)), 0)
      i += 1
    }
    val specialsD = Seq(
      BigInt(0), BigInt("8000000000000000", 16),
      BigInt("7ff0000000000000", 16), BigInt("fff0000000000000", 16),
      BigInt("7ff8000000000000", 16), BigInt("0010000000000000", 16),
      BigInt("0000000000000001", 16), BigInt("7fefffffffffffff", 16),
      BigInt("3ff0000000000000", 16), BigInt("4008000000000000", 16)
    )
    for (rm <- 0 to 4; a <- specialsD; b <- specialsD.take(4)) {
      out += DivCase(FDiv, D, rm, a, b)
    }
    for (rm <- 0 to 4; a <- specialsD.take(6)) {
      out += DivCase(FSqrt, D, rm, a, a)
    }
    i = 0
    while (i < 40) {
      out += DivCase(FDiv, D, i % 5, randomDFinite(rng), randomDFinite(rng))
      i += 1
    }
    i = 0
    while (i < 30) {
      out += DivCase(FSqrt, D, i % 5, randomDFinite(rng), 0)
      i += 1
    }
    out.toSeq
  }

  private def checkDivCase(c: DivCase, got: (BigInt, Int)): Unit = {
    val (wantR, wantF) =
      if (c.op == FDiv) {
        if (c.fmt == S) {
          val (r, f) = F32.divide(bits32(c.a), bits32(c.b), c.rm)
          (boxS(r), f)
        } else {
          F64.divide(c.a, c.b, c.rm)
        }
      } else {
        if (c.fmt == S) {
          val (r, f) = F32.sqrt(bits32(c.a), c.rm)
          (boxS(r), f)
        } else {
          F64.sqrt(c.a, c.rm)
        }
      }
    withClue(s"op=${c.op} fmt=${c.fmt} rm=${c.rm} a=${c.a.toString(16)} b=${c.b.toString(16)}: ") {
      got should be((wantR, wantF))
    }
  }

  "Fudian 共享 DIV/SQRT" should "与独立 F/D 引擎及精确 oracle 一致" in {
    val cases = makeDivCases()
    val sharedResults = ArrayBuffer[(BigInt, Int)]()
    simulate(new ysyx_26030103_FudianFPU(fudianConfig(fma = true, shared = true))) {
      dut =>
        initDut(dut)
        cases.foreach { c =>
          val got = send(dut, 64, c.op, c.fmt, c.rm, c.a, c.b)
          checkDivCase(c, got)
          sharedResults += got
        }
    }
    val dedicatedResults = ArrayBuffer[(BigInt, Int)]()
    simulate(new ysyx_26030103_FudianFPU(fudianConfig(fma = true, shared = false))) {
      dut =>
        initDut(dut)
        cases.foreach { c =>
          dedicatedResults += send(dut, 64, c.op, c.fmt, c.rm, c.a, c.b)
        }
    }
    sharedResults should be(dedicatedResults)
  }

  // ------------------------------------------------------------------
  // 生成 RTL 结构区分
  // ------------------------------------------------------------------
  "FPU RTL" should "区分 fused/exact FMA 与 shared/dedicated FDIV" in {
    val hfFused = ChiselStage.emitCHIRRTL(
      new ysyx_26030103_HardFloatFPU(hardfloatConfig(fma = true)))
    hfFused should include("MulAddRecFN")
    hfFused should not include "ysyx_26030103_ExactFMA"

    val hfExact = ChiselStage.emitCHIRRTL(
      new ysyx_26030103_HardFloatFPU(hardfloatConfig(fma = false)))
    hfExact should include("ysyx_26030103_ExactFMA")
    hfExact should not include "MulAddRecFN"

    val fdFused = ChiselStage.emitCHIRRTL(
      new ysyx_26030103_FudianFPU(fudianConfig(fma = true, shared = false)))
    fdFused should include("module FCMA :")
    fdFused should not include "ysyx_26030103_ExactFMA"
    fdFused should include("sDivDedicatedDivSqrt")
    fdFused should include("dDivDedicatedDivSqrt")

    val fdExactShared = ChiselStage.emitCHIRRTL(
      new ysyx_26030103_FudianFPU(fudianConfig(fma = false, shared = true)))
    fdExactShared should include("ysyx_26030103_ExactFMA")
    fdExactShared should not include "module FCMA :"
    fdExactShared should include("dDivSharedDivSqrt")
    fdExactShared should not include "sDivDedicatedDivSqrt"
  }
}
