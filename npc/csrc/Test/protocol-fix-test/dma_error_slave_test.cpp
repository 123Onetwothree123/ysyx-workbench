#include "Vysyx_26030103_AXI5DMAErrorSlave.h"
#include "test_common.hpp"
#include <verilated.h>

#include <cstdint>

using DUT = Vysyx_26030103_AXI5DMAErrorSlave;

static void defaults(DUT &dut) {
  dut.io_AW_AWID = 0;
  dut.io_AW_AWVALID = 0;

  dut.io_W_WLAST = 0;
  dut.io_W_WVALID = 0;
  dut.io_B_BREADY = 0;

  dut.io_AR_ARID = 0;
  dut.io_AR_ARLEN = 0;
  dut.io_AR_ARVALID = 0;
  dut.io_R_RREADY = 0;
}

static void accept_b(DUT &dut) {
  wait_until(dut, [&] { return dut.io_B_BVALID; },
             "DMA error slave never returned B");
  check(dut.io_B_BRESP == 3, "DMA write did not return DECERR");
  dut.io_B_BREADY = 1;
  tick(dut);
  dut.io_B_BREADY = 0;
  dut.eval();
  check(!dut.io_B_BVALID, "DMA BVALID did not clear after handshake");
}

static void test_address_then_burst(DUT &dut) {
  dut.io_AW_AWID = 0xa;
  dut.io_AW_AWVALID = 1;
  dut.eval();
  check(dut.io_AW_AWREADY, "DMA slave did not accept AW");
  tick(dut);
  dut.io_AW_AWVALID = 0;

  for (unsigned beat = 0; beat < 3; ++beat) {
    dut.io_W_WLAST = beat == 2;
    dut.io_W_WVALID = 1;
    dut.eval();
    check(dut.io_W_WREADY, "DMA slave stopped draining a write burst");
    check(!dut.io_B_BVALID,
          "DMA slave responded before accepting the final W beat");
    tick(dut);
  }
  dut.io_W_WVALID = 0;
  dut.io_W_WLAST = 0;
  dut.eval();
  check(dut.io_B_BVALID && dut.io_B_BRESP == 3 && dut.io_B_BID == 0xa,
        "DMA burst response did not preserve BID/DECERR");

  // B must remain stable for an arbitrarily backpressured requester.
  for (int cycle = 0; cycle < 3; ++cycle) {
    tick(dut);
    check(dut.io_B_BVALID && dut.io_B_BRESP == 3 && dut.io_B_BID == 0xa,
          "DMA B response changed while BREADY was low");
  }
  accept_b(dut);
}

static void test_data_before_address(DUT &dut) {
  dut.io_W_WLAST = 1;
  dut.io_W_WVALID = 1;
  dut.eval();
  check(dut.io_W_WREADY, "DMA slave rejected W-before-AW");
  tick(dut);
  dut.io_W_WVALID = 0;
  dut.io_W_WLAST = 0;
  check(!dut.io_B_BVALID,
        "DMA slave responded to W-before-AW without an address");

  dut.io_AW_AWID = 3;
  dut.io_AW_AWVALID = 1;
  dut.eval();
  check(dut.io_AW_AWREADY,
        "DMA slave did not accept AW after an early W channel");
  tick(dut);
  dut.io_AW_AWVALID = 0;
  dut.eval();
  check(dut.io_B_BVALID && dut.io_B_BID == 3 && dut.io_B_BRESP == 3,
        "DMA W-before-AW transaction did not complete with DECERR");
  accept_b(dut);
}

static void test_read_burst_and_backpressure(DUT &dut) {
  dut.io_AR_ARID = 5;
  dut.io_AR_ARLEN = 2;
  dut.io_AR_ARVALID = 1;
  dut.eval();
  check(dut.io_AR_ARREADY, "DMA slave did not accept AR");
  tick(dut);
  dut.io_AR_ARVALID = 0;
  dut.eval();

  check(dut.io_R_RVALID && dut.io_R_RID == 5 && dut.io_R_RRESP == 3 &&
            !dut.io_R_RLAST,
        "DMA slave produced an invalid first DECERR read beat");
  for (int cycle = 0; cycle < 3; ++cycle) {
    tick(dut);
    check(dut.io_R_RVALID && dut.io_R_RID == 5 && dut.io_R_RRESP == 3 &&
              !dut.io_R_RLAST,
          "DMA R response changed while RREADY was low");
  }

  dut.io_R_RREADY = 1;
  for (unsigned beat = 0; beat < 3; ++beat) {
    dut.eval();
    check(dut.io_R_RVALID && dut.io_R_RRESP == 3 &&
              dut.io_R_RID == 5 && dut.io_R_RLAST == (beat == 2),
          "DMA read burst length/RLAST was incorrect");
    tick(dut);
  }
  dut.io_R_RREADY = 0;
  dut.eval();
  check(!dut.io_R_RVALID,
        "DMA RVALID did not clear after ARLEN+1 handshakes");
}

static void test_independent_read_and_write(DUT &dut) {
  dut.io_AW_AWID = 7;
  dut.io_AW_AWVALID = 1;
  dut.io_W_WVALID = 1;
  dut.io_W_WLAST = 1;
  dut.io_AR_ARID = 8;
  dut.io_AR_ARLEN = 0;
  dut.io_AR_ARVALID = 1;
  dut.eval();
  check(dut.io_AW_AWREADY && dut.io_W_WREADY && dut.io_AR_ARREADY,
        "DMA slave did not accept independent read/write channels");
  tick(dut);
  dut.io_AW_AWVALID = 0;
  dut.io_W_WVALID = 0;
  dut.io_W_WLAST = 0;
  dut.io_AR_ARVALID = 0;
  dut.eval();
  check(dut.io_B_BVALID && dut.io_R_RVALID && dut.io_R_RLAST,
        "DMA slave failed to make progress on simultaneous read/write");

  dut.io_B_BREADY = 1;
  dut.io_R_RREADY = 1;
  tick(dut);
  dut.io_B_BREADY = 0;
  dut.io_R_RREADY = 0;
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  return run_test("DMA inbound AXI DECERR termination", [] {
    DUT dut;
    defaults(dut);
    reset(dut);
    check(dut.io_AW_AWREADY && dut.io_W_WREADY && dut.io_AR_ARREADY,
          "DMA error slave was not ready after reset");

    test_address_then_burst(dut);
    test_data_before_address(dut);
    test_read_burst_and_backpressure(dut);
    test_independent_read_and_write(dut);
    dut.final();
  });
}
