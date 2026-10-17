package ysyx_26030103.cache

import chisel3._
import _root_.ysyx_26030103.common.ysyx_26030103_PMARegion
import _root_.ysyx_26030103.common.ysyx_26030103_PhysicalMemoryMap
import _root_.ysyx_26030103.config._

/** 按配置选择数据缓存实现。
  *
  * 存储介质由 Storage 选择：寄存器阵列(Reg)或同步读SRAM(SRAM)。
  * 后续可在不改动调用方的前提下增加更多实现。
  */
object ysyx_26030103_DCacheFactory {
  def Create(
      Enable: Boolean = true,
      BlockSizeLog2: Int = 4,
      IndexBits: Int = 5,
      AddressWidth: Int = 32,
      CacheableBase: Long = 0x80000000L,
      CacheableMask: Long = 0x80000000L,
      PMARegions: Seq[ysyx_26030103_PMARegion] =
        ysyx_26030103_PhysicalMemoryMap.SoC(HasChipLink = false),
      Storage: ysyx_26030103_CacheStorage = ysyx_26030103_CacheStorage.Reg
  ): ysyx_26030103_DCacheBase = {
    Storage match {
      case ysyx_26030103_CacheStorage.Reg =>
        Module(
          new ysyx_26030103_DCacheReg(
            Enable = Enable,
            BlockSizeLog2 = BlockSizeLog2,
            IndexBits = IndexBits,
            AddressWidth = AddressWidth,
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
            CacheableBase = CacheableBase,
            CacheableMask = CacheableMask,
            Bypass = false,
            PMARegions = PMARegions
          )
        )
    }
  }
}
