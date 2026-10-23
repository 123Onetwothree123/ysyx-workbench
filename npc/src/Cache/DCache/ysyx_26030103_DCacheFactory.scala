package ysyx_26030103.cache

import chisel3._
import _root_.ysyx_26030103.common.ysyx_26030103_PMARegion
import _root_.ysyx_26030103.common.ysyx_26030103_PhysicalMemoryMap
import _root_.ysyx_26030103.config._

/** 按配置选择数据缓存实现。
  *
  * 存储介质由 Storage 选择：寄存器阵列(Reg)或同步读SRAM(SRAM/SRAMNoBypass)。
  *
  * 双实现是**有意保留**的设计决定（2026-10-18 定稿）：
  *   - Reg 版：组合读、结构简单，作为参考实现供叶测试与协议回归使用；
  *   - SRAM 版：同步读，是可用于综合/生产配置的实现。
  * 两者在读通路与响应级存在固有的时序差异（SRAM 多一拍），不做合并；
  * 行为对齐由审计套件的参数化用例（同一激励源编译到三种介质）与随机化
  * 漂移守卫保证——修改任一实现后必须跑 csrc/Test/issue-audit-test 全套。
  */
object ysyx_26030103_DCacheFactory {
  def Create(
      Enable: Boolean = true,
      BlockSizeLog2: Int = 4,
      IndexBits: Int = 5,
      AddressWidth: Int = 32,
      DataWidth: Int = 32,
      CacheableBase: Long = 0x80000000L,
      CacheableMask: Long = 0x80000000L,
      PMARegions: Seq[ysyx_26030103_PMARegion] =
        ysyx_26030103_PhysicalMemoryMap.SoC(HasChipLink = false),
      Storage: ysyx_26030103_CacheStorage = ysyx_26030103_CacheStorage.Reg,
      Vipt: Boolean = false,
      ViptAliasSafe: Boolean = false,
      ViptAliasScanMode: String = "all_sets",
      DmaSnoop: Boolean = false,
      ViptAliasScanWidth: Int = 0,
      ViptAliasDirectoryEntries: Int = 0
  ): ysyx_26030103_DCacheBase = {
    Storage match {
      case ysyx_26030103_CacheStorage.Reg =>
        Module(
          new ysyx_26030103_DCacheReg(
            Enable = Enable,
            BlockSizeLog2 = BlockSizeLog2,
            IndexBits = IndexBits,
            AddressWidth = AddressWidth,
            DataWidth = DataWidth,
            Vipt = Vipt,
            ViptAliasSafe = ViptAliasSafe,
            ViptAliasScanMode = ViptAliasScanMode,
            DmaSnoop = DmaSnoop,
            ViptAliasScanWidth = ViptAliasScanWidth,
            ViptAliasDirectoryEntries = ViptAliasDirectoryEntries,
            CacheableBase = CacheableBase,
            CacheableMask = CacheableMask,
            PMARegions = PMARegions
          )
        )
      case ysyx_26030103_CacheStorage.SRAM =>
        Module(
          new ysyx_26030103_DCacheSRAM(
            Enable = Enable,
            BlockSizeLog2 = BlockSizeLog2,
            IndexBits = IndexBits,
            AddressWidth = AddressWidth,
            DataWidth = DataWidth,
            Vipt = Vipt,
            ViptAliasSafe = ViptAliasSafe,
            ViptAliasScanMode = ViptAliasScanMode,
            DmaSnoop = DmaSnoop,
            ViptAliasScanWidth = ViptAliasScanWidth,
            ViptAliasDirectoryEntries = ViptAliasDirectoryEntries,
            CacheableBase = CacheableBase,
            CacheableMask = CacheableMask,
            PMARegions = PMARegions
          )
        )
      case ysyx_26030103_CacheStorage.SRAMNoBypass =>
        Module(
          new ysyx_26030103_DCacheSRAM(
            Enable = Enable,
            BlockSizeLog2 = BlockSizeLog2,
            IndexBits = IndexBits,
            AddressWidth = AddressWidth,
            DataWidth = DataWidth,
            Vipt = Vipt,
            ViptAliasSafe = ViptAliasSafe,
            ViptAliasScanMode = ViptAliasScanMode,
            DmaSnoop = DmaSnoop,
            ViptAliasScanWidth = ViptAliasScanWidth,
            ViptAliasDirectoryEntries = ViptAliasDirectoryEntries,
            CacheableBase = CacheableBase,
            CacheableMask = CacheableMask,
            Bypass = false,
            PMARegions = PMARegions
          )
        )
    }
  }
}
