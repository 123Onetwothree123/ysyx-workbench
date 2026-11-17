package ysyx_26030103.cache

import chisel3._
import _root_.ysyx_26030103.config._

/** 按配置选择指令缓存实现。
  *
  * 存储介质由 ICacheStorage 选择：寄存器阵列(Reg)或同步读SRAM(SRAM/SRAMNoBypass)。
  *
  * 公共控制逻辑集中在 ICacheCore，两版只保留存储适配：
  *   - Reg 版：组合读、结构简单，作为参考实现供叶测试与协议回归使用；
  *   - SRAM 版：同步读，是可用于综合/生产配置的实现。
  * 两者在读通路与响应级存在固有的时序差异（SRAM 多一拍），时序代码不合并；
  * 行为对齐由审计套件的参数化用例与 icache_sram_test（生产几何）保证——
  * 修改 core 或任一存储适配后必须跑 csrc/Test/issue-audit-test 全套。
  */
object ysyx_26030103_ICacheFactory {
  def Create(
      config: ysyx_26030103_NPCConfig = ysyx_26030103_NPCConfig()
  ): ysyx_26030103_ICacheBase = {
    // HARDWARE_SNOOP 只在显式选择时生成 store 地址比较/失效逻辑；
    // ICACHE=n 时没有可失效的阵列，模式在日志中标注为未生效。
    val snoop = config.ICacheEnable &&
      config.ICacheCoherence == ysyx_26030103_ICacheCoherence.HardwareSnoop
    // DMA 行失效只由 DMA_COHERENCE=hardware_snoop 生成；ICACHE=n 时没有
    // 可失效的阵列，端口在叶模块内恒 ready。
    val dmaSnoop = config.ICacheEnable &&
      config.DMACoherence == ysyx_26030103_DMACoherence.HardwareSnoop
    config.ICacheStorage match {
      case ysyx_26030103_CacheStorage.Reg =>
        Module(
          new ysyx_26030103_ICacheReg(
            Enable = config.ICacheEnable,
            BlockSizeLog2 = config.ICacheBlockSizeLog2,
            IndexBits = config.ICacheIndexBits,
            AddressWidth = config.AddressWidth,
            DataWidth = config.EffectiveInternalBusWidth,
            VIPT = config.MMU.cacheMode == "vipt",
            VIPTAliasSafe = config.ICacheVIPTAliasHardware,
            VIPTAliasScanMode = config.ICacheVIPTAliasScanMode,
            VIPTAliasScanWidth = config.ICacheVIPTAliasScanWidth,
            VIPTAliasDirectoryEntries =
              config.ICacheVIPTAliasDirectoryEntries,
            Snoop = snoop,
            DMASnoop = dmaSnoop,
            DrainTimeout = config.ICacheDrainTimeout,
            PMARegions = config.CachePMARegions
          )
        )
      case ysyx_26030103_CacheStorage.SRAM =>
        Module(
          new ysyx_26030103_ICacheSRAM(
            Enable = config.ICacheEnable,
            BlockSizeLog2 = config.ICacheBlockSizeLog2,
            IndexBits = config.ICacheIndexBits,
            AddressWidth = config.AddressWidth,
            DataWidth = config.EffectiveInternalBusWidth,
            VIPT = config.MMU.cacheMode == "vipt",
            VIPTAliasSafe = config.ICacheVIPTAliasHardware,
            VIPTAliasScanMode = config.ICacheVIPTAliasScanMode,
            VIPTAliasScanWidth = config.ICacheVIPTAliasScanWidth,
            VIPTAliasDirectoryEntries =
              config.ICacheVIPTAliasDirectoryEntries,
            Snoop = snoop,
            DMASnoop = dmaSnoop,
            DrainTimeout = config.ICacheDrainTimeout,
            PMARegions = config.CachePMARegions
          )
        )
      case ysyx_26030103_CacheStorage.SRAMNoBypass =>
        Module(
          new ysyx_26030103_ICacheSRAM(
            Enable = config.ICacheEnable,
            BlockSizeLog2 = config.ICacheBlockSizeLog2,
            IndexBits = config.ICacheIndexBits,
            AddressWidth = config.AddressWidth,
            DataWidth = config.EffectiveInternalBusWidth,
            VIPT = config.MMU.cacheMode == "vipt",
            VIPTAliasSafe = config.ICacheVIPTAliasHardware,
            VIPTAliasScanMode = config.ICacheVIPTAliasScanMode,
            VIPTAliasScanWidth = config.ICacheVIPTAliasScanWidth,
            VIPTAliasDirectoryEntries =
              config.ICacheVIPTAliasDirectoryEntries,
            Bypass = false,
            Snoop = snoop,
            DMASnoop = dmaSnoop,
            DrainTimeout = config.ICacheDrainTimeout,
            PMARegions = config.CachePMARegions
          )
        )
    }
  }
}
