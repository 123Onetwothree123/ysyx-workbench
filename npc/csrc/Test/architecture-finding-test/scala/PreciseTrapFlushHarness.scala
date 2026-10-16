package ysyx_26030103.test

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._
import _root_.ysyx_26030103.infra._
import _root_.ysyx_26030103.mem.ysyx_26030103_LSU

/** Production LSU and StageConnect wired like the EXU-to-LSU boundary. */
class PreciseTrapFlushHarness extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ysyx_26030103_EXUMessage))
    val out = Decoupled(new ysyx_26030103_EXUMessage)

    val arvalid = Output(Bool())
    val araddr = Output(UInt(32.W))
    val arready = Input(Bool())
    val rvalid = Input(Bool())
    val rdata = Input(UInt(32.W))
    val rresp = Input(UInt(2.W))
    val rlast = Input(Bool())
    val rready = Output(Bool())

    val mem_trap_commit = Output(Bool())
    val flush_exmem = Output(Bool())
  })

  val lsu = Module(
    new ysyx_26030103_LSU(
      WBufDepth = 1,
      DCacheEnable = false,
      DCacheBlockSizeLog2 = 4,
      DCacheIndexBits = 1
    )
  )

  // Mirrors production's three-argument overload, which sets
  // flushCurrent=flush.  The test verifies that an older LSU trap kills this
  // younger item even while LSU is not ready.
  ysyx_26030103_StageConnect(io.in, lsu.io.in, lsu.io.FlushEXMEM)

  lsu.io.out.ready := io.out.ready
  io.out.valid := lsu.io.out.valid
  io.out.bits := lsu.io.out.bits

  lsu.io.DataBus.AW.AWREADY := false.B
  lsu.io.DataBus.W.WREADY := false.B
  lsu.io.DataBus.B.BID := 0.U
  lsu.io.DataBus.B.BRESP := 0.U
  lsu.io.DataBus.B.BVALID := false.B

  io.arvalid := lsu.io.DataBus.AR.ARVALID
  io.araddr := lsu.io.DataBus.AR.ARADDR
  lsu.io.DataBus.AR.ARREADY := io.arready
  lsu.io.DataBus.R.RID := 0.U
  lsu.io.DataBus.R.RDATA := io.rdata
  lsu.io.DataBus.R.RRESP := io.rresp
  lsu.io.DataBus.R.RLAST := io.rlast
  lsu.io.DataBus.R.RVALID := io.rvalid
  io.rready := lsu.io.DataBus.R.RREADY
  lsu.io.DCacheFlush := false.B

  io.mem_trap_commit := lsu.io.MemTrapCommit
  io.flush_exmem := lsu.io.FlushEXMEM
}

object PreciseTrapFlushHarnessElaborate extends App {
  val targetDir = args
    .sliding(2)
    .collectFirst { case Array("--target-dir", value) => value }
    .getOrElse("build/trap-flush-rtl")
  emitVerilog(new PreciseTrapFlushHarness, Array("--target-dir", targetDir))
}
