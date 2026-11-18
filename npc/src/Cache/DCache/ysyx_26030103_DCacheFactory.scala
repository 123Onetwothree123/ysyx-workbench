package ysyx_26030103.cache

import chisel3._
import _root_.ysyx_26030103.common.ysyx_26030103_PMARegion
import _root_.ysyx_26030103.common.ysyx_26030103_PhysicalMemoryMap
import _root_.ysyx_26030103.common.ysyx_26030103_KconfigDefaults
import _root_.ysyx_26030103.config._

/** 按配置选择数据缓存实现。
  *
  * 存储介质由 Storage 选择：寄存器阵列(Reg)或同步读SRAM(SRAM/SRAMNoBypass)。
  * 公共 core + 两个存储适配：Reg 组合读，SRAM 同步读（响应多一拍）。
  * 修改任一实现后必须跑 csrc/Test/rtl/issue-audit-test 全套。
  */
object ysyx_26030103_DCacheFactory {
  def Create(
      Enable: Boolean = true,
      BlockSizeLog2: Int = ysyx_26030103_KconfigDefaults.NpcDCacheBlockSizeLog2,
      IndexBits: Int = ysyx_26030103_KconfigDefaults.NpcDCacheIndexBits,
      AddressWidth: Int = ysyx_26030103_KconfigDefaults.NpcAddressWidth,
      DataWidth: Int = ysyx_26030103_KconfigDefaults.NpcInternalBusWidth,
      BusDataWidth: Int = ysyx_26030103_KconfigDefaults.NpcInternalBusWidth,
      CacheableBase: Long = 0L,
      CacheableMask: Long = 0L,
      DrainTimeout: Int = ysyx_26030103_KconfigDefaults.DCacheDrainTimeout,
      PMARegions: Seq[ysyx_26030103_PMARegion] =
        ysyx_26030103_PhysicalMemoryMap.SoC(HasChipLink = false),
      Storage: ysyx_26030103_CacheStorage = ysyx_26030103_CacheStorage.Reg,
      VIPT: Boolean = false,
      VIPTAliasSafe: Boolean = false,
      VIPTAliasScanMode: String = "all_sets",
      DMASnoop: Boolean = false,
      VIPTAliasScanWidth: Int = 0,
      VIPTAliasDirectoryEntries: Int = 0,
      VIPTAliasEventQueueDepth: Int =
        ysyx_26030103_KconfigDefaults.MMUCacheVIPTAliasEventQueueDepth
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
            BusDataWidth = BusDataWidth,
            VIPT = VIPT,
            VIPTAliasSafe = VIPTAliasSafe,
            VIPTAliasScanMode = VIPTAliasScanMode,
            DMASnoop = DMASnoop,
            VIPTAliasScanWidth = VIPTAliasScanWidth,
            VIPTAliasDirectoryEntries = VIPTAliasDirectoryEntries,
            VIPTAliasEventQueueDepth = VIPTAliasEventQueueDepth,
            CacheableBase = CacheableBase,
            CacheableMask = CacheableMask,
            DrainTimeout = DrainTimeout,
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
            BusDataWidth = BusDataWidth,
            VIPT = VIPT,
            VIPTAliasSafe = VIPTAliasSafe,
            VIPTAliasScanMode = VIPTAliasScanMode,
            DMASnoop = DMASnoop,
            VIPTAliasScanWidth = VIPTAliasScanWidth,
            VIPTAliasDirectoryEntries = VIPTAliasDirectoryEntries,
            VIPTAliasEventQueueDepth = VIPTAliasEventQueueDepth,
            CacheableBase = CacheableBase,
            CacheableMask = CacheableMask,
            DrainTimeout = DrainTimeout,
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
            BusDataWidth = BusDataWidth,
            VIPT = VIPT,
            VIPTAliasSafe = VIPTAliasSafe,
            VIPTAliasScanMode = VIPTAliasScanMode,
            DMASnoop = DMASnoop,
            VIPTAliasScanWidth = VIPTAliasScanWidth,
            VIPTAliasDirectoryEntries = VIPTAliasDirectoryEntries,
            VIPTAliasEventQueueDepth = VIPTAliasEventQueueDepth,
            CacheableBase = CacheableBase,
            CacheableMask = CacheableMask,
            Bypass = false,
            DrainTimeout = DrainTimeout,
            PMARegions = PMARegions
          )
        )
    }
  }
}
