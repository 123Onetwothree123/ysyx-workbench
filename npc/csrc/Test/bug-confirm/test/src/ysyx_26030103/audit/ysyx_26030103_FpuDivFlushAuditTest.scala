package ysyx_26030103.audit

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.ysyx_26030103.config._
import _root_.ysyx_26030103.fpu._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** FPU 除法 / flush 疑点审计。
  *
  * 三个相互独立的疑点：
  *
  *   1. Fudian 共享 DIV 模式下，F 格式 FDIV/FSQRT 走 D 精度引擎后再回舍，
  *      其 fflags 由 `sharedFFlags` 启发式重算。这里用精确有理数 oracle
  *      生成的定向向量逐位核对结果与标志。
  *   2. flush 是否能丢弃在途 DIV（架构正确性）：flush 后不得再吐出旧结果，
  *      且随后仍能正常计算。
  *   3. flush 是否立即释放 DIV 单元（实现/延迟契约，非架构正确性）：
  *      Fudian 显式 `kill`，HardFloat 只置 `divAbandoned` 等自然结束。
  *
  * 源码证据：
  *   - `npc/src/FPU/Fudian/ysyx_26030103_FudianFPUResponse.scala:130-148`：
  *     `sharedFFlags` 用 `tiny && nx` 近似 UF，并用 `activeFSNaN` 补 NV；
  *     `:169` 在 flush 且 busy 时断言 `dDiv.specialIO.kill`。
  *   - `npc/src/FPU/HardFloat/ysyx_26030103_HardFloatFPUResponse.scala:88-91`：
  *     flush 只清 `respValid` 并置 `divAbandoned := true`；
  *     `:49-50` 的 `io.req.ready` 仍要求 `!divActive`，必须等
  *     `DivSqrtRecFN_small` 自己跑完（`:69-77`）才恢复接收。
  */
class ysyx_26030103_FpuDivFlushAuditTest extends AnyFlatSpec with Matchers {

  // fflags 位序：NX=1, UF=2, OF=4, DZ=8, NV=16。
  private val NX: BigInt = BigInt(1)
  private val UF: BigInt = BigInt(2)
  private val OF: BigInt = BigInt(4)
  private val DZ: BigInt = BigInt(8)
  private val NV: BigInt = BigInt(16)

  private val FDiv = ysyx_26030103_FpuOp.FDiv.litValue.toInt
  private val FSqrt = ysyx_26030103_FpuOp.FSqrt.litValue.toInt

  private val FMT_S = ysyx_26030103_FpuFmt.S.litValue.toInt
  private val FMT_D = ysyx_26030103_FpuFmt.D.litValue.toInt
  private val RNE = ysyx_26030103_FpuRm.RNE.litValue.toInt

  private def fudian64: ysyx_26030103_NPCConfig =
    ysyx_26030103_NPCConfig(
      XLEN = 64,
      AddressWidth = 64,
      UseF = true,
      UseD = true,
      FPUImpl = ysyx_26030103_FPUImpl.Fudian,
      FPULen = 64
    )

  private def fudian32: ysyx_26030103_NPCConfig =
    ysyx_26030103_NPCConfig(
      XLEN = 32,
      UseF = true,
      FPUImpl = ysyx_26030103_FPUImpl.Fudian,
      FPULen = 32
    )

  private def hardfloat64: ysyx_26030103_NPCConfig =
    ysyx_26030103_NPCConfig(
      XLEN = 64,
      AddressWidth = 64,
      UseF = true,
      UseD = true,
      FPUImpl = ysyx_26030103_FPUImpl.HardFloat,
      FPULen = 64
    )

  private def f64(x: Double): BigInt =
    BigInt(java.lang.Double.doubleToRawLongBits(x)) & ((BigInt(1) << 64) - 1)

  /** FLEN=64 时 S 值必须 NaN-box，否则会被后端当成规范 NaN。 */
  private def boxS(x: BigInt): BigInt =
    (BigInt(0xffffffffL) << 32) | (x & BigInt(0xffffffffL))

  private def waitUntil(
      dut: ysyx_26030103_FPU,
      cond: => Boolean,
      maxCycles: Int,
      clue: String
  ): Int = {
    var cycles = 0
    while (!cond && cycles < maxCycles) {
      dut.clock.step()
      cycles += 1
    }
    withClue(s"$clue 等待条件超时（$maxCycles 拍）: ") {
      cond should be(true)
    }
    cycles
  }

