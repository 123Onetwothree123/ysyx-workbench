package ysyx_26030103.fpu

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.circt.stage.ChiselStage
import _root_.ysyx_26030103.config._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Fudian 后端的定向单元测试。
  *
  * 覆盖组合运算、NaN/fflags/舍入、整数/精度转换，以及 FDIV/FSQRT 的
  * 多周期 valid/ready 握手与反压。测试只经过统一 FPU 接口，不依赖
  * Fudian 内部 Bundle。
  *
  * 为了缩短 Verilator 构建时间，同一格式的组合运算集中在一个仿真块中；
  * 每个测试块仍从复位开始，并独立检查全部断言。
  */
class ysyx_26030103_FudianFPUTest extends AnyFlatSpec with Matchers {

  private implicit class UIntToInt(private val value: UInt) {
    def Int: Int = value.litValue.toInt
  }

  private val NX = BigInt(1)
  private val UF = BigInt(2)
  private val OF = BigInt(4)
  private val DZ = BigInt(8)
  private val NV = BigInt(16)

  private def config32: ysyx_26030103_NPCConfig =
    ysyx_26030103_NPCConfig(
      XLEN = 32,
      UseF = true,
      FPUImpl = ysyx_26030103_FPUImpl.Fudian,
      FPULen = 32
    )

  private def config64: ysyx_26030103_NPCConfig =
    ysyx_26030103_NPCConfig(
      XLEN = 64,
      AddressWidth = 64,
      UseF = true,
      UseD = true,
      FPUImpl = ysyx_26030103_FPUImpl.Fudian,
      FPULen = 64
    )

  private def f32(x: Float): BigInt =
    BigInt(java.lang.Float.floatToRawIntBits(x).toLong & 0xffffffffL)

  private def f64(x: Double): BigInt =
    BigInt(java.lang.Double.doubleToRawLongBits(x)) & ((BigInt(1) << 64) - 1)

  private val Box: BigInt = BigInt(0xffffffffL) << 32

  private def box(x: BigInt): BigInt = Box | (x & 0xffffffffL)

  private val CanonicalNaN32: BigInt = BigInt(0x7fc00000L)
  private val BoxedNaN32: BigInt = Box | CanonicalNaN32
  private val QNaN32: BigInt = BigInt(0x7fc00001L)
  private val SNaN32: BigInt = BigInt(0x7f800001L)
  private val Inf32: BigInt = BigInt(0x7f800000L)

  private def waitUntil(
      dut: ysyx_26030103_FudianFPU,
      cond: => Boolean,
      maxCycles: Int = 600
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
      dut: ysyx_26030103_FudianFPU,
      flen: Int,
      op: Int,
      fmt: Int,
      rm: Int,
      rs1: BigInt,
      rs2: BigInt = 0,
      rs3: BigInt = 0,
      intWord: Boolean = false
  ): (BigInt, BigInt) = {
    waitUntil(dut, dut.io.req.ready.peek().litToBoolean)
    dut.io.req.bits.op.poke(op.U(ysyx_26030103_FpuOp.Width.W))
    dut.io.req.bits.fmt.poke(fmt.U(ysyx_26030103_FpuFmt.Width.W))
    dut.io.req.bits.rm.poke(rm.U(ysyx_26030103_FpuRm.Width.W))
    dut.io.req.bits.intWord.poke(intWord.B)
    dut.io.req.bits.rs1.poke(rs1.U(flen.W))
    dut.io.req.bits.rs2.poke(rs2.U(flen.W))
    dut.io.req.bits.rs3.poke(rs3.U(flen.W))
    dut.io.req.valid.poke(true.B)
    dut.clock.step()
    dut.io.req.valid.poke(false.B)

    waitUntil(dut, dut.io.resp.valid.peek().litToBoolean)
    val result = dut.io.resp.bits.result.peek().litValue
    val fflags = dut.io.resp.bits.fflags.peek().litValue

    dut.io.resp.ready.poke(true.B)
    dut.clock.step()
    dut.io.resp.ready.poke(false.B)
    (result, fflags)
  }

  private def sim64(body: ysyx_26030103_FudianFPU => Unit): Unit =
    simulate(new ysyx_26030103_FudianFPU(config64)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.req.valid.poke(false.B)
      dut.io.resp.ready.poke(false.B)
      body(dut)
    }

  private def sim32(body: ysyx_26030103_FudianFPU => Unit): Unit =
    simulate(new ysyx_26030103_FudianFPU(config32)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.req.valid.poke(false.B)
      dut.io.resp.ready.poke(false.B)
      body(dut)
    }

