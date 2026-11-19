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

    val MemTrapCommit = Output(Bool())
    val FlushExMem = Output(Bool())
  })

  val LSU = Module(
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
  ysyx_26030103_StageConnect(io.in, LSU.io.in, LSU.io.FlushEXMEM)

  LSU.io.out.ready := io.out.ready
  io.out.valid := LSU.io.out.valid
  io.out.bits := LSU.io.out.bits

  LSU.io.DataBus.AW.AWREADY := false.B
  LSU.io.DataBus.W.WREADY := false.B
  LSU.io.DataBus.B.BID := 0.U
  LSU.io.DataBus.B.BRESP := 0.U
  LSU.io.DataBus.B.BVALID := false.B

  io.arvalid := LSU.io.DataBus.AR.ARVALID
  io.araddr := LSU.io.DataBus.AR.ARADDR
  LSU.io.DataBus.AR.ARREADY := io.arready
  LSU.io.DataBus.R.RID := 0.U
  LSU.io.DataBus.R.RDATA := io.rdata
  LSU.io.DataBus.R.RRESP := io.rresp
  LSU.io.DataBus.R.RLAST := io.rlast
  LSU.io.DataBus.R.RVALID := io.rvalid
  io.rready := LSU.io.DataBus.R.RREADY
  LSU.io.DCacheFlush := false.B
  LSU.io.BusQuiet := false.B
  LSU.io.ReservationClear := false.B
  LSU.io.DCacheDMASnoopValid := false.B
  LSU.io.DCacheDMASnoopStart := 0.U
  LSU.io.DCacheDMASnoopEnd := 0.U
  LSU.io.VIPTPAddress := 0.U
  LSU.io.VIPTPAddressValid := false.B
  LSU.io.VIPTPAddressVirtualAddress := 0.U
  LSU.io.VIPTKill := false.B
  LSU.io.PMPFault := false.B

  io.MemTrapCommit := LSU.io.MemTrapCommit
  io.FlushExMem := LSU.io.FlushEXMEM
}

object PreciseTrapFlushHarnessElaborate extends App {
  val targetDir = args
    .sliding(2)
    .collectFirst { case Array("--target-dir", value) => value }
    .getOrElse("build/trap-flush-rtl")
  emitVerilog(new PreciseTrapFlushHarness, Array("--target-dir", targetDir))
}
