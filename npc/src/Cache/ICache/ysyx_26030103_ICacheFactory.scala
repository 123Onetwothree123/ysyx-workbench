package ysyx_26030103.cache

import chisel3._
import _root_.ysyx_26030103.config._

/** 按配置选择指令缓存实现。
  *
  * 存储介质由 ICacheStorage 选择：寄存器阵列(Reg)或同步读SRAM(SRAM/SRAMNoBypass)。
  *
  * 双实现是**有意保留**的设计决定（2026-10-18 定稿）：
  *   - Reg 版：组合读、结构简单，作为参考实现供叶测试与协议回归使用；
  *   - SRAM 版：同步读，是可用于综合/生产配置的实现。
  * 两者在读通路与响应级存在固有的时序差异（SRAM 多一拍），不做合并；
  * 行为对齐由审计套件的参数化用例与 icache_sram_test（生产几何）保证——
  * 修改任一实现后必须跑 csrc/Test/issue-audit-test 全套。
  */
object ysyx_26030103_ICacheFactory {
  def Create(
      config: ysyx_26030103_NPCConfig = ysyx_26030103_NPCConfig()
  ): ysyx_26030103_ICacheBase = {
    config.ICacheStorage match {
      case ysyx_26030103_CacheStorage.Reg =>
        Module(
          new ysyx_26030103_ICacheReg(
            Enable = config.ICacheEnable,
            BlockSizeLog2 = config.ICacheBlockSizeLog2,
            IndexBits = config.ICacheIndexBits,
            AddressWidth = config.AddressWidth,
            Vipt = config.MMU.cacheMode == "vipt",
            ViptAliasSafe = config.ICacheViptAliasHardware,
            ViptAliasScanMode = config.ICacheViptAliasScanMode,
            PMARegions = config.PMARegions
          )
        )
      case ysyx_26030103_CacheStorage.SRAM =>
        Module(
          new ysyx_26030103_ICacheSRAM(
            Enable = config.ICacheEnable,
            BlockSizeLog2 = config.ICacheBlockSizeLog2,
            IndexBits = config.ICacheIndexBits,
            AddressWidth = config.AddressWidth,
            Vipt = config.MMU.cacheMode == "vipt",
            ViptAliasSafe = config.ICacheViptAliasHardware,
            ViptAliasScanMode = config.ICacheViptAliasScanMode,
            PMARegions = config.PMARegions
          )
        )
      case ysyx_26030103_CacheStorage.SRAMNoBypass =>
        Module(
          new ysyx_26030103_ICacheSRAM(
            Enable = config.ICacheEnable,
            BlockSizeLog2 = config.ICacheBlockSizeLog2,
            IndexBits = config.ICacheIndexBits,
            AddressWidth = config.AddressWidth,
            Vipt = config.MMU.cacheMode == "vipt",
            ViptAliasSafe = config.ICacheViptAliasHardware,
            ViptAliasScanMode = config.ICacheViptAliasScanMode,
            Bypass = false,
            PMARegions = config.PMARegions
          )
        )
    }
  }
}