  it should "通过统一工厂创建具体后端" in {
    val chirrtl = ChiselStage.emitCHIRRTL(ysyx_26030103_FPU(config64))
    chirrtl should include("ysyx_26030103_FudianFPU")
  }

  it should "支持 F-only 宽度组合与 SharedDivSqrt 配置的 elaboration" in {
    val configs = Seq(
      // FLEN=64 的 F-only（不启用 D）。
      ysyx_26030103_NPCConfig(
        XLEN = 32,
        UseF = true,
        FPUImpl = ysyx_26030103_FPUImpl.Fudian,
        FPULen = 64
      ),
      // RV64 但只启用 F、浮点寄存器宽度 32。
      ysyx_26030103_NPCConfig(
        XLEN = 64,
        AddressWidth = 64,
        UseF = true,
        FPUImpl = ysyx_26030103_FPUImpl.Fudian,
        FPULen = 32
      ),
      // 公开 profile 参数关闭时仍可 elaboration（见类注释的限制）。
      ysyx_26030103_NPCConfig(
        XLEN = 64,
        AddressWidth = 64,
        UseF = true,
        UseD = true,
        FPUImpl = ysyx_26030103_FPUImpl.Fudian,
        FPUProfile = ysyx_26030103_FPUProfile(
          Fudian = ysyx_26030103_FudianParams(SharedDivSqrt = false)
        )
      )
    )
    configs.foreach { config =>
      ChiselStage.emitCHIRRTL(new ysyx_26030103_FudianFPU(config)) should
        include("ysyx_26030103_FudianFPU")
    }
  }

