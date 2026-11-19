package ysyx_26030103.config

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import _root_.ysyx_26030103.common.ysyx_26030103_PMARegion
import _root_.ysyx_26030103.common.ysyx_26030103_PhysicalMemoryMap

/** 自修改代码 / DMA 一致性模式的配置规则测试。
  *
  * 重点不是性能，而是“模式必须显式、未实现的值必须报错、日志必须能看出
  * 实际生效行为”。DMA uncached 通过 Cache 使用的 PMA 视图切出
  * 0xc0000000--0xffffffff，原始 PMA 的读写/执行权限不变。
  */
class ysyx_26030103_CoherenceConfigTest extends AnyFlatSpec with Matchers {
  private val DMABase = 0xc0000000L
  private val DMAEnd  = 0x100000000L

  private def covers(region: ysyx_26030103_PMARegion, base: Long, end: Long): Boolean =
    region.Base <= base && region.EndExclusive >= end

  "NPCConfig" should "默认保持 fence.i 和既有 DMA 写 B 失效行为" in {
    val config = ysyx_26030103_NPCConfig()
    config.ICacheCoherence should be(ysyx_26030103_ICacheCoherence.FenceI)
    config.DMACoherence should be(ysyx_26030103_DMACoherence.SoftwareFlush)
    config.CachePMARegions should be(config.PMARegions)
    config.DMAWindowUncached should be(false)
    config.Describe should include("icache_coherence=fence_i")
    config.Describe should include("dma_coherence=software_flush")
  }

  it should "UNCACHED 只在 Cache 视图中把 DMA 窗口切成不可缓存" in {
    for (xlen <- Seq(32, 64)) {
      val PMA = ysyx_26030103_PhysicalMemoryMap.SoC(HasChipLink = true)
      val config = ysyx_26030103_NPCConfig(
        XLEN = xlen,
        AddressWidth = if (xlen == 64) 64 else 32,
        PMARegions = PMA,
        DMACoherence = ysyx_26030103_DMACoherence.Uncached
      )
      withClue(s"XLEN=$xlen: ") {
        config.DMAWindowUncached should be(true)
        config.DMAWindowPresent should be(true)
        config.Describe should include("dma_coherence=uncached(dma_window)")
        // 原始 PMA 不变，Xbar/PMA 权限仍是原值。
        config.PMARegions should be(PMA)
        val DMAParts = config.CachePMARegions.filter(r =>
          r.Base < DMAEnd && r.EndExclusive > DMABase
        )
        DMAParts should not be empty
        DMAParts.foreach { region =>
          region.Cacheable should be(false)
          // 权限不能因为“不可缓存”而丢掉。
          region.Readable should be(true)
          region.Writable should be(true)
          region.Executable should be(true)
        }
        // 完整覆盖 DMA 窗口，不能留下可缓存的缝隙。
        DMAParts.exists(r => covers(r, DMABase, DMAEnd)) should be(true)
        // 其他可缓存区域保持原样。
        val psram = config.CachePMARegions.find(r =>
          r.Base == 0x80000000L && r.EndExclusive == 0x80400000L
        )
        psram.map(_.Cacheable) should be(Some(true))
        val sdram = config.CachePMARegions.find(r =>
          r.Base == 0xa0000000L && r.EndExclusive == 0xa2000000L
        )
        sdram.map(_.Cacheable) should be(Some(true))
      }
    }
  }

  it should "UNCACHED 对不含 DMA 窗口的本地 PMA 不产生额外区域" in {
    val PMA = ysyx_26030103_PhysicalMemoryMap.NPC
    val config = ysyx_26030103_NPCConfig(
      ResetAddress = 0x80000000L,
      PMARegions = PMA,
      DMACoherence = ysyx_26030103_DMACoherence.Uncached
    )
    config.CachePMARegions should be(PMA)
    config.DMAWindowPresent should be(false)
    config.Describe should include(
      "dma_coherence=uncached(inactive:no-DMA-window)"
    )
  }

  it should "硬件 store snoop 模式在日志中标注实际生效状态" in {
    val active = ysyx_26030103_NPCConfig(
      ICacheEnable = true,
      ICacheCoherence = ysyx_26030103_ICacheCoherence.HardwareSnoop
    )
    active.Describe should include("icache_coherence=hardware_snoop(hw)")

    val inactive = ysyx_26030103_NPCConfig(
      ICacheEnable = false,
      ICacheCoherence = ysyx_26030103_ICacheCoherence.HardwareSnoop
    )
    inactive.Describe should include(
      "icache_coherence=hardware_snoop(inactive:ICache=off)"
    )
  }

  "DmaCoherence" should "接受 hardware_snoop 并在日志中标注生效缓存" in {
    ysyx_26030103_DMACoherence.FromString("hardware_snoop") should be(
      ysyx_26030103_DMACoherence.HardwareSnoop
    )
    val active = ysyx_26030103_NPCConfig(
      ICacheEnable = true,
      DCacheEnable = true,
      PMARegions = ysyx_26030103_PhysicalMemoryMap.SoC(HasChipLink = true),
      DMACoherence = ysyx_26030103_DMACoherence.HardwareSnoop
    )
    active.DMAHardwareSnoop should be(true)
    active.Describe should include("dma_coherence=hardware_snoop")
    active.Describe should include("ICache+DCache")
    // uncached 的 PMA 视图切换不能因为选择 snoop 而被误打开。
    active.CachePMARegions should be(active.PMARegions)
  }

  it should "在没有 DMA 窗口或没有缓存时标注 snoop 未生效" in {
    val noWindow = ysyx_26030103_NPCConfig(
      ResetAddress = 0x80000000L,
      PMARegions = ysyx_26030103_PhysicalMemoryMap.NPC,
      DMACoherence = ysyx_26030103_DMACoherence.HardwareSnoop
    )
    noWindow.Describe should include(
      "dma_coherence=hardware_snoop(inactive:no-DMA-window)"
    )

    val noCache = ysyx_26030103_NPCConfig(
      ICacheEnable = false,
      DCacheEnable = false,
      PMARegions = ysyx_26030103_PhysicalMemoryMap.SoC(HasChipLink = true),
      DMACoherence = ysyx_26030103_DMACoherence.HardwareSnoop
    )
    noCache.Describe should include(
      "dma_coherence=hardware_snoop(inactive:no-cache)"
    )
  }

  it should "拒绝未知字符串而不是静默回退" in {
    val DMAError = intercept[IllegalArgumentException] {
      ysyx_26030103_DMACoherence.FromString("flush_everything")
    }
    DMAError.getMessage should include("DMA_COHERENCE 只支持")

    val ICacheError = intercept[IllegalArgumentException] {
      ysyx_26030103_ICacheCoherence.FromString("auto")
    }
    ICacheError.getMessage should include("ICACHE_COHERENCE 只支持")
  }
}
