package ysyx_26030103.infra

import chisel3._

/**
  * Protocol-complete termination for the CPU's inbound AXI port.
  *
  * The current SoC does not provide storage behind this port.  In particular,
  * forwarding a ChipLink MEM request to the CPU master port would route the
  * same 0xc0000000--0xffffffff address back to ChipLink and create a loop.
  * Rejecting the transaction is therefore preferable to either hanging the
  * requester or falsely acknowledging a write that was never committed.
  *
  * AXI write address and data channels are independent, so this slave accepts
  * them in either order and drains every W beat through WLAST before returning
  * DECERR.  Reads return ARLEN + 1 DECERR beats and preserve RID/RLAST while
  * respecting response backpressure.
  */
class ysyx_26030103_AXI5DMAErrorSlave(
    AddressWidth: Int = 32,
    DataWidth: Int = 32,
    IdWidth: Int = 4
) extends Module {
  val io = IO(new ysyx_26030103_AXI5Slave(AddressWidth, DataWidth, IdWidth))

  private val DECERR = "b11".U(2.W)

  io.ACLK := clock.asBool
  io.ARESETn := !reset.asBool

  // Write channel: AW and W are intentionally collected independently.
  val awHeld = RegInit(false.B)
  val awId = RegInit(0.U(IdWidth.W))
  val wLastSeen = RegInit(false.B)
  val bValid = RegInit(false.B)

  io.AW.AWREADY := !awHeld && !bValid
  io.W.WREADY := !wLastSeen && !bValid
  io.B.BID := awId
  io.B.BRESP := DECERR
  io.B.BVALID := bValid
  // Keep the response leaves visible in standalone Verilator regressions;
  // otherwise CIRCT is allowed to propagate these constants into the parent.
  dontTouch(io.B.BRESP)

  val awFire = io.AW.AWVALID && io.AW.AWREADY
  val wFire = io.W.WVALID && io.W.WREADY
  val wLastFire = wFire && io.W.WLAST
  val haveAWAfterThisCycle = awHeld || awFire
  val haveLastWAfterThisCycle = wLastSeen || wLastFire

  when(awFire) {
    awHeld := true.B
    awId := io.AW.AWID
  }
  when(wLastFire) {
    wLastSeen := true.B
  }
  when(!bValid && haveAWAfterThisCycle && haveLastWAfterThisCycle) {
    bValid := true.B
  }
  when(io.B.BVALID && io.B.BREADY) {
    awHeld := false.B
    wLastSeen := false.B
    bValid := false.B
  }

  // Read channel: one outstanding burst, with a stable response under
  // backpressure and exactly ARLEN + 1 beats.
  val readActive = RegInit(false.B)
  val readId = RegInit(0.U(IdWidth.W))
  val readLen = RegInit(0.U(8.W))
  val readBeat = RegInit(0.U(8.W))

  io.AR.ARREADY := !readActive
  io.R.RID := readId
  io.R.RDATA := 0.U
  io.R.RRESP := DECERR
  io.R.RLAST := readBeat === readLen
  io.R.RVALID := readActive
  dontTouch(io.R.RRESP)

  when(io.AR.ARVALID && io.AR.ARREADY) {
    readActive := true.B
    readId := io.AR.ARID
    readLen := io.AR.ARLEN
    readBeat := 0.U
  }
  when(io.R.RVALID && io.R.RREADY) {
    when(io.R.RLAST) {
      readActive := false.B
    }.otherwise {
      readBeat := readBeat + 1.U
    }
  }

}
