// AXI5WidthBridge LOCK association regression.
//
// The internal AXI bundle has no LOCK field, so the bridge must latch the
// external AWLOCK/ARLOCK on the external address handshake and keep it tied to
// the internal transaction until the matching B / final R.  The old top-level
// wiring sampled io.slave_awlock/arlock live, which loses exclusive requests
// once the external master deasserts LOCK after the handshake.
#include "Vysyx_26030103_AXI5WidthBridge.h"
#include "test_common.hpp"
#include <verilated.h>

using DUT = Vysyx_26030103_AXI5WidthBridge;

static void defaults(DUT &dut) {
  dut.io_in_AW_AWVALID = 0;
  dut.io_in_AW_AWADDR = 0;
  dut.io_in_AW_AWID = 0;
  dut.io_in_AW_AWLEN = 0;
  dut.io_in_AW_AWSIZE = 3;
  dut.io_in_AW_AWBURST = 1;
  dut.io_in_AW_AWPROT = 0;
  dut.io_in_AW_AWCACHE = 0;
  dut.io_in_AW_AWQOS = 0;
  dut.io_in_W_WVALID = 0;
  dut.io_in_W_WDATA = 0;
  dut.io_in_W_WSTRB = 0;
  dut.io_in_W_WLAST = 0;
  dut.io_in_B_BREADY = 0;
  dut.io_in_AR_ARVALID = 0;
  dut.io_in_AR_ARADDR = 0;
  dut.io_in_AR_ARID = 0;
  dut.io_in_AR_ARLEN = 0;
  dut.io_in_AR_ARSIZE = 3;
  dut.io_in_AR_ARBURST = 1;
  dut.io_in_AR_ARPROT = 0;
  dut.io_in_AR_ARCACHE = 0;
  dut.io_in_AR_ARQOS = 0;
  dut.io_in_R_RREADY = 0;
  dut.io_out_AW_AWREADY = 1;
  dut.io_out_W_WREADY = 1;
  dut.io_out_B_BVALID = 0;
  dut.io_out_B_BID = 1;
  dut.io_out_B_BRESP = 0;
  dut.io_out_AR_ARREADY = 1;
  dut.io_out_R_RVALID = 0;
  dut.io_out_R_RID = 1;
  dut.io_out_R_RDATA = 0;
  dut.io_out_R_RRESP = 0;
  dut.io_out_R_RLAST = 0;
  dut.io_inAWLock = 0;
  dut.io_inARLock = 0;
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  int status = 0;

  status |= run_test("WidthBridge latches AWLOCK for all internal chunks",
                     [] {
    DUT dut;
    defaults(dut);
    reset(dut);

    // External AW handshake with LOCK=1, then LOCK is immediately deasserted.
    dut.io_in_AW_AWVALID = 1;
    dut.io_in_AW_AWADDR = 0x1000;
    dut.io_in_AW_AWID = 1;
    dut.io_in_AW_AWLEN = 0;
    dut.io_in_AW_AWSIZE = 3; // one 8-byte beat, split into two 4-byte chunks
    dut.io_in_AW_AWBURST = 1;
    dut.io_inAWLock = 1;
    dut.eval();
    check(dut.io_in_AW_AWREADY, "bridge did not accept the exclusive AW");
    tick(dut);
    dut.io_in_AW_AWVALID = 0;
    dut.io_inAWLock = 0;
    dut.eval();
    check(dut.io_outAWLock,
          "AWLOCK was not latched at the external AW handshake");

    // Supply the single upstream W beat.  Both downstream chunks must still
    // observe the latched exclusive attribute.
    dut.io_in_W_WVALID = 1;
    dut.io_in_W_WDATA = 0x1122334455667788ULL;
    dut.io_in_W_WSTRB = 0xff;
    dut.io_in_W_WLAST = 1;

    wait_until(dut, [&] {
      return dut.io_out_AW_AWVALID && dut.io_out_W_WVALID;
    }, "bridge did not issue the first internal chunk");
    check(dut.io_outAWLock,
          "AWLOCK disappeared before the first internal chunk");
    check(dut.io_out_AW_AWLEN == 0 && dut.io_out_AW_AWSIZE == 2,
          "internal chunk shape is not single-beat 32-bit");
    tick(dut); // accept first chunk AW+W
    dut.io_in_W_WVALID = 0;

    // Complete the first internal transaction.
    dut.io_out_B_BVALID = 1;
    dut.io_out_B_BID = 1;
    dut.io_out_B_BRESP = 0;
    dut.eval();
    check(dut.io_out_B_BREADY, "bridge did not accept first chunk B");
    tick(dut);
    dut.io_out_B_BVALID = 0;

    // Second chunk must still see the latched AWLOCK even though the external
    // pin has been low for several cycles.
    wait_until(dut, [&] {
      return dut.io_out_AW_AWVALID && dut.io_out_W_WVALID;
    }, "bridge did not issue the second internal chunk");
    check(dut.io_outAWLock,
          "AWLOCK disappeared before the second internal chunk");
    tick(dut);

    dut.io_out_B_BVALID = 1;
    dut.io_out_B_BID = 1;
    dut.io_out_B_BRESP = 0;
    dut.eval();
    check(dut.io_out_B_BREADY, "bridge did not accept second chunk B");
    tick(dut);
    dut.io_out_B_BVALID = 0;

    wait_until(dut, [&] { return dut.io_in_B_BVALID; },
               "bridge did not aggregate the upstream B");
    check(dut.io_outAWLock,
          "AWLOCK cleared before the upstream B completed");
    dut.io_in_B_BREADY = 1;
    tick(dut);
    dut.io_in_B_BREADY = 0;
    dut.eval();
    check(!dut.io_outAWLock,
          "AWLOCK stayed asserted after the upstream write completed");
    dut.final();
  });

  status |= run_test("WidthBridge latches ARLOCK until final upstream R",
                     [] {
    DUT dut;
    defaults(dut);
    reset(dut);

    dut.io_in_AR_ARVALID = 1;
    dut.io_in_AR_ARADDR = 0x2000;
    dut.io_in_AR_ARID = 1;
    dut.io_in_AR_ARLEN = 0;
    dut.io_in_AR_ARSIZE = 3; // one 8-byte beat, split into two 4-byte chunks
    dut.io_in_AR_ARBURST = 1;
    dut.io_inARLock = 1;
    dut.eval();
    check(dut.io_in_AR_ARREADY, "bridge did not accept the exclusive AR");
    tick(dut);
    dut.io_in_AR_ARVALID = 0;
    dut.io_inARLock = 0;
    dut.eval();
    check(dut.io_outARLock,
          "ARLOCK was not latched at the external AR handshake");

    // First chunk.
    wait_until(dut, [&] { return dut.io_out_AR_ARVALID; },
               "bridge did not issue the first internal read chunk");
    check(dut.io_outARLock,
          "ARLOCK disappeared before the first internal read chunk");
    tick(dut);
    dut.io_out_R_RVALID = 1;
    dut.io_out_R_RID = 1;
    dut.io_out_R_RDATA = 0x55667788;
    dut.io_out_R_RRESP = 0;
    dut.io_out_R_RLAST = 1;
    dut.eval();
    check(dut.io_out_R_RREADY, "bridge did not accept first read chunk R");
    tick(dut);
    dut.io_out_R_RVALID = 0;

    // Second chunk.
    wait_until(dut, [&] { return dut.io_out_AR_ARVALID; },
               "bridge did not issue the second internal read chunk");
    check(dut.io_outARLock,
          "ARLOCK disappeared before the second internal read chunk");
    tick(dut);
    dut.io_out_R_RVALID = 1;
    dut.io_out_R_RID = 1;
    dut.io_out_R_RDATA = 0x11223344;
    dut.io_out_R_RRESP = 0;
    dut.io_out_R_RLAST = 1;
    dut.eval();
    check(dut.io_out_R_RREADY, "bridge did not accept second read chunk R");
    tick(dut);
    dut.io_out_R_RVALID = 0;

    wait_until(dut, [&] { return dut.io_in_R_RVALID; },
               "bridge did not assemble the upstream R");
    check(dut.io_outARLock,
          "ARLOCK cleared before the final upstream R completed");
    check(dut.io_in_R_RDATA == 0x1122334455667788ULL,
          "assembled read data was corrupted");
    check(dut.io_in_R_RLAST, "assembled upstream RLAST was not asserted");
    dut.io_in_R_RREADY = 1;
    tick(dut);
    dut.io_in_R_RREADY = 0;
    dut.eval();
    check(!dut.io_outARLock,
          "ARLOCK stayed asserted after the final upstream R");
    dut.final();
  });

  return status;
}
