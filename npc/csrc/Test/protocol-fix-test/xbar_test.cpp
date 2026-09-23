#include "Vysyx_26030103_AXI5Xbar.h"
#include "Vysyx_26030103_AXI5Xbar___024root.h"
#include "test_common.hpp"
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
  dut.io_in_AR_ARLEN = 0;
  dut.io_in_AR_ARSIZE = 2;
  dut.io_in_AR_ARBURST = 1;
  dut.io_in_AR_ARVALID = 0;
  dut.io_in_R_RREADY = 1;

  dut.io_SoCBus_AW_AWREADY = 1;
  dut.io_SoCBus_W_WREADY = 1;
  dut.io_SoCBus_B_BRESP = 0;
  dut.io_SoCBus_B_BVALID = 0;
  dut.io_SoCBus_AR_ARREADY = 0;
  dut.io_SoCBus_R_RDATA = 0;
  dut.io_SoCBus_R_RRESP = 0;
  dut.io_SoCBus_R_RLAST = 0;
  dut.io_SoCBus_R_RVALID = 0;

  dut.io_CLINT_AW_AWREADY = 1;
  dut.io_CLINT_W_WREADY = 1;
  dut.io_CLINT_B_BRESP = 0;
  dut.io_CLINT_B_BVALID = 0;
  dut.io_CLINT_AR_ARREADY = 0;
  dut.io_CLINT_R_RDATA = 0;
  dut.io_CLINT_R_RRESP = 0;
  dut.io_CLINT_R_RLAST = 0;
  dut.io_CLINT_R_RVALID = 0;
}

static void external_burst(DUT &dut) {
  dut.io_in_AW_AWADDR = 0x80000400U;
  dut.io_in_AW_AWVALID = 1;
  dut.io_in_W_WDATA = 0x11111111U;
  dut.io_in_W_WLAST = 0;
  dut.io_in_W_WVALID = 1;
  tick(dut); // collect AW and first W
  dut.io_in_AW_AWVALID = 0;

  dut.eval();
  check(dut.io_SoCBus_AW_AWVALID && dut.io_SoCBus_W_WVALID,
        "xbar did not forward external AW and first W beat");
  tick(dut);

  dut.io_in_W_WDATA = 0x22222222U;
  dut.io_in_W_WLAST = 0;
  dut.eval();
  check(dut.io_in_W_WREADY && dut.io_SoCBus_W_WVALID,
        "xbar stopped external burst before WLAST");
  tick(dut);

  dut.io_in_W_WDATA = 0x33333333U;
  dut.io_in_W_WLAST = 1;
  dut.eval();
  check(dut.io_in_W_WREADY && dut.io_SoCBus_W_WLAST,
        "xbar did not forward the final external WLAST");
  tick(dut);
  dut.io_in_W_WVALID = 0;

  dut.io_SoCBus_B_BRESP = 0;
  dut.io_SoCBus_B_BVALID = 1;
  dut.eval();
  check(dut.io_in_B_BVALID,
        "xbar did not return external burst response");
  tick(dut);
  dut.io_SoCBus_B_BVALID = 0;
}

static void external_read_burst(DUT &dut, std::uint32_t address) {
  dut.io_in_AR_ARADDR = address;
  dut.io_in_AR_ARLEN = 3;
  dut.io_in_AR_ARVALID = 1;
  dut.eval();
  check(dut.io_in_AR_ARREADY, "xbar did not accept a mapped MROM read");
  tick(dut);
  dut.io_in_AR_ARVALID = 0;

  dut.eval();
  check(dut.io_SoCBus_AR_ARVALID,
        "mapped MROM read was not forwarded to the SoC bus");
  check(dut.io_SoCBus_AR_ARADDR == address &&
            dut.io_SoCBus_AR_ARLEN == 3,
        "mapped read burst metadata was corrupted");
  dut.io_SoCBus_AR_ARREADY = 1;
  tick(dut);
  dut.io_SoCBus_AR_ARREADY = 0;

  for (unsigned beat = 0; beat < 4; ++beat) {
    dut.io_SoCBus_R_RDATA = 0x1000U + beat;
    dut.io_SoCBus_R_RRESP = 0;
    dut.io_SoCBus_R_RLAST = beat == 3;
    dut.io_SoCBus_R_RVALID = 1;
    dut.eval();
    check(dut.io_in_R_RVALID && dut.io_in_R_RDATA == 0x1000U + beat,
          "mapped read response was not forwarded upstream");
    check(bool(dut.io_in_R_RLAST) == (beat == 3),
          "mapped read response corrupted RLAST");
    tick(dut);
  }
  dut.io_SoCBus_R_RVALID = 0;
  dut.io_SoCBus_R_RLAST = 0;
  dut.io_in_AR_ARLEN = 0;
}