  /** 通用请求/响应驱动：valid 一拍，等 resp.valid，读结果后 ready 一拍。 */
  private def sendDut(
      dut: ysyx_26030103_FPU,
      flen: Int,
      op: Int,
      fmt: Int,
      rm: Int,
      a: BigInt,
      b: BigInt,
      maxCycles: Int = 6000
  ): (BigInt, Long) = {
    val clue =
      s"op=$op fmt=$fmt rm=$rm a=0x${a.toString(16)} b=0x${b.toString(16)}"
    waitUntil(dut, dut.io.req.ready.peek().litToBoolean, maxCycles,
      s"$clue 发射前 req.ready")

    dut.io.req.bits.op.poke(op.U(ysyx_26030103_FpuOp.Width.W))
    dut.io.req.bits.fmt.poke(fmt.U(ysyx_26030103_FpuFmt.Width.W))
    dut.io.req.bits.srcFmt.poke(fmt.U(ysyx_26030103_FpuFmt.Width.W))
    dut.io.req.bits.rm.poke(rm.U(ysyx_26030103_FpuRm.Width.W))
    dut.io.req.bits.intWord.poke(true.B)
    dut.io.req.bits.rs1.poke(a.U(flen.W))
    dut.io.req.bits.rs2.poke(b.U(flen.W))
    dut.io.req.bits.rs3.poke(0.U(flen.W))
    dut.io.req.valid.poke(true.B)
    dut.clock.step()
    dut.io.req.valid.poke(false.B)

    waitUntil(dut, dut.io.resp.valid.peek().litToBoolean, maxCycles,
      s"$clue 等待 resp.valid")
    val result = dut.io.resp.bits.result.peek().litValue
    val fflags = dut.io.resp.bits.fflags.peek().litValue.toLong

    dut.io.resp.ready.poke(true.B)
    dut.clock.step()
    dut.io.resp.ready.poke(false.B)
    (result, fflags)
  }

  private def initDut(dut: ysyx_26030103_FPU): Unit = {
    dut.reset.poke(true.B)
    dut.clock.step(3)
    dut.reset.poke(false.B)
    dut.io.req.valid.poke(false.B)
    dut.io.resp.ready.poke(false.B)
    dut.io.flush.poke(false.B)
  }

  // ------------------------------------------------------------------
  // TEST 1：Fudian 共享模式的 F 格式 FDIV/FSQRT 与 fflags
  // ------------------------------------------------------------------

  private case class SVec(
      idx: Int,
      name: String,
      op: Int,
      a: BigInt,
      b: BigInt,
      exp: BigInt,
      flags: BigInt,
      nan: Boolean = false
  )

  private def svecs: Seq[SVec] = Seq(
    // idx, 说明, op, a, b, 期望低 32 位, 期望 fflags, 是否 NaN（不比对 payload）
    SVec(1, "1/3", FDiv, BigInt(0x3f800000L), BigInt(0x40400000L),
      BigInt(0x3eaaaaabL), NX),
    SVec(2, "maxFinite/minSubnormal 溢出", FDiv,
      BigInt(0x7f7fffffL), BigInt(0x00000001L),
      BigInt(0x7f800000L), OF | NX),
    SVec(3, "1/+0", FDiv, BigInt(0x3f800000L), BigInt(0x00000000L),
      BigInt(0x7f800000L), DZ),
    SVec(4, "-1/+0", FDiv, BigInt(0xbf800000L), BigInt(0x00000000L),
      BigInt(0xff800000L), DZ),
    SVec(5, "0/0", FDiv, BigInt(0x00000000L), BigInt(0x00000000L),
      BigInt(0), NV, nan = true),
    SVec(6, "sNaN/1", FDiv, BigInt(0x7f800001L), BigInt(0x3f800000L),
      BigInt(0), NV, nan = true),
    SVec(7, "qNaN/1", FDiv, BigInt(0x7fc00001L), BigInt(0x3f800000L),
      BigInt(0), BigInt(0), nan = true),
    // 2^-150 恰好是 0 与最小次正规的中点，RNE 取偶 => 0，且下溢。
    SVec(8, "minSubnormal/2 tie-to-even", FDiv,
      BigInt(0x00000001L), BigInt(0x40000000L),
      BigInt(0x00000000L), NX | UF),
    // 精确次正规结果绝不能报 UF。
    SVec(9, "minSubnormal/1 精确次正规", FDiv,
      BigInt(0x00000001L), BigInt(0x3f800000L),
      BigInt(0x00000001L), BigInt(0)),
    SVec(10, "3*minSubnormal/2 非精确次正规", FDiv,
      BigInt(0x00000003L), BigInt(0x40000000L),
      BigInt(0x00000002L), NX | UF),
    SVec(11, "minNormal/2 精确次正规", FDiv,
      BigInt(0x00800000L), BigInt(0x40000000L),
      BigInt(0x00400000L), BigInt(0)),
    SVec(12, "3/7", FDiv, BigInt(0x40400000L), BigInt(0x40e00000L),
      BigInt(0x3edb6db7L), NX),
    SVec(13, "sqrt(2)", FSqrt, BigInt(0x40000000L), BigInt(0),
      BigInt(0x3fb504f3L), NX),
    SVec(14, "sqrt(minSubnormal)", FSqrt, BigInt(0x00000001L), BigInt(0),
      BigInt(0x1a3504f3L), NX),
    SVec(15, "sqrt(-1)", FSqrt, BigInt(0xbf800000L), BigInt(0),
      BigInt(0), NV, nan = true),
    SVec(16, "sqrt(maxFinite)", FSqrt, BigInt(0x7f7fffffL), BigInt(0),
      BigInt(0x5f7fffffL), NX)
  )

