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

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  return run_test("AXI xbar external and CLINT bursts", [] {
    DUT dut;
    defaults(dut);
    reset(dut);
    external_burst(dut);
    clint_burst_decerr(dut);
    dut.final();
  });
}
