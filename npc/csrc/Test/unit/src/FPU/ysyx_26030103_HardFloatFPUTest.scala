package ysyx_26030103.fpu

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.ysyx_26030103.config._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Berkeley HardFloat 适配层的定向单元测试。
  *
  * 覆盖 IEEE 边界、NaN/sNaN、全部 FMA 符号组合、除法/开方、舍入、
  * 异常标志、整数/精度转换、NaN-boxing、FMIN/FMAX/FCLASS。
  */
class ysyx_26030103_HardFloatFPUTest extends AnyFlatSpec with Matchers {
  private val UseD = true
  private def config(useD: Boolean = UseD): ysyx_26030103_NPCConfig =
    ysyx_26030103_NPCConfig(
      XLEN = 64,
      AddressWidth = 64,
      UseF = true,
      UseD = useD,
      FPUImpl = ysyx_26030103_FPUImpl.HardFloat,
      FPULen = if (useD) 64 else 32
    )

  private def opOf(op: UInt): BigInt = op.litValue
  private def fadd = opOf(ysyx_26030103_FpuOp.FAdd)
  private def fsub = opOf(ysyx_26030103_FpuOp.FSub)
  private def fmul = opOf(ysyx_26030103_FpuOp.FMul)
  private def fdiv = opOf(ysyx_26030103_FpuOp.FDiv)
  private def fsqrt = opOf(ysyx_26030103_FpuOp.FSqrt)
  private def ffma = opOf(ysyx_26030103_FpuOp.FFma)
  private def ffms = opOf(ysyx_26030103_FpuOp.FFms)
  private def ffnma = opOf(ysyx_26030103_FpuOp.FFnma)
  private def ffnms = opOf(ysyx_26030103_FpuOp.FFnms)
  private def fsgnj = opOf(ysyx_26030103_FpuOp.FSgnj)
  private def fsgnjn = opOf(ysyx_26030103_FpuOp.FSgnjn)
  private def fsgnjx = opOf(ysyx_26030103_FpuOp.FSgnjx)
  private def fmin = opOf(ysyx_26030103_FpuOp.FMin)
  private def fmax = opOf(ysyx_26030103_FpuOp.FMax)
  private def fclass = opOf(ysyx_26030103_FpuOp.FClass)
  private def feq = opOf(ysyx_26030103_FpuOp.FEq)
  private def flt = opOf(ysyx_26030103_FpuOp.FLt)
  private def fle = opOf(ysyx_26030103_FpuOp.FLe)
  private def cvtI = opOf(ysyx_26030103_FpuOp.FCvtFpToInt)
  private def cvtU = opOf(ysyx_26030103_FpuOp.FCvtFpToUInt)
  private def cvtF = opOf(ysyx_26030103_FpuOp.FCvtIntToFp)
  private def cvtFU = opOf(ysyx_26030103_FpuOp.FCvtUIntToFp)
  private def cvtFF = opOf(ysyx_26030103_FpuOp.FCvtFpToFp)
  private def mvFpInt = opOf(ysyx_26030103_FpuOp.FMvFpToInt)
  private def mvIntFp = opOf(ysyx_26030103_FpuOp.FMvIntToFp)
  private val S = ysyx_26030103_FpuFmt.S.litValue
  private val D = ysyx_26030103_FpuFmt.D.litValue
  private val RNE = 0
  private val RTZ = 1
  private val RDN = 2
  private val RUP = 3
  private val RMM = 4

