package ysyx_26030103.test

import chisel3._
import _root_.ysyx_26030103.common.ysyx_26030103_PMARegion
import _root_.ysyx_26030103.ifu.ysyx_26030103_ICache

/** A deliberately non-word-aligned executable PMA region. Address 0x1000 is
  * a complete instruction word inside the region, while a word beginning at
  * 0x1004 crosses EndExclusive=0x1005.
  */
class ICachePmaBoundaryHarness
    extends ysyx_26030103_ICache(
      Enable = true,
      BlockSizeLog2 = 4,
      IndexBits = 1,
      AddressWidth = 32,
      PMARegions = Seq(
        ysyx_26030103_PMARegion(
          Base = 0x1000L,
          Size = 5L,
          Readable = true,
          Writable = false,
          Cacheable = false,
          Executable = true
        )
      )
    )

object ICachePmaBoundaryHarnessElaborate extends App {
  val targetDir = args
    .sliding(2)
    .collectFirst { case Array("--target-dir", value) => value }
    .getOrElse("build/rtl")
  emitVerilog(new ICachePmaBoundaryHarness, Array("--target-dir", targetDir))
}
