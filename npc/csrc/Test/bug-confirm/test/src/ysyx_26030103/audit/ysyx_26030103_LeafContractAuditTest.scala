package ysyx_26030103.audit

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.circt.stage.ChiselStage
import _root_.ysyx_26030103.mem.ysyx_26030103_LSU
import _root_.ysyx_26030103.wbu.ysyx_26030103_WBU
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** 叶子契约审计：WBU 写使能与 LSU WBufDepth。 */
class ysyx_26030103_LeafContractAuditTest extends AnyFlatSpec with Matchers {
  private def bool(value: Bool): Boolean = value.peek().litValue == 1

  "WBU write enables" should
    "不为 Retire=0 的指令写架构寄存器" in {
      // 正确契约：WriteEN/FPWriteEN 必须同时要求 in.fire 与 Retire；
      // 只有 FPCommit 已经检查 Retire 并不够，写口本身也必须受退休约束。
      simulate(new ysyx_26030103_WBU(32)) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step(2)
        dut.reset.poke(false.B)

        dut.io.in.valid.poke(false.B)
        dut.io.in.bits.poke(0.U.asTypeOf(chiselTypeOf(dut.io.in.bits)))

        // 对照组：真正退休且写寄存器的指令必须产生写使能。
        dut.io.in.bits.RegisterWrite.poke(true.B)
        dut.io.in.bits.FPRegisterWrite.poke(true.B)
        dut.io.in.bits.Retire.poke(true.B)
        dut.io.in.bits.Rd.poke(5.U)
        dut.io.in.bits.ALUResult.poke("h12345678".U)
        dut.io.in.bits.WBSelect.poke(0.U(2.W))
        dut.io.in.valid.poke(true.B) // io.in.ready 在 WBU 中恒为 true

        val retireWriteEN = bool(dut.io.WriteEN)
        val retireFPWriteEN = bool(dut.io.FPWriteEN)
        val retireFPCommit = bool(dut.io.FPCommit)

        withClue(
          s"WBU 退休对照组：WriteEN=$retireWriteEN, " +
            s"FPWriteEN=$retireFPWriteEN, FPCommit=$retireFPCommit；"
        ) {
          retireWriteEN should be(true)
          retireFPWriteEN should be(true)
          retireFPCommit should be(true)
        }

        // 疑点：非退休指令仍请求写寄存器。上游压掉 Retire 后，WBU
        // 不应再把这次写口请求传给 GPR/FPR。
        dut.io.in.bits.Retire.poke(false.B)

        val writeEN = bool(dut.io.WriteEN)
        val fpWriteEN = bool(dut.io.FPWriteEN)
        val fpCommit = bool(dut.io.FPCommit)

        println(
          s"WBU Retire=0, RegisterWrite=1 => WriteEN=$writeEN " +
            s"(expected false); Retire=0, FPRegisterWrite=1 => " +
            s"FPWriteEN=$fpWriteEN (expected false), FPCommit=$fpCommit"
        )

        withClue(
          s"WBU 非退休路径：WriteEN=$writeEN, FPWriteEN=$fpWriteEN, " +
            s"FPCommit=$fpCommit（三者都应为 false）；" +
            "当前 WriteEN/FPWriteEN 未与 Retire 相与，只有 FPCommit 做了退休检查。"
        ) {
          // FPCommit 已经 checking Retire，可作为同一拍的反例。
          fpCommit should be(false)
          // 这两条即确认 WriteEN/FPWriteEN 缺少 Retire 门控。
          writeEN should be(false)
          fpWriteEN should be(false)
        }
      }
    }

  "LSU WBufDepth" should
    "在 elaboration 阶段拒绝非 2 的幂深度" in {
      // RTL 注释与环形队列实现都要求 WBufDepth 是 2 的幂；正确实现应在
      // 构造期用 require(isPow2(WBufDepth)) 拒绝 3 之类非法深度。
      // 当前缺少该 require，因此 WBufDepth=3 会成功 elaborate，测试失败。
      val thrown = intercept[IllegalArgumentException] {
        ChiselStage.emitCHIRRTL(new ysyx_26030103_LSU(WBufDepth = 3))
      }

      withClue(
        s"WBufDepth=3 elaboration 抛出的异常=${thrown.getClass.getName}: " +
          s"${Option(thrown.getMessage).getOrElse("<null>")}；" +
          "正确实现必须在构造期用 require(isPow2(WBufDepth)) 无限拒绝非 2 的幂。"
      ) {
        Option(thrown.getMessage).getOrElse("") should include("WBufDepth")
      }
    }

  it should "允许 2 的幂深度 WBufDepth=4 正常 elaborate" in {
    // 对照组：合法深度不得被新的 require 误伤。使用 emitCHIRRTL 而非
    // emitSystemVerilog，足以证明构造和 Chisel 连接都成功，且更省时间。
    noException should be thrownBy {
      ChiselStage.emitCHIRRTL(new ysyx_26030103_LSU(WBufDepth = 4))
    }
  }
}
