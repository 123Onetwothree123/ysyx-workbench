package ysyx_26030103.test

import chisel3._
import _root_.ysyx_26030103.common.ysyx_26030103_PMARegion
import _root_.ysyx_26030103.infra._

/** Leaf RTL snapshots for the PLIC/DMA-LOCK regression tests.
  *
  * Each top is emitted by a separate elaborator so the C++ tests can select a
  * small leaf module instead of the full core.
  */
private object PLICHarnessArgs {
  def apply(args: Array[String]): Array[String] = {
    val targetDir = args
      .sliding(2)
      .collectFirst { case Array("--target-dir", value) => value }
      .getOrElse("build/rtl")
    Array("--target-dir", targetDir)
  }
}

object PLICClaimCompleteHarnessElaborate extends App {
  emitVerilog(
    new ysyx_26030103_PLIC(),
    PLICHarnessArgs(args)
  )
}

object WidthBridgeLockHarnessElaborate extends App {
  emitVerilog(
    new ysyx_26030103_AXI5WidthBridge(
      UpstreamAddressWidth = 64,
      DownstreamAddressWidth = 32,
      UpstreamDataWidth = 64,
      DownstreamDataWidth = 32,
      IDWidth = 4,
      TrackExclusive = true
    ),
    PLICHarnessArgs(args)
  )
}

/** Xbar instance deliberately uses a PMA map in which the PLIC window exists
  * but has Readable/Writable=false; that is the configuration that used to
  * bypass PMA and route to the dedicated PLIC port.
  */
object XbarPLICPmaHarnessElaborate extends App {
  private val PLICBase = 0x0c000000L
  private val PLICSize = 0x00300000L
  private val DeniedPLICPma = Seq(
    ysyx_26030103_PMARegion(
      Base = PLICBase,
      Size = PLICSize,
      Readable = false,
      Writable = false,
      Cacheable = false,
      Executable = false
    ),
    ysyx_26030103_PMARegion(
      Base = 0x80000000L,
      Size = 0x04000000L,
      Readable = true,
      Writable = true,
      Cacheable = true,
      Executable = true
    )
  )
  emitVerilog(
    new ysyx_26030103_AXI5Xbar(
      AddressWidth = 32,
      PMARegions = DeniedPLICPma,
      PLICBaseValue = BigInt(PLICBase),
      PLICSizeBytes = BigInt(PLICSize),
      DataWidth = 32,
      IDWidth = 4
    ),
    PLICHarnessArgs(args)
  )
}

object ArbiterLockHarnessElaborate extends App {
  emitVerilog(
    new ysyx_26030103_AXI5Arbiter(
      AddressWidth = 32,
      DataWidth = 32,
      IDWidth = 4,
      HardwareSnoop = false
    ),
    PLICHarnessArgs(args)
  )
}