  "Fudian 共享 DIV/SQRT" should
    "在 F 格式上给出正确的 S 结果与 fflags" in {
      simulate(new ysyx_26030103_FudianFPU(fudian64)) { dut =>
        initDut(dut)

        svecs.foreach { c =>
          val (got, gotFlags) =
            sendDut(dut, 64, c.op, FMT_S, RNE, boxS(c.a), boxS(c.b))

          val clue =
            s"case ${c.idx} (${c.name}): op=${c.op} fmt=S rm=RNE " +
              s"a=0x${c.a.toString(16)} b=0x${c.b.toString(16)} " +
              s"gotResult=0x${got.toString(16)} gotFlags=$gotFlags " +
              s"expectedResult=0x${c.exp.toString(16)} " +
              s"expectedFlags=${c.flags}"

          withClue(clue) {
            val hi = (got >> 32) & BigInt(0xffffffffL)
            val low = got & BigInt(0xffffffffL)
            if (c.nan) {
              // NaN 只要求是 NaN 且已 NaN-box；payload/符号由各实现自定。
              // 有效 S NaN：指数域全 1（0xff）且尾数非零。
              hi should be(BigInt(0xffffffffL))
              ((low >> 23) & BigInt(0xff)) should be(BigInt(0xff))
              (low & BigInt(0x7fffff)).signum should be(1)
            } else {
              // 数值结果必须完整等于 NaN-box 后的 S 位模式。
              got should be(boxS(c.exp))
            }
            BigInt(gotFlags) should be(c.flags)
          }
        }
      }
    }

  // ------------------------------------------------------------------
  // TEST 2 / TEST 3 共用：发射 1.0/3.0，flush，测量恢复
  // ------------------------------------------------------------------

  private def issueDivD(dut: ysyx_26030103_FPU, a: BigInt, b: BigInt): Unit = {
    waitUntil(dut, dut.io.req.ready.peek().litToBoolean, 6000,
      s"FDIV.D a=0x${a.toString(16)} b=0x${b.toString(16)} 发射前 req.ready")
    dut.io.req.bits.op.poke(FDiv.U(ysyx_26030103_FpuOp.Width.W))
    dut.io.req.bits.fmt.poke(FMT_D.U(ysyx_26030103_FpuFmt.Width.W))
    dut.io.req.bits.srcFmt.poke(FMT_D.U(ysyx_26030103_FpuFmt.Width.W))
    dut.io.req.bits.rm.poke(RNE.U(ysyx_26030103_FpuRm.Width.W))
    dut.io.req.bits.intWord.poke(true.B)
    dut.io.req.bits.rs1.poke(a.U(64.W))
    dut.io.req.bits.rs2.poke(b.U(64.W))
    dut.io.req.bits.rs3.poke(0.U(64.W))
    dut.io.req.valid.poke(true.B)
    dut.clock.step()
    dut.io.req.valid.poke(false.B)
  }

  /** 执行 TEST 2 的 a-d，返回 flush 后到 req.ready 恢复的拍数。 */
  private def flushInFlightDivAndMeasure(
      dut: ysyx_26030103_FPU,
      label: String
  ): Long = {
    val oneThirdA = BigInt("3ff0000000000000", 16) // 1.0
    val oneThirdB = BigInt("4008000000000000", 16) // 3.0

    // a. 发射一长延迟的 FDIV.D。
    issueDivD(dut, oneThirdA, oneThirdB)

    // b. 接受后必须立刻忙：不再接收新请求，也没有响应。
    withClue(s"$label: 接受 1.0/3.0 后 req.ready/resp.valid 应立即为 false: ") {
      dut.io.req.ready.peek().litToBoolean should be(false)
      dut.io.resp.valid.peek().litToBoolean should be(false)
    }

    // c. 走几拍让 DIV 真正进入迭代，再打一拍 flush。
    dut.clock.step(5)
    dut.io.flush.poke(true.B)
    dut.clock.step()
    dut.io.flush.poke(false.B)

    // d. flush 后绝不能再看到在途 DIV 的旧结果；同时测量恢复拍数。
    var recoveryCycles = 0L
    var readySeen = false
    while (!readySeen && recoveryCycles < 4000L) {
      withClue(
        s"$label: flush 后第 $recoveryCycles 拍出现了在途 FDIV.D 的陈旧响应 " +
          "(resp.valid=true)，说明 flush 没有杀死旧结果: "
      ) {
        dut.io.resp.valid.peek().litToBoolean should be(false)
      }
      if (dut.io.req.ready.peek().litToBoolean) {
        readySeen = true
      } else {
        dut.clock.step()
        recoveryCycles += 1
      }
    }
    withClue(
      s"$label: flush 后 4000 拍内 req.ready 仍未恢复；" +
        s"recoveryCycles=$recoveryCycles"
    ) {
      readySeen should be(true)
    }
    recoveryCycles
  }

