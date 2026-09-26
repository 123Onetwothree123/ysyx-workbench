package ysyx_26030103.test

import chisel3._
import _root_.ysyx_26030103.common.ysyx_26030103_PMARegion
import _root_.ysyx_26030103.mem.ysyx_26030103_DCache

/** A legal word ends at this PMA's EndExclusive, while its 16-byte line does
  * not.  The demand must therefore use the uncached one-beat path.
  */
class DCachePmaLineBoundaryHarness
    extends ysyx_26030103_DCache(
      Enable = true,
      BlockSizeLog2 = 4,
      IndexBits = 1,
      AddressWidth = 32,
      CacheableBase = 0x80000000L,
      CacheableMask = 0x80000000L,
      PMARegions = Seq(
        ysyx_26030103_PMARegion(
          Base = 0x80000000L,
          Size = 0x0cL,
          Readable = true,
          Writable = true,
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