  private def exec(
      op: BigInt,
      fmt: BigInt,
      srcFmt: BigInt,
      rm: Int,
      a: BigInt,
      b: BigInt = 0,
      c: BigInt = 0,
      intWord: Boolean = true,
      useD: Boolean = UseD
  ): (BigInt, Int) = {
    var result = BigInt(0)
    var flags = 0
    simulate(new ysyx_26030103_HardFloatFPU(config(useD))) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.flush.poke(false.B)
      dut.io.resp.ready.poke(true.B)
      dut.io.req.valid.poke(true.B)
      dut.io.req.bits.op.poke(op.U)
      dut.io.req.bits.fmt.poke(fmt.U)
      dut.io.req.bits.srcFmt.poke(srcFmt.U)
      dut.io.req.bits.rm.poke(rm.U)
      dut.io.req.bits.intWord.poke(intWord.B)
      dut.io.req.bits.rs1.poke(a.U(64.W))
      dut.io.req.bits.rs2.poke(b.U(64.W))
      dut.io.req.bits.rs3.poke(c.U(64.W))
      var cycles = 0
      while (!dut.io.resp.valid.peek().litToBoolean && cycles < 2000) {
        dut.clock.step()
        cycles += 1
      }
      withClue(s"op=$op fmt=$fmt 未在 2000 拍内返回") {
        dut.io.resp.valid.peek().litToBoolean should be(true)
      }
      result = dut.io.resp.bits.result.peek().litValue
      flags = dut.io.resp.bits.fflags.peek().litValue.toInt
      dut.io.req.valid.poke(false.B)
      dut.clock.step()
    }
    (result, flags)
  }

  private def f32(x: BigInt): BigInt = x & 0xffffffffL
  private def f64(x: BigInt): BigInt = x & BigInt("ffffffffffffffff", 16)
  private def boxS(x: BigInt): BigInt = (BigInt("ffffffff", 16) << 32) | f32(x)

  "HardFloatFPU" should "执行单精度加减乘" in {
    exec(fadd, S, S, RNE, boxS(BigInt("3fc00000", 16)),
      boxS(BigInt("40100000", 16))) should be(
      (boxS(BigInt("40700000", 16)), 0)) // 3.75
    exec(fsub, S, S, RNE, boxS(BigInt("3f800000", 16)),
      boxS(BigInt("3f000000", 16))) should be(
      (boxS(BigInt("3f000000", 16)), 0)) // 0.5
    exec(fmul, S, S, RNE, boxS(BigInt("c0000000", 16)),
      boxS(BigInt("40400000", 16))) should be(
      (boxS(BigInt("c0c00000", 16)), 0)) // -6
  }

  it should "执行双精度除法并设置 NX" in {
    val (r, f) =
      exec(fdiv, D, D, RNE, f64(BigInt("3ff0000000000000", 16)),
        f64(BigInt("4008000000000000", 16))) // 1/3
    r should be(BigInt("3fd5555555555555", 16))
    (f & 0x1f) should be(0x01) // NX
  }

  it should "执行双精度开方" in {
    val (r, f) = exec(fsqrt, D, D, RNE,
      f64(BigInt("4000000000000000", 16))) // sqrt(2)
    r should be(BigInt("3ff6a09e667f3bcd", 16))
    (f & 0x1f) should be(0x01)
  }

  it should "实现全部 FMA 符号组合（单精度）" in {
    val a = boxS(BigInt("40000000", 16)) // 2
    val b = boxS(BigInt("40400000", 16)) // 3
    val c = boxS(BigInt("40800000", 16)) // 4
    exec(ffma, S, S, RNE, a, b, c)._1 should be(boxS(BigInt("41200000", 16))) // 10
    exec(ffms, S, S, RNE, a, b, c)._1 should be(boxS(BigInt("40000000", 16))) // 2
    exec(ffnma, S, S, RNE, a, b, c)._1 should be(boxS(BigInt("c1200000", 16))) // -10
    exec(ffnms, S, S, RNE, a, b, c)._1 should be(boxS(BigInt("c0000000", 16))) // -2
  }

  it should "实现 FMA 负零/舍入边界" in {
    // (-0) * 1 + (-0) = -0（RDN 必须保持负号）
    val (r, _) = exec(ffma, S, S, RDN, boxS(BigInt("80000000", 16)),
      boxS(BigInt("3f800000", 16)), boxS(BigInt("80000000", 16)))
    r should be(boxS(BigInt("80000000", 16)))
  }

  it should "产生 canonical NaN 并置 NV" in {
    val inf = boxS(BigInt("7f800000", 16))
    val ninf = boxS(BigInt("ff800000", 16))
    val (r, f) = exec(fadd, S, S, RNE, inf, ninf)
    r should be(boxS(BigInt("7fc00000", 16)))
    (f & 0x10) should be(0x10)
  }

  it should "区分 sNaN 比较与 quiet FEQ" in {
    val snan = boxS(BigInt("7f800001", 16))
    val qnan = boxS(BigInt("7fc00000", 16))
    val one = boxS(BigInt("3f800000", 16))
    val (_, fltFlags) = exec(flt, S, S, RNE, one, snan)
    (fltFlags & 0x10) should be(0x10)
    val (eqr, eqFlags) = exec(feq, S, S, RNE, qnan, qnan)
    eqr should be(0)
    (eqFlags & 0x10) should be(0)
  }

  it should "实现 FSGNJ 系列" in {
    val a = boxS(BigInt("bfc00000", 16)) // -1.5
    val b = boxS(BigInt("40200000", 16)) // 2.5
    exec(fsgnj, S, S, RNE, a, b)._1 should be(
      boxS(BigInt("3fc00000", 16))) // +1.5
    exec(fsgnjn, S, S, RNE, a, b)._1 should be(
      boxS(BigInt("bfc00000", 16))) // -1.5
    exec(fsgnjx, S, S, RNE, a, b)._1 should be(
      boxS(BigInt("bfc00000", 16))) // -1.5
  }

  it should "实现 FMIN/FMAX 的 NaN 与 ±0 语义" in {
    val pz = boxS(0)
    val nz = boxS(BigInt("80000000", 16))
    exec(fmin, S, S, RNE, pz, nz)._1 should be(nz)
    exec(fmax, S, S, RNE, pz, nz)._1 should be(pz)
    val nan = boxS(BigInt("7fc00000", 16))
    val three = boxS(BigInt("40400000", 16))
    exec(fmin, S, S, RNE, nan, three)._1 should be(three)
    exec(fmax, S, S, RNE, nan, three)._1 should be(three)
    exec(fmax, S, S, RNE, nan, nan)._1 should be(boxS(BigInt("7fc00000", 16)))
  }

  it should "实现 FCLASS" in {
    exec(fclass, S, S, RNE, boxS(BigInt("7f800000", 16)), intWord = false)._1 should be(
      BigInt(0x80))
    exec(fclass, S, S, RNE, boxS(BigInt("80000000", 16)), intWord = false)._1 should be(
      BigInt(0x08))
    exec(fclass, S, S, RNE, boxS(BigInt("7f800001", 16)), intWord = false)._1 should be(
      BigInt(0x100))
  }

  it should "实现 FCVT 浮点->整数（含饱和与标志）" in {
    exec(cvtI, S, S, RTZ, boxS(BigInt("40700000", 16)), intWord = true) should be(
      (BigInt(3), 0x01)) // NX
    exec(cvtU, S, S, RTZ, boxS(BigInt("bf800000", 16)), intWord = true) should be(
      (BigInt(0), 0x10)) // NV
    // RV64 的 64 位无符号转换：-1.0 必须裁剪到 0 并只报 NV。
    exec(cvtU, D, D, RTZ, f64(BigInt("bff0000000000000", 16)),
      intWord = false) should be((BigInt(0), 0x10))
    exec(cvtI, D, D, RTZ, f64(BigInt("bff0000000000000", 16)),
      intWord = false) should be((BigInt("ffffffffffffffff", 16), 0))
    val (ov, of) = exec(cvtI, S, S, RNE, boxS(BigInt("4f800000", 16)), intWord = true)
    ov should be(BigInt("7fffffff", 16))
    (of & 0x10) should be(0x10)
    // RV64 的 FCVT.L.D 使用完整 64 位整数宽度：-2^63 可表示。
    exec(cvtI, D, D, RNE, f64(BigInt("c3e0000000000000", 16)),
      intWord = false)._1 should be(BigInt(1) << 63)
  }

  it should "实现 FCVT 整数->浮点（W/L、有/无符号）" in {
    val negOne = BigInt("ffffffffffffffff", 16)
    exec(cvtF, S, S, RNE, negOne, intWord = true)._1 should be(
      boxS(BigInt("bf800000", 16)))
    exec(cvtFU, S, S, RNE, BigInt("ffffffff", 16), intWord = true)._1 should be(
      boxS(BigInt("4f800000", 16))) // 2^32
    // 2^32-1 在 double 中可精确表示；float 中会舍入到 2^32。
    exec(cvtFU, D, D, RNE, BigInt("ffffffff", 16), intWord = true)._1 should be(
      f64(BigInt("41efffffffe00000", 16)))
  }

  it should "实现 FCVT.S.D / FCVT.D.S" in {
    exec(cvtFF, D, S, RNE, boxS(BigInt("3fc00000", 16))) should be(
      (f64(BigInt("3ff8000000000000", 16)), 0))
    exec(cvtFF, S, D, RNE, f64(BigInt("3ff8000000000000", 16))) should be(
      (boxS(BigInt("3fc00000", 16)), 0))
    // D->S 丢精度必须置 NX。
    val (_, f) = exec(cvtFF, S, D, RNE, f64(BigInt("3ff8000000000001", 16)))
    (f & 0x1f) should be(0x01)
  }

  it should "实现 FMV 的 NaN-boxing 与整数宽度" in {
    // FMV.X.W 把低 32 位按 bit31 符号扩展；FMV.X.D 保留完整 64 位。
    val raw = BigInt("ffffffff80000001", 16)
    exec(mvFpInt, S, S, RNE, raw, intWord = true)._1 should be(
      BigInt("ffffffff80000001", 16))
    exec(mvIntFp, S, S, RNE, BigInt("7fc00001", 16), intWord = true)._1 should be(
      boxS(BigInt("7fc00001", 16)))
    exec(mvIntFp, D, D, RNE, BigInt("7ff8000000000001", 16),
      intWord = false)._1 should be(BigInt("7ff8000000000001", 16))
  }

  it should "对未 NaN-box 的单精度输入按 canonical NaN 处理" in {
    val unboxed = BigInt("000000003f800000", 16) // 上 32 位不是全 1
    val (r, _) = exec(fadd, S, S, RNE, unboxed,
      boxS(BigInt("3f800000", 16)))
    r should be(boxS(BigInt("7fc00000", 16)))
  }

  it should "尊重 RTZ/RDN/RUP/RMM 舍入模式" in {
    val one = boxS(BigInt("3f800000", 16))
    val three = boxS(BigInt("40400000", 16))
    val rne = exec(fdiv, S, S, RNE, one, three)._1
    val rtz = exec(fdiv, S, S, RTZ, one, three)._1
    val rdn = exec(fdiv, S, S, RDN, one, three)._1
    val rup = exec(fdiv, S, S, RUP, one, three)._1
    val rmm = exec(fdiv, S, S, RMM, one, three)._1
    rne should be(boxS(BigInt("3eaaaaab", 16)))
    rtz should be(boxS(BigInt("3eaaaaaa", 16)))
    rdn should be(boxS(BigInt("3eaaaaaa", 16)))
    rup should be(boxS(BigInt("3eaaaaab", 16)))
    rmm should be(boxS(BigInt("3eaaaaab", 16)))
  }

  it should "置 DZ/OF/UF/NX 标志" in {
    val one = boxS(BigInt("3f800000", 16))
    exec(fdiv, S, S, RNE, one, boxS(0))._2 should be(0x08) // DZ
    val maxf = boxS(BigInt("7f7fffff", 16))
    val (ov, of) = exec(fmul, S, S, RNE, maxf, boxS(BigInt("40000000", 16)))
    ov should be(boxS(BigInt("7f800000", 16)))
    (of & 0x05) should be(0x05) // OF|NX
    // 最小正规数 * 0.5 = 精确次正规数；tininess after rounding 时不置 UF。
    val minNorm = boxS(BigInt("00800000", 16))
    val (sub, uf) = exec(fmul, S, S, RNE, minNorm, boxS(BigInt("3f000000", 16)))
    sub should be(boxS(BigInt("00400000", 16)))
    (uf & 0x02) should be(0)
  }
}