static void clint_burst_decerr(DUT &dut) {
  // The complete CPU elaboration specializes AWLEN to zero because today's
  // LSU only emits single-beat writes.  Enter the otherwise real drain state
  // directly so this C++ regression still checks its WLAST/DECERR behavior.
  dut.rootp->ysyx_26030103_AXI5Xbar__DOT__WriteTargetReg = 1;
  dut.rootp->ysyx_26030103_AXI5Xbar__DOT__state = 8;
  dut.io_in_AW_AWVALID = 0;
  dut.io_in_W_WDATA = 0xaaaaaaaaU;
  dut.io_in_W_WLAST = 0;
  dut.io_in_W_WVALID = 1;
  dut.eval();
  check(!dut.io_CLINT_AW_AWVALID && !dut.io_CLINT_W_WVALID,
        "unsupported CLINT burst reached the CLINT slave");
  check(dut.io_in_W_WREADY,
        "xbar did not enter drain state for CLINT burst");

  dut.io_in_W_WDATA = 0xbbbbbbbbU;
  dut.io_in_W_WLAST = 0;
  tick(dut);
  check(!dut.io_in_B_BVALID,
        "xbar responded before draining CLINT burst through WLAST");

  dut.io_in_W_WDATA = 0xccccccccU;
  dut.io_in_W_WLAST = 1;
  dut.eval();
  check(dut.io_in_W_WREADY, "xbar did not accept drained WLAST");
  tick(dut);
  dut.io_in_W_WVALID = 0;
  dut.eval();
  check(dut.io_in_B_BVALID && dut.io_in_B_BRESP == 3,
        "CLINT burst did not return DECERR after drain");
  tick(dut);
}

static void unmapped_read_burst_decerr(DUT &dut) {
  dut.io_in_AR_ARADDR = 0x20000000U; // SoC address-map hole
  dut.io_in_AR_ARLEN = 3;
  dut.io_in_AR_ARSIZE = 2;
  dut.io_in_AR_ARBURST = 1;
  dut.io_in_AR_ARVALID = 1;
  dut.eval();
  check(dut.io_in_AR_ARREADY, "xbar did not accept an unmapped read");
  tick(dut);
  dut.io_in_AR_ARVALID = 0;

  // The request must be completed locally and never leak to a real device.
  for (unsigned beat = 0; beat < 4; ++beat) {
    dut.eval();
    check(!dut.io_SoCBus_AR_ARVALID && !dut.io_CLINT_AR_ARVALID,
          "unmapped read was forwarded downstream");
    check(dut.io_in_R_RVALID && dut.io_in_R_RRESP == 3,
          "unmapped read did not return DECERR");
    check(dut.io_in_R_RDATA == 0,
          "unmapped read returned nonzero placeholder data");
    check(bool(dut.io_in_R_RLAST) == (beat == 3),
          "unmapped read burst asserted RLAST on the wrong beat");
    tick(dut);
  }
  dut.eval();
  check(!dut.io_in_R_RVALID,
        "unmapped read response remained valid after RLAST");
  dut.io_in_AR_ARLEN = 0;
}

static void crossing_read_decerr(DUT &dut) {
  // The first word is in MROM, but the second word is outside that manager.
  // Routing based only on the start address would make this request unsafe.
  dut.io_in_AR_ARADDR = 0x3ffffffcU;
  dut.io_in_AR_ARLEN = 1;
  dut.io_in_AR_ARVALID = 1;
  tick(dut);
  dut.io_in_AR_ARVALID = 0;
  for (unsigned beat = 0; beat < 2; ++beat) {
    dut.eval();
    check(!dut.io_SoCBus_AR_ARVALID && !dut.io_CLINT_AR_ARVALID,
          "cross-region read was forwarded downstream");
    check(dut.io_in_R_RVALID && dut.io_in_R_RRESP == 3,
          "cross-region read did not return DECERR");
    check(bool(dut.io_in_R_RLAST) == (beat == 1),
          "cross-region read returned the wrong burst length");
    tick(dut);
  }
  dut.io_in_AR_ARLEN = 0;
}

static void unmapped_write_decerr(DUT &dut) {
  dut.io_in_AW_AWADDR = 0x20000000U;
  dut.io_in_AW_AWVALID = 1;
  dut.io_in_W_WDATA = 0xdeadbeefU;
  dut.io_in_W_WLAST = 1;
  dut.io_in_W_WVALID = 1;
  dut.eval();
  check(dut.io_in_AW_AWREADY && dut.io_in_W_WREADY,
        "xbar did not accept an unmapped write");
  tick(dut);
  dut.io_in_AW_AWVALID = 0;
  dut.io_in_W_WVALID = 0;

  dut.eval();
  check(!dut.io_SoCBus_AW_AWVALID && !dut.io_SoCBus_W_WVALID &&
            !dut.io_CLINT_AW_AWVALID && !dut.io_CLINT_W_WVALID,
        "unmapped write was forwarded downstream");
  check(dut.io_in_B_BVALID && dut.io_in_B_BRESP == 3,
        "unmapped write did not return DECERR");
  tick(dut);
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
#ifdef TEST_CHIPLINK
  constexpr const char *kTestName = "AXI xbar PMA routing with ChipLink";
#else
  constexpr const char *kTestName =
      "AXI xbar PMA routing and finite DECERR bursts";
#endif
  return run_test(kTestName, [] {
    DUT dut;
    defaults(dut);
    reset(dut);
    external_burst(dut);
    external_read_burst(dut, 0x30000000U);
#ifdef TEST_CHIPLINK
    external_read_burst(dut, 0xc0000000U);
#endif
    clint_burst_decerr(dut);
    unmapped_read_burst_decerr(dut);
    crossing_read_decerr(dut);
    unmapped_write_decerr(dut);
    dut.final();
  });
}
