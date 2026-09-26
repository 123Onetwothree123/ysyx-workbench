package ysyx_26030103.test

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common.ysyx_26030103_StageConnect

/** Test-only wrapper around the production StageConnect helper. */
class StageConnectHarness extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(UInt(32.W)))
    val out = Decoupled(UInt(32.W))
    val flush = Input(Bool())
    val flushCurrent = Input(Bool())
  })

  ysyx_26030103_StageConnect(
    io.in,
    io.out,
    io.flush,
    flushCurrent = io.flushCurrent
  )
}

object StageConnectHarnessElaborate extends App {
  val targetDir = args
    .sliding(2)
    .collectFirst { case Array("--target-dir", value) => value }
    .getOrElse("build/stage-connect-rtl")
  emitVerilog(new StageConnectHarness, Array("--target-dir", targetDir))
}
