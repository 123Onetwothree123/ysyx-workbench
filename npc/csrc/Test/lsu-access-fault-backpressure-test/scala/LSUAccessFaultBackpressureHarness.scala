package ysyx_26030103.test

import chisel3._
import _root_.ysyx_26030103.mem.ysyx_26030103_LSU

/** Independently elaborated production LSU with a controllable out.ready.
  *
  * In the complete CPU, WBU is permanently ready and whole-core optimization
  * removes this backpressure input.  Making the LSU the elaboration top keeps
  * the Decoupled boundary observable without changing its implementation.
  */
class LSUAccessFaultBackpressureHarness
    extends ysyx_26030103_LSU(
      WBufDepth = 1,
      DCacheEnable = false,
      DCacheBlockSizeLog2 = 4,
      DCacheIndexBits = 1
    )

object LSUAccessFaultBackpressureHarnessElaborate extends App {
  val targetDir = args
    .sliding(2)
    .collectFirst { case Array("--target-dir", value) => value }
    .getOrElse("build/rtl")
  emitVerilog(
    new LSUAccessFaultBackpressureHarness,
    Array("--target-dir", targetDir)
  )
}
