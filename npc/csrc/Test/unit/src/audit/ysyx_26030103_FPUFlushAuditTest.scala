package ysyx_26030103.audit

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.ysyx_26030103.config._
import _root_.ysyx_26030103.fpu._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** HardFloat FPU 的 flush 边界审计。
  *
  * 语义：flush 同拍 FDIV 可以完成 ready/valid 握手，但不能启动
  * DIV/SQRT，否则 `divS/divD.inValid` 被 `!io.flush` 屏蔽而
  * `divActive` 被置 1，迭代器不会运行，之后 `io.req.ready` 永久为低。
  * EXU 会在同一拍用 FpuFlush 清掉 FpPending，因此该请求可安全丢弃。
  *
  * 本测试在 FDIV 发射同拍拉高 flush，随后撤销 flush，检查 FPU 是否
  * 迅速恢复为可接受新请求。
  */
class ysyx_26030103_FPUFlushAuditTest extends AnyFlatSpec with Matchers {
  private def config: ysyx_26030103_NPCConfig =
    ysyx_26030103_NPCConfig(
      XLEN = 64,
      AddressWidth = 64,
      UseF = true,
      UseD = true,
      FPUImpl = ysyx_26030103_FPUImpl.HardFloat,
      FPULen = 64
    )

  private def f64(value: BigInt): BigInt = value & ((BigInt(1) << 64) - 1)

  private def bool(v: Bool): Boolean = v.peek().litValue == 1

  "HardFloatFPU" should "在 flush 同拍不启动 FDIV，并在撤销 flush 后恢复 req.ready" in {
    simulate(new ysyx_26030103_HardFloatFPU(config)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.flush.poke(false.B)
      dut.io.resp.ready.poke(true.B)
      dut.io.req.valid.poke(false.B)
      dut.io.req.bits.op.poke(ysyx_26030103_FpuOp.FDiv)
      dut.io.req.bits.fmt.poke(ysyx_26030103_FpuFmt.D)
      dut.io.req.bits.srcFmt.poke(ysyx_26030103_FpuFmt.D)
      dut.io.req.bits.rm.poke(ysyx_26030103_FpuRm.RNE)
      dut.io.req.bits.intWord.poke(false.B)
      dut.io.req.bits.rs1.poke(
        f64(BigInt("3ff0000000000000", 16)).U(64.W)) // 1.0
      dut.io.req.bits.rs2.poke(
        f64(BigInt("4008000000000000", 16)).U(64.W)) // 3.0
      dut.io.req.bits.rs3.poke(0.U(64.W))

      var wait = 0
      while (!bool(dut.io.req.ready) && wait < 8) {
        dut.clock.step()
        wait += 1
      }
      withClue("FPU did not become ready for the initial FDIV: ") {
        bool(dut.io.req.ready) should be(true)
      }

      // 关键拍：FDIV 请求与 flush 同拍。FPU 可以握手（ready 只反映
      // 内部空闲），但不得启动迭代器，否则 divActive 会卡死。
      dut.io.req.valid.poke(true.B)
      dut.io.flush.poke(true.B)
      withClue("FPU ready should not combinationally depend on flush: ") {
        bool(dut.io.req.ready) should be(true)
      }
      dut.clock.step()

      // 撤销请求和 flush，FPU 必须回到可接受新请求的状态。
      dut.io.req.valid.poke(false.B)
      dut.io.flush.poke(false.B)

      var cycles = 0
      while (!bool(dut.io.req.ready) && cycles < 64) {
        dut.clock.step()
        cycles += 1
      }
      withClue(
        s"FPU req.ready stayed low for $cycles cycles after a same-cycle " +
          "FDIV/flush; divActive is likely stuck: ") {
        bool(dut.io.req.ready) should be(true)
      }
    }
  }
}