  /** TEST 2 的 e：flush 恢复后仍必须能算对一笔新的 FDIV.D。 */
  private def expectPostFlushDiv(dut: ysyx_26030103_FPU, label: String): Unit = {
    val (result, flags) =
      sendDut(dut, 64, FDiv, FMT_D, RNE,
        f64(2.0), f64(4.0))
    withClue(
      s"$label: flush 后 FDIV.D 2.0/4.0 got=0x${result.toString(16)} " +
        s"flags=$flags，期望 0x3fe0000000000000/0: "
    ) {
      result should be(BigInt("3fe0000000000000", 16))
      flags should be(0L)
    }
  }

  // ------------------------------------------------------------------
  // TEST 2：flush 丢弃在途 DIV 的架构正确性
  // ------------------------------------------------------------------

  "Fudian flush 在途 FDIV.D" should
    "不泄漏陈旧结果并能恢复计算（架构正确性）" in {
      simulate(new ysyx_26030103_FudianFPU(fudian64)) { dut =>
        initDut(dut)
        flushInFlightDivAndMeasure(dut, "Fudian")
        expectPostFlushDiv(dut, "Fudian")
      }
    }

  "HardFloat flush 在途 FDIV.D" should
    "不泄漏陈旧结果并能恢复计算（架构正确性）" in {
      simulate(new ysyx_26030103_HardFloatFPU(hardfloat64)) { dut =>
        initDut(dut)
        flushInFlightDivAndMeasure(dut, "HardFloat")
        expectPostFlushDiv(dut, "HardFloat")
      }
    }

  // ------------------------------------------------------------------
  // TEST 3：flush 是否立即释放 DIV 单元（性能/实现契约）
  // ------------------------------------------------------------------

  "Fudian flush 应立即释放 DIV 单元（性能契约，非架构正确性）" should
    "在 <= 40 拍内让 req.ready 恢复" in {
      simulate(new ysyx_26030103_FudianFPU(fudian64)) { dut =>
        initDut(dut)
        val recoveryCycles = flushInFlightDivAndMeasure(dut, "Fudian")
        info(s"Fudian flush -> req.ready recoveryCycles=$recoveryCycles")
        withClue(
          s"Fudian: flush 后恢复拍数=$recoveryCycles，" +
            "期望 <= 40。flush 应立即释放 DIV 单元" +
            "（性能契约，非架构正确性）。" +
            "源码：FudianFPUResponse.scala:169 已断言 specialIO.kill。"
        ) {
          recoveryCycles should be <= 40L
        }
      }
    }

  // Berkeley HardFloat 的 DivSqrtRecFN_small 没有 kill 端口：flush 只能
  // 丢弃结果，无法中断已在迭代的除法。这里不断言“立即释放”，否则等于要求
  // 修改 vendor 模块；只要求最终恢复，并把实测拍数记录下来。
  "HardFloat flush 等待在途 DIV 自然结束（实现差异，非 BUG）" should
    "在有限拍内恢复并在恢复后给出正确结果" in {
      simulate(new ysyx_26030103_HardFloatFPU(hardfloat64)) { dut =>
        initDut(dut)
        val recoveryCycles = flushInFlightDivAndMeasure(dut, "HardFloat")
        info(s"HardFloat flush -> req.ready recoveryCycles=$recoveryCycles")
        withClue(
          s"HardFloat: flush 后恢复拍数=$recoveryCycles，" +
            "期望 <= 200（DivSqrtRecFN_small 自然迭代长度）。" +
            "flush 只置 divAbandoned（HardFloatFPUResponse.scala:88-90），" +
            "req.ready 仍要求 !divActive（:49-50）；结果不会被误写回，属实现差异。" +
            "对照：Fudian 在 :169 断言 specialIO.kill，恢复拍数为 0。"
        ) {
          recoveryCycles should be <= 200L
        }
        expectPostFlushDiv(dut, "HardFloat")
      }
    }
}
