package ysyx_26030103.test

import chisel3._
import _root_.ysyx_26030103.common.ysyx_26030103_PMARegion
import _root_.ysyx_26030103.mem.ysyx_26030103_DCache

/** Covers all three PMA decisions made by DCache: a cacheable line which
  * crosses a region boundary, an explicitly uncacheable readable region, and
  * a cacheable region outside the historical 0x8/0xa address windows.
  */
class DCachePmaLineBoundaryHarness
    extends ysyx_26030103_DCache(
      Enable = true,
      BlockSizeLog2 = 4,
      IndexBits = 1,
      AddressWidth = 32,
      // A zero mask disables the optional address-range filter. PMA remains
      // authoritative for whether an address may allocate a cache line.
      CacheableBase = 0x00000000L,
      CacheableMask = 0x00000000L,
      PMARegions = Seq(
        ysyx_26030103_PMARegion(
          Base = 0x81000000L,
          Size = 0x0cL,
          Readable = true,
          Writable = true,
          Cacheable = true,
          Executable = false
        ),
        ysyx_26030103_PMARegion(
          Base = 0x80000000L,
          Size = 0x100L,
          Readable = true,
          Writable = true,
          Cacheable = false,
          Executable = false
        ),
        ysyx_26030103_PMARegion(
          Base = 0x30000000L,
          Size = 0x100L,
          Readable = true,
          Writable = false,
          Cacheable = true,
          Executable = false
        )
      )
    )

object DCachePmaLineBoundaryHarnessElaborate extends App {
  val targetDir = args
    .sliding(2)
    .collectFirst { case Array("--target-dir", value) => value }
    .getOrElse("build/rtl")
  emitVerilog(
    new DCachePmaLineBoundaryHarness,
    Array("--target-dir", targetDir)
  )
}
