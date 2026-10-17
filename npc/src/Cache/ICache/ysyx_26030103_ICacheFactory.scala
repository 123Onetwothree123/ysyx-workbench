package ysyx_26030103.cache

import chisel3._
import _root_.ysyx_26030103.config._

/** 按配置选择指令缓存实现。
  *
  * 存储介质由 ICacheStorage 选择：寄存器阵列(Reg)或同步读SRAM(SRAM)。
  * 后续可在不改动调用方的前提下增加更多实现。
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
            BlockSizeLog2 = config.BlockSizeLog2,
            IndexBits = config.IndexBits,
            AddressWidth = config.AddressWidth,
            PMARegions = config.PMARegions
          )
        )
      case ysyx_26030103_CacheStorage.SRAM =>
        Module(
          new ysyx_26030103_ICacheSRAM(
            Enable = config.ICacheEnable,
            BlockSizeLog2 = config.BlockSizeLog2,
            IndexBits = config.IndexBits,
            AddressWidth = config.AddressWidth,
            PMARegions = config.PMARegions
          )
        )
      case ysyx_26030103_CacheStorage.SRAMNoBypass =>
        Module(
          new ysyx_26030103_ICacheSRAM(
            Enable = config.ICacheEnable,
            BlockSizeLog2 = config.BlockSizeLog2,
            IndexBits = config.IndexBits,
            AddressWidth = config.AddressWidth,
            Bypass = false,
            PMARegions = config.PMARegions
          )
        )
    }
  }
}
