package ysyx_26030103.config

import _root_.circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import _root_.ysyx_26030103.ysyx_26030103

/** CPU32/64 x SoC32/64 的顶层 elaboration 矩阵。
  *
  * 只做 Chisel elaboration/CHIRRTL 端口检查，不跑 firtool 和全量
  * Verilator 构建。重点验证：
  *   - master/slave AXI 的地址、数据、WSTRB 宽度由 EffectiveSocXLEN
  *     决定，而不是由 CPU XLEN 决定；
  *   - debug/trap 观测端口始终是 32 位低字 + 独立 `_hi` 高字，
  *     64 位 SoC 不会把 32 位观测 ABI 扩展成“假 64 位”，也不会
  *     覆盖 NPC 的 64 位架构状态。
  */
class ysyx_26030103_SocXLENElaborationTest extends AnyFlatSpec with Matchers {
  private def cpuAddressWidth(cpuXlen: Int): Int =
    if (cpuXlen == 64) { 64 }
    else { 32 }

  private def config(cpuXlen: Int, socXlen: Int): ysyx_26030103_NPCConfig =
    ysyx_26030103_NPCConfig(
      XLEN = cpuXlen,
      AddressWidth = cpuAddressWidth(cpuXlen),
      SocXLENFollowCpu = false,
      SocXLEN = socXlen,
      // 只验证边界和端口，关闭不影响 ABI 的观测/性能逻辑以缩短 elaboration。
      SDBEnable = false,
      DiffTestEnable = false,
      MTraceEnable = false,
      ITraceEnable = false,
      FTraceEnable = false,
      IRingBufEnable = false,
      PerfStatsEnable = false
    )

  private def elaborate(cpuXlen: Int, socXlen: Int): String =
    ChiselStage.emitCHIRRTL(
      new ysyx_26030103(config(cpuXlen, socXlen))
    )

  private def portWidth(chirrtl: String, Name: String): Int = {
    val Pattern = ("""\b""" + Name + """\s*:\s*UInt<(\d+)>""").r
    Pattern.findFirstMatchIn(chirrtl) match {
      case Some(matched) => matched.group(1).toInt
      case None =>
        fail(s"elaboration 结果中找不到端口 $Name")
    }
  }

  private def checkPorts(chirrtl: String, socXlen: Int): Unit = {
    val StrbWidth = socXlen / 8
    Seq(
      "master_awaddr" -> socXlen,
      "master_araddr" -> socXlen,
      "master_wdata" -> socXlen,
      "master_rdata" -> socXlen,
      "slave_awaddr" -> socXlen,
      "slave_araddr" -> socXlen,
      "slave_wdata" -> socXlen,
      "slave_rdata" -> socXlen,
      "master_wstrb" -> StrbWidth,
      "slave_wstrb" -> StrbWidth
    ).foreach { case (name, expected) =>
      withClue(s"SOC_XLEN=$socXlen 端口 $name: ") {
        portWidth(chirrtl, name) should be(expected)
      }
    }

    // ysyxSoC 黑盒使用 io_perf_trap，直接仿真 ABI 使用 perf_trap_count；
    // 两个名字必须同时存在并保持 1 位。
    Seq("perf_trap", "perf_trap_count").foreach { name =>
      withClue(s"SOC_XLEN=$socXlen 端口 $name: ") {
        portWidth(chirrtl, name) should be(1)
      }
    }

    // 调试/trap 仍是 32 位观测 ABI；高 32 位只能走 NPC 的 `_hi` 端口。
    Seq(
      "trap_pc",
      "trap_pc_hi",
      "debug_gpr_rdata",
      "debug_gpr_rdata_hi",
      "debug_pc",
      "debug_pc_hi",
      "debug_arch_pc",
      "debug_arch_pc_hi",
      "debug_next_pc",
      "debug_next_pc_hi",
      "debug_trap_pc",
      "debug_trap_pc_hi",
      "debug_trap_target",
      "debug_trap_target_hi",
      "debug_mtrace_addr",
      "debug_mtrace_addr_hi",
      "debug_access_fault_pc",
      "debug_access_fault_pc_hi"
    ).foreach { name =>
      withClue(s"SOC_XLEN=$socXlen 端口 $name: ") {
        portWidth(chirrtl, name) should be(32)
      }
    }
  }

  it should "在 ChipLink 入站 DMA 代理路径下 elaboration SoC32/64" in {
    for (socXlen <- Seq(32, 64)) {
      val base = config(if (socXlen == 64) 64 else 32, socXlen)
      val chirrtl = ChiselStage.emitCHIRRTL(
        new ysyx_26030103(
          base.copy(
            PMARegions =
              _root_.ysyx_26030103.common.ysyx_26030103_PhysicalMemoryMap
                .SoC(HasChipLink = true)
          )
        )
      )
      chirrtl should include("socSlaveBridge")
    }
  }

  "NPC top" should "在 CPU32/64 x SoC32/64 四种组合下 elaboration 一致的端口宽度" in {
    val Cases = Seq(
      (32, 32),
      (64, 32),
      (32, 64),
      (64, 64)
    )
    for ((cpuXlen, socXlen) <- Cases) {
      withClue(s"CPU XLEN=$cpuXlen, SoC XLEN=$socXlen: ") {
        val chirrtl = elaborate(cpuXlen, socXlen)
        checkPorts(chirrtl, socXlen)
      }
    }
  }
}
