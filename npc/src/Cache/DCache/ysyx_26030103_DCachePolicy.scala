package ysyx_26030103.cache

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._

/** DCache 共用分配策略：PMA Readable && Cacheable，且整条回填行须落在同一可缓存区域内；
  * 否则该需求退化为单拍非缓存读，避免在 PMA 边界上扩展成非法突发传输。
  */
object ysyx_26030103_DCachePolicy {
  def LineContainedInCacheableRegion(
      address: UInt,
      AddressWidth: Int,
      BlockSizeLog2: Int,
      PMARegions: Seq[ysyx_26030103_PMARegion]
  ): Bool = {
    require(
      BlockSizeLog2 >= 2,
      "DCache cache line must contain at least one 32-bit word"
    )
    require(
      BlockSizeLog2 < AddressWidth,
      "DCache cache line must fit in the address space"
    )
    val lineBase = Cat(
      0.U(1.W),
      address(AddressWidth - 1, BlockSizeLog2),
      0.U(BlockSizeLog2.W)
    )
    val lineLast = lineBase +
      ((BigInt(1) << BlockSizeLog2) - 1).U((AddressWidth + 1).W)
    ysyx_26030103_AnyOf(
      PMARegions
        .filter(region => region.Readable && region.Cacheable)
        .map { region =>
          lineBase >= BigInt(region.Base).U((AddressWidth + 1).W) &&
          lineLast < BigInt(region.EndExclusive).U((AddressWidth + 1).W)
        }
    )
  }

  def IsCacheable(
      address: UInt,
      Enable: Boolean,
      AddressWidth: Int,
      BlockSizeLog2: Int,
      CacheableBase: Long,
      CacheableMask: Long,
      PMARegions: Seq[ysyx_26030103_PMARegion]
  ): Bool = {
    if (Enable) {
      val configured =
        (address & CacheableMask.U(AddressWidth.W)) ===
          CacheableBase.U(AddressWidth.W)
      configured && LineContainedInCacheableRegion(
        address,
        AddressWidth,
        BlockSizeLog2,
        PMARegions
      )
    } else false.B
  }
}
