#include "Vysyx_26030103_AXI5Xbar.h"
#include "test_common.hpp"

#include <cstdint>
#include <string>
#include <verilated.h>

using DUT = Vysyx_26030103_AXI5Xbar;

static void defaults(DUT &dut) {
  dut.io_in_AW_AWADDR = 0;
  dut.io_in_AW_AWSIZE = 2;
  dut.io_in_AW_AWBURST = 1;
  dut.io_in_AW_AWVALID = 0;
  dut.io_in_W_WDATA = 0;
  dut.io_in_W_WSTRB = 0xf;
  dut.io_in_W_WLAST = 0;
  dut.io_in_W_WVALID = 0;
  dut.io_in_B_BREADY = 1;
  dut.io_in_AR_ARADDR = 0;
  dut.io_in_AR_ARID = 0;
  dut.io_in_AR_ARLEN = 0;
  dut.io_in_AR_ARSIZE = 2;
  dut.io_in_AR_ARBURST = 1;
  dut.io_in_AR_ARVALID = 0;
  dut.io_in_R_RREADY = 0;

  dut.io_SoCBus_AW_AWREADY = 1;
  dut.io_SoCBus_W_WREADY = 1;
  dut.io_SoCBus_B_BRESP = 0;
  dut.io_SoCBus_B_BVALID = 0;
  dut.io_SoCBus_AR_ARREADY = 0;
  dut.io_SoCBus_R_RDATA = 0;
  dut.io_SoCBus_R_RID = 0;
  dut.io_SoCBus_R_RRESP = 0;
  dut.io_SoCBus_R_RLAST = 0;
  dut.io_SoCBus_R_RVALID = 0;

  dut.io_CLINT_AW_AWREADY = 1;
  dut.io_CLINT_W_WREADY = 1;
  dut.io_CLINT_B_BRESP = 0;
  dut.io_CLINT_B_BVALID = 0;
  dut.io_CLINT_AR_ARREADY = 0;
  dut.io_CLINT_R_RDATA = 0;
  dut.io_CLINT_R_RID = 0;
  dut.io_CLINT_R_RRESP = 0;
  dut.io_CLINT_R_RLAST = 0;
  dut.io_CLINT_R_RVALID = 0;
}

static void accept_ar(DUT &dut, std::uint8_t id, std::uint32_t address,
                      std::uint8_t len) {
  dut.io_in_AR_ARID = id;
  dut.io_in_AR_ARADDR = address;
  dut.io_in_AR_ARLEN = len;
  dut.io_in_AR_ARVALID = 1;
  dut.eval();
  check(dut.io_in_AR_ARREADY,
        "Xbar did not accept a request into a free RID slot");
  tick(dut);
  dut.io_in_AR_ARVALID = 0;
}

static void check_local_decerr_baseline() {
  DUT dut;
  defaults(dut);
  reset(dut);
  dut.io_in_R_RREADY = 1;
  accept_ar(dut, 0, 0x20000000U, 3);

  unsigned beats = 0;
  bool saw_last = false;
  for (int cycle = 0; cycle < 12 && !saw_last; ++cycle) {
    dut.eval();
    if (dut.io_in_R_RVALID) {
      check(dut.io_in_R_RID == 0 && dut.io_in_R_RRESP == 3,
            "baseline local response metadata was corrupted");
      ++beats;
      if (dut.io_in_R_RLAST) {
        check(beats == 4,
              "baseline local DECERR returned the wrong burst length");
        saw_last = true;
      }
    }
    tick(dut);
  }
  check(saw_last, "baseline local DECERR burst did not terminate");
  dut.final();
}

static void check_local_decerr_interleave() {
  DUT dut;
  defaults(dut);
  reset(dut);

  // RID 0 is an unmapped four-beat burst.  It must return exactly four local
  // DECERR beats, irrespective of responses for another RID being interleaved.
  accept_ar(dut, 0, 0x20000000U, 3);
  tick(dut); // capture local beat 0 into the one-beat response buffer
  dut.eval();
  check(dut.io_in_R_RVALID && dut.io_in_R_RID == 0 && dut.io_in_R_RRESP == 3 &&
            !dut.io_in_R_RLAST,
        "Xbar did not buffer local DECERR beat 0");

  // While local beat 0 is backpressured, create a legal single-beat request in
  // RID 1 and issue it to the SoC bus.
  accept_ar(dut, 1, 0x80001000U, 0);
  dut.io_SoCBus_AR_ARREADY = 1;
  dut.eval();
  check(dut.io_SoCBus_AR_ARVALID && dut.io_SoCBus_AR_ARID == 1,
        "mapped RID 1 request was not issued while RID 0 held local DECERR");
  tick(dut);
  dut.io_SoCBus_AR_ARREADY = 0;

  // Pop local beat 0 and replace it on the same edge with the higher-priority
  // SoC response.  The local slot has already advanced once when beat 0 was
  // captured; it must not advance a second time on this replacement edge.
  dut.io_in_R_RREADY = 1;
  dut.io_SoCBus_R_RID = 1;
  dut.io_SoCBus_R_RDATA = 0x11223344U;
  dut.io_SoCBus_R_RRESP = 0;
  dut.io_SoCBus_R_RLAST = 1;
  dut.io_SoCBus_R_RVALID = 1;
  dut.eval();
  check(dut.io_in_R_RVALID && dut.io_in_R_RID == 0 && dut.io_SoCBus_R_RREADY,
        "test did not create the local-pop/SoC-capture overlap");
  tick(dut);
  dut.io_SoCBus_R_RVALID = 0;
  dut.io_SoCBus_R_RLAST = 0;

  // Consume RID 1.  The now-free buffer may capture the next local response on
  // the same edge.
  dut.eval();
  check(dut.io_in_R_RVALID && dut.io_in_R_RID == 1 &&
            dut.io_in_R_RDATA == 0x11223344U && dut.io_in_R_RLAST,
        "interleaved SoC response was corrupted");
  tick(dut);

  unsigned local_beats = 1; // beat 0 was consumed on the replacement edge
  bool saw_last = false;
  for (int cycle = 0; cycle < 12 && !saw_last; ++cycle) {
    dut.eval();
    if (dut.io_in_R_RVALID) {
      check(dut.io_in_R_RID == 0 && dut.io_in_R_RRESP == 3,
            "unexpected response appeared while draining local DECERR");
      ++local_beats;
      if (dut.io_in_R_RLAST) {
        check(local_beats == 4, "local DECERR asserted RLAST after " +
                                    std::to_string(local_beats) +
                                    " beats; ARLEN=3 requires exactly 4");
        saw_last = true;
      }
    }
    tick(dut);
  }
  check(saw_last, "local DECERR burst did not terminate within four beats");
  dut.final();
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  int failures = 0;
  failures += run_test("Xbar local DECERR baseline length",
                       check_local_decerr_baseline);
  failures += run_test("Xbar local DECERR survives interleaved SoC response",
                       check_local_decerr_interleave);
  return failures == 0 ? 0 : 1;
}
