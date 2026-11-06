package ysyx_26030103.config

import _root_.circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import _root_.ysyx_26030103.riscv32e_npc_AXIRAM

/** direct-NPC SoC 位宽契约测试。
  *
  * SimTop 与 AXIRAM 固定 32 位 AXI；Makefile 的 direct 目标负责把
  * EffectiveSocXLEN 强制投影为 32。这里锁定 AXIRAM 本身的默认值，并
  * 确认 64 位实例化会得到可执行的错误提示，而不是生成错宽端口。
  */
class ysyx_26030103_DirectNpcSocWidthTest extends AnyFlatSpec with Matchers {
  "riscv32e_npc_AXIRAM" should "默认使用 32 位 SoC AXI" in {
    val axiRam = new riscv32e_npc_AXIRAM(4096L)
    ChiselStage.emitCHIRRTL(axiRam)
    axiRam.socXLEN should be(32)
  }

  it should "拒绝 64 位 SoC AXI 并提示改用完整 SoC 构建" in {
    val error = intercept[IllegalArgumentException] {
      ChiselStage.emitCHIRRTL(new riscv32e_npc_AXIRAM(4096L, 64))
    }
    error.getMessage should include("32 位 SoC AXI")
    error.getMessage should include("SIM_TOP=soc")
  }
}