  it should "实现 F/D 组合运算、舍入、NaN、fflags 与转换" in {
    sim64 { dut =>
      val one = box(f32(1.0f))
      val two = box(f32(2.0f))
      val three = box(f32(3.0f))
      val four = box(f32(4.0f))

      withClue("FADD/FSUB/FMUL: ") {
        send(dut, 64, ysyx_26030103_FpuOp.FAdd.Int, 0, 0,
          box(f32(1.5f)), box(f32(2.25f))) should be((box(f32(3.75f)), 0))
        send(dut, 64, ysyx_26030103_FpuOp.FAdd.Int, 1, 0,
          f64(1.5), f64(2.25)) should be((f64(3.75), 0))
        send(dut, 64, ysyx_26030103_FpuOp.FSub.Int, 1, 0,
          f64(1.0), f64(2.0)) should be((f64(-1.0), 0))
        send(dut, 64, ysyx_26030103_FpuOp.FMul.Int, 0, 0,
          box(f32(1.5f)), two) should be((box(f32(3.0f)), 0))
        send(dut, 64, ysyx_26030103_FpuOp.FMul.Int, 1, 0,
          f64(-1.5), f64(4.0)) should be((f64(-6.0), 0))
      }

      withClue("舍入模式: ") {
        // 1.0 + 2^-24 恰好落在两个 S 数中间。
        val half = f32(math.pow(2.0, -24.0).toFloat)
        val up = f32(1.0f + math.pow(2.0, -23.0).toFloat)
        send(dut, 64, ysyx_26030103_FpuOp.FAdd.Int, 0,
          ysyx_26030103_FpuRm.RNE.Int, one, box(half)) should be(
          (one, NX))
        send(dut, 64, ysyx_26030103_FpuOp.FAdd.Int, 0,
          ysyx_26030103_FpuRm.RMM.Int, one, box(half)) should be(
          (box(up), NX))
        send(dut, 64, ysyx_26030103_FpuOp.FAdd.Int, 0,
          ysyx_26030103_FpuRm.RUP.Int, one, box(half)) should be(
          (box(up), NX))
        send(dut, 64, ysyx_26030103_FpuOp.FAdd.Int, 0,
          ysyx_26030103_FpuRm.RDN.Int, one, box(half)) should be((one, NX))
        send(dut, 64, ysyx_26030103_FpuOp.FAdd.Int, 0,
          ysyx_26030103_FpuRm.RTZ.Int, one, box(half)) should be((one, NX))
        // D 格式 tie-to-even。
        val oneD = f64(1.0)
        val halfD = BigInt("3ca0000000000000", 16)
        send(dut, 64, ysyx_26030103_FpuOp.FAdd.Int, 1,
          ysyx_26030103_FpuRm.RNE.Int, oneD, halfD) should be((oneD, NX))
        send(dut, 64, ysyx_26030103_FpuOp.FAdd.Int, 1,
          ysyx_26030103_FpuRm.RMM.Int, oneD, halfD) should be(
          (BigInt("3ff0000000000001", 16), NX))
        // DYN 在 wrapper 内按 RNE 处理（CPU 集成前必须先解析 frm）。
        send(dut, 64, ysyx_26030103_FpuOp.FAdd.Int, 0,
          ysyx_26030103_FpuRm.DYN.Int, one, box(half)) should be((one, NX))
      }

      withClue("FMA 符号: ") {
        send(dut, 64, ysyx_26030103_FpuOp.FFma.Int, 0, 0,
          two, three, four) should be((box(f32(10.0f)), 0))
        send(dut, 64, ysyx_26030103_FpuOp.FFms.Int, 0, 0,
          two, three, four) should be((box(f32(2.0f)), 0))
        send(dut, 64, ysyx_26030103_FpuOp.FFnma.Int, 0, 0,
          two, three, four) should be((box(f32(-10.0f)), 0))
        send(dut, 64, ysyx_26030103_FpuOp.FFnms.Int, 0, 0,
          two, three, four) should be((box(f32(-2.0f)), 0))
        send(dut, 64, ysyx_26030103_FpuOp.FFma.Int, 1, 0,
          f64(2.0), f64(3.0), f64(-4.0)) should be((f64(2.0), 0))
      }

      withClue("FMIN/FMAX: ") {
        val negZero = box(f32(-0.0f))
        val posZero = box(f32(0.0f))
        send(dut, 64, ysyx_26030103_FpuOp.FMin.Int, 0, 0,
          negZero, posZero) should be((negZero, 0))
        send(dut, 64, ysyx_26030103_FpuOp.FMax.Int, 0, 0,
          negZero, posZero) should be((posZero, 0))
        send(dut, 64, ysyx_26030103_FpuOp.FMin.Int, 0, 0,
          box(QNaN32), two) should be((two, 0))
        send(dut, 64, ysyx_26030103_FpuOp.FMin.Int, 0, 0,
          box(SNaN32), two) should be((two, NV))
        send(dut, 64, ysyx_26030103_FpuOp.FMin.Int, 0, 0,
          box(QNaN32), box(SNaN32)) should be((BoxedNaN32, NV))
      }

      withClue("FSGNJ/FCLASS: ") {
        val negB = box(f32(-2.0f))
        send(dut, 64, ysyx_26030103_FpuOp.FSgnj.Int, 0, 0,
          one, negB) should be((box(f32(-1.0f)), 0))
        send(dut, 64, ysyx_26030103_FpuOp.FSgnjn.Int, 0, 0,
          one, two) should be((box(f32(-1.0f)), 0))
        send(dut, 64, ysyx_26030103_FpuOp.FSgnjx.Int, 0, 0,
          box(f32(-1.0f)), negB) should be((box(f32(1.0f)), 0))

        def cls(bits: BigInt): BigInt =
          send(dut, 64, ysyx_26030103_FpuOp.FClass.Int, 0, 0, box(bits))._1

        cls(BigInt(0xff800000L)) should be(BigInt(1))
        cls(box(f32(-1.0f))) should be(BigInt(2))
        cls(box(f32(-1.0e-40f))) should be(BigInt(4))
        cls(box(f32(-0.0f))) should be(BigInt(8))
        cls(box(f32(0.0f))) should be(BigInt(16))
        cls(box(f32(1.0e-40f))) should be(BigInt(32))
        cls(box(f32(1.0f))) should be(BigInt(64))
        cls(box(Inf32)) should be(BigInt(128))
        cls(box(SNaN32)) should be(BigInt(256))
        cls(box(QNaN32)) should be(BigInt(512))
      }

      withClue("FEQ/FLT/FLE: ") {
        send(dut, 64, ysyx_26030103_FpuOp.FEq.Int, 0, 0,
          one, two) should be((BigInt(0), 0))
        send(dut, 64, ysyx_26030103_FpuOp.FLt.Int, 0, 0,
          one, two) should be((BigInt(1), 0))
        send(dut, 64, ysyx_26030103_FpuOp.FLe.Int, 0, 0,
          one, two) should be((BigInt(1), 0))
        send(dut, 64, ysyx_26030103_FpuOp.FLe.Int, 1, 0,
          f64(2.0), f64(2.0)) should be((BigInt(1), 0))
        send(dut, 64, ysyx_26030103_FpuOp.FEq.Int, 0, 0,
          box(QNaN32), one) should be((BigInt(0), 0))
        send(dut, 64, ysyx_26030103_FpuOp.FEq.Int, 0, 0,
          box(SNaN32), one) should be((BigInt(0), NV))
      }

      withClue("NaN 与标志: ") {
        send(dut, 64, ysyx_26030103_FpuOp.FAdd.Int, 0, 0,
          one, box(SNaN32)) should be((BoxedNaN32, NV))
        send(dut, 64, ysyx_26030103_FpuOp.FAdd.Int, 0, 0,
          one, box(QNaN32)) should be((BoxedNaN32, 0))
        // 未 NaN-box 的 S 输入按规范视为规范 NaN。
        send(dut, 64, ysyx_26030103_FpuOp.FAdd.Int, 0, 0,
          one, BigInt(0x3f800000L)) should be((BoxedNaN32, 0))
        send(dut, 64, ysyx_26030103_FpuOp.FMul.Int, 0, 0,
          box(f32(0.0f)), box(Inf32)) should be((BoxedNaN32, NV))
        send(dut, 64, ysyx_26030103_FpuOp.FMul.Int, 0, 0,
          box(f32(java.lang.Float.MAX_VALUE)), two) should be(
          (box(Inf32), OF | NX))
      }

      withClue("FCVT: ") {
        send(dut, 64, ysyx_26030103_FpuOp.FCvtIntToFp.Int, 0,
          ysyx_26030103_FpuRm.RNE.Int, 42) should be((box(f32(42.0f)), 0))
        send(dut, 64, ysyx_26030103_FpuOp.FCvtFpToInt.Int, 0,
          ysyx_26030103_FpuRm.RTZ.Int, box(f32(42.5f))) should be((
          BigInt(42), NX))
        send(dut, 64, ysyx_26030103_FpuOp.FCvtFpToInt.Int, 0,
          ysyx_26030103_FpuRm.RTZ.Int, box(f32(-42.5f))) should be((
          BigInt(-42) & BigInt("ffffffffffffffff", 16), NX))
        send(dut, 64, ysyx_26030103_FpuOp.FCvtUIntToFp.Int, 1,
          ysyx_26030103_FpuRm.RNE.Int, 42) should be((f64(42.0), 0))
        // W/WU 只使用低 32 位：RV64 下 -1 的 FCVT.D.WU 是 0xffffffff。
        send(dut, 64, ysyx_26030103_FpuOp.FCvtUIntToFp.Int, 1,
          ysyx_26030103_FpuRm.RNE.Int, BigInt("ffffffffffffffff", 16),
          intWord = true) should be((f64(4294967295.0), 0))
        send(dut, 64, ysyx_26030103_FpuOp.FCvtIntToFp.Int, 1,
          ysyx_26030103_FpuRm.RNE.Int, BigInt("fffffffffffffff9", 16),
          intWord = true) should be((f64(-7.0), 0))
        // FCVT.W.D 负结果要符号扩展到 XLEN；WU 仍为零扩展并只报 NV。
        send(dut, 64, ysyx_26030103_FpuOp.FCvtFpToInt.Int, 1,
          ysyx_26030103_FpuRm.RTZ.Int, f64(-1.5),
          intWord = true) should be((
          BigInt("ffffffffffffffff", 16), NX))
        send(dut, 64, ysyx_26030103_FpuOp.FCvtFpToUInt.Int, 1,
          ysyx_26030103_FpuRm.RTZ.Int, f64(-1.0),
          intWord = true) should be((BigInt(0), NV))
        send(dut, 64, ysyx_26030103_FpuOp.FCvtFpToUInt.Int, 1,
          ysyx_26030103_FpuRm.RTZ.Int, f64(-1.0)) should be((
          BigInt(0), NV))
        send(dut, 64, ysyx_26030103_FpuOp.FCvtFpToFp.Int, 0,
          ysyx_26030103_FpuRm.RNE.Int, f64(1.5)) should be((
          box(f32(1.5f)), 0))
        send(dut, 64, ysyx_26030103_FpuOp.FCvtFpToFp.Int, 1,
          ysyx_26030103_FpuRm.RNE.Int, box(f32(1.5f))) should be((
          f64(1.5), 0))
      }

      withClue("FMV: ") {
        // FMV.X.W：低 32 位符号扩展到 64 位。
        send(dut, 64, ysyx_26030103_FpuOp.FMvFpToInt.Int, 0, 0,
          box(BigInt(0xdeadbeefL))) should be((
          BigInt("ffffffffdeadbeef", 16), 0))
        // FMV.W.X：写入低 32 位并 NaN-box。
        send(dut, 64, ysyx_26030103_FpuOp.FMvIntToFp.Int, 0, 0,
          BigInt(0xdeadbeefL)) should be((box(BigInt(0xdeadbeefL)), 0))
      }
    }
  }

