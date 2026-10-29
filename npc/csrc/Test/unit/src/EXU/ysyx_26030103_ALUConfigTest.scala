package ysyx_26030103.exu

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import _root_.ysyx_26030103.common.ysyx_26030103_ALUFunction._
import _root_.ysyx_26030103.config._

/** ALU 后端/加法器结构矩阵测试：所有组合都必须保持 RV32/RV64 语义。 */
class ysyx_26030103_ALUConfigTest extends AnyFlatSpec with Matchers {
  private val Backends = Seq(
    ysyx_26030103_ALUImpl.Standard,
    ysyx_26030103_ALUImpl.Compact
  )

  private val Adders = Seq(
    ysyx_26030103_ALUAdderImpl.Native,
    ysyx_26030103_ALUAdderImpl.Ripple,
    ysyx_26030103_ALUAdderImpl.CarrySelect,
    ysyx_26030103_ALUAdderImpl.CarryLookahead,
    ysyx_26030103_ALUAdderImpl.Elau
  )

  private def config(
      xlen: Int,
      backend: ysyx_26030103_ALUImpl,
      adder: ysyx_26030103_ALUAdderImpl,
      elauSpeed: ysyx_26030103_ElauSpeed = ysyx_26030103_ElauSpeed.Fast
  ): ysyx_26030103_NPCConfig =
    ysyx_26030103_NPCConfig(
      XLEN = xlen,
      AddressWidth = xlen,
      ALUImpl = backend,
      ALUProfile = ysyx_26030103_ALUProfile(
        Adder = adder,
        AdderParams = ysyx_26030103_ALUAdderParams(CarrySelectGroup = 8),
        Elau = ysyx_26030103_ElauParams(Speed = elauSpeed)
      )
    )

  /** 每个配置只仿真一次，在同一组合 DUT 上依次检查多个操作。 */
  private def check(cfg: ysyx_26030103_NPCConfig): Unit = {
    simulate(ysyx_26030103_ALU(cfg)) { dut =>
      def alu(ctrl: UInt, a: BigInt, b: BigInt): BigInt = {
        dut.io.A.poke(a.U(cfg.XLEN.W))
        dut.io.B.poke(b.U(cfg.XLEN.W))
        dut.io.ALUCtrl.poke(ctrl)
        dut.io.result.peek().litValue
      }

      val mask = (BigInt(1) << cfg.XLEN) - 1
      alu(ADD, mask, 1) should be(0)
      // 全 1 相加会穿过所有分组/进位链，专门覆盖 carry-select 的边界。
      alu(ADD, mask, mask) should be(mask - 1)
      alu(SUB, 0, 1) should be(mask)
      alu(SLT, mask, 1) should be(1)
      alu(SLTU, mask, 1) should be(0)
      alu(SLL, 1, 4) should be(16)
      alu(SRL, mask, 4) should be(mask >> 4)
      alu(XOR, BigInt("a5a5a5a5", 16), BigInt("0f0f0f0f", 16)) should
        be(BigInt("aaaaaaaa", 16))
      if (cfg.XLEN == 64) {
        alu(ADDW, BigInt("7fffffff", 16), 1) should
          be(BigInt("ffffffff80000000", 16))
        alu(SUBW, BigInt("80000000", 16), 1) should
          be(BigInt("000000007fffffff", 16))
        alu(SLLW, 1, 33) should be(2)
      }
    }
  }

  "ALU configuration matrix" should "保持 RV32/RV64 算术、比较和 W 型语义" in {
    for {
      xlen <- Seq(32, 64)
      backend <- Backends
      adder <- Adders
    } {
      withClue(s"xlen=$xlen backend=${backend.Name} adder=${adder.Name}: ") {
        check(config(xlen, backend, adder))
      }
    }
  }

  "ELAU speed backends" should "在 slow/medium/fast 下保持相同语义" in {
    for (speed <- ysyx_26030103_ElauSpeed.All) {
      withClue(s"elau speed=${speed.Name}: ") {
        check(config(
          xlen = 32,
          backend = ysyx_26030103_ALUImpl.Standard,
          adder = ysyx_26030103_ALUAdderImpl.Elau,
          elauSpeed = speed
        ))
      }
    }
  }

  "ALU external backend" should "在配置期和工厂入口明确拒绝" in {
    val error = intercept[IllegalArgumentException] {
      ysyx_26030103_NPCConfig(
        XLEN = 32,
        AddressWidth = 32,
        ALUImpl = ysyx_26030103_ALUImpl.External
      )
    }
    error.getMessage should include("ALU_IMPL=external")
  }

  "ALU carry-select" should "拒绝不能整除 XLEN 的分组宽度" in {
    val error = intercept[IllegalArgumentException] {
      ysyx_26030103_NPCConfig(
        XLEN = 32,
        AddressWidth = 32,
        ALUProfile = ysyx_26030103_ALUProfile(
          Adder = ysyx_26030103_ALUAdderImpl.CarrySelect,
          AdderParams = ysyx_26030103_ALUAdderParams(CarrySelectGroup = 3)
        )
      )
    }
    error.getMessage should include("ALU_CARRY_SELECT_GROUP")
  }
}