  it should "FDIV/FSQRT 遵守多周期握手、反压与特殊值语义" in {
    sim64 { dut =>
      val ten = box(f32(10.0f))
      val four = box(f32(4.0f))

      withClue("FDIV 握手: ") {
        waitUntil(dut, dut.io.req.ready.peek().litToBoolean)
        dut.io.req.bits.op.poke(ysyx_26030103_FpuOp.FDiv)
        dut.io.req.bits.fmt.poke(0.U(2.W))
        dut.io.req.bits.rm.poke(0.U(3.W))
        dut.io.req.bits.rs1.poke(ten.U(64.W))
        dut.io.req.bits.rs2.poke(four.U(64.W))
        dut.io.req.bits.rs3.poke(0.U(64.W))
        dut.io.req.valid.poke(true.B)
        dut.clock.step()
        dut.io.req.valid.poke(false.B)

        // 忙时不能接收新请求。
        dut.io.req.ready.peek().litToBoolean should be(false)
        val latency = waitUntil(
          dut,
          dut.io.resp.valid.peek().litToBoolean,
          700
        )
        latency should be > 1
        dut.io.resp.bits.result.peek().litValue should be(box(f32(2.5f)))
        dut.io.resp.bits.fflags.peek().litValue should be(BigInt(0))

        // 下游反压：valid/result 保持不变，直到 ready。
        dut.clock.step(4)
        dut.io.resp.valid.peek().litToBoolean should be(true)
        dut.io.resp.bits.result.peek().litValue should be(box(f32(2.5f)))
        dut.io.resp.ready.poke(true.B)
        dut.clock.step()
        dut.io.resp.ready.poke(false.B)
        dut.io.resp.valid.peek().litToBoolean should be(false)
        dut.io.req.ready.peek().litToBoolean should be(true)
      }

      withClue("S/D 除法与开方: ") {
        send(dut, 64, ysyx_26030103_FpuOp.FSqrt.Int, 0, 0,
          box(f32(9.0f))) should be((box(f32(3.0f)), 0))
        send(dut, 64, ysyx_26030103_FpuOp.FDiv.Int, 1, 0,
          f64(1.0), f64(8.0)) should be((f64(0.125), 0))
        send(dut, 64, ysyx_26030103_FpuOp.FSqrt.Int, 1, 0,
          f64(2.25)) should be((f64(1.5), 0))
      }

      withClue("FDIV/FSQRT 特殊值与 underflow: ") {
        send(dut, 64, ysyx_26030103_FpuOp.FDiv.Int, 0, 0,
          box(f32(1.0f)), box(f32(0.0f))) should be((box(Inf32), DZ))
        send(dut, 64, ysyx_26030103_FpuOp.FSqrt.Int, 0, 0,
          box(f32(-1.0f))) should be((BoxedNaN32, NV))
        send(dut, 64, ysyx_26030103_FpuOp.FDiv.Int, 0, 0,
          box(f32(0.0f)), box(f32(0.0f))) should be((BoxedNaN32, NV))

        val minNormal = java.lang.Float.intBitsToFloat(0x00800000)
        val (ufResult, ufFlags) = send(
          dut, 64, ysyx_26030103_FpuOp.FDiv.Int, 0,
          ysyx_26030103_FpuRm.RNE.Int,
          box(f32(minNormal)), box(f32(3.0f)))
        ufResult should be(box(f32(minNormal / 3.0f)))
        (ufFlags & (UF | NX)) should be(UF | NX)
      }
    }
  }

  it should "FLEN=32 配置只生成并运行 S 格式" in {
    sim32 { dut =>
      send(dut, 32, ysyx_26030103_FpuOp.FAdd.Int, 0, 0,
        f32(1.5f), f32(2.25f)) should be((f32(3.75f), 0))
      send(dut, 32, ysyx_26030103_FpuOp.FMul.Int, 0, 0,
        f32(1.5f), f32(2.0f)) should be((f32(3.0f), 0))
      send(dut, 32, ysyx_26030103_FpuOp.FCvtIntToFp.Int, 0,
        ysyx_26030103_FpuRm.RNE.Int, 7) should be((f32(7.0f), 0))
      send(dut, 32, ysyx_26030103_FpuOp.FDiv.Int, 0, 0,
        f32(10.0f), f32(4.0f)) should be((f32(2.5f), 0))
      send(dut, 32, ysyx_26030103_FpuOp.FSqrt.Int, 0, 0,
        f32(9.0f)) should be((f32(3.0f), 0))
    }
  }
}
