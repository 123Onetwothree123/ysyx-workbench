// Xbar PLIC PMA permission regression.
//
// The Xbar has a dedicated PLIC port, but PLIC is still MMIO and must obey the
// same PMA Readable/Writable flags as CLINT/SoCBus.  The harness below is
// elaborated with a PMA region that covers the PLIC window but marks it
// Readable=false/Writable=false.  Neither a read nor a write may reach the
// PLIC port; both must terminate locally with DECERR.
#include "Vysyx_26030103_AXI5Xbar.h"
#include "test_common.hpp"
#include <verilated.h>

using DUT = Vysyx_26030103_AXI5Xbar;

static constexpr uint32_t PLIC_CLAIM = 0x0c201004U;

static void defaults(DUT &dut) {
  dut.io_in_AW_AWVALID = 0;
  dut.io_in_AW_AWADDR = 0;
  dut.io_in_AW_AWID = 0;
  dut.io_in_AW_AWLEN = 0;
  dut.io_in_AW_AWSIZE = 2;
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
  dut.io_in_AR_ARSIZE = 2;
  dut.io_in_AR_ARBURST = 1;
  dut.io_in_AR_ARPROT = 0;
  dut.io_in_AR_ARCACHE = 0;
  dut.io_in_AR_ARQOS = 0;
  dut.io_in_R_RREADY = 0;

  dut.io_SoCBus_AW_AWREADY = 1;
  dut.io_SoCBus_W_WREADY = 1;
  dut.io_SoCBus_B_BVALID = 0;
  dut.io_SoCBus_B_BID = 0;
  dut.io_SoCBus_B_BRESP = 0;
  dut.io_SoCBus_AR_ARREADY = 0;
  dut.io_SoCBus_R_RVALID = 0;
  dut.io_SoCBus_R_RID = 0;
  dut.io_SoCBus_R_RDATA = 0;
  dut.io_SoCBus_R_RRESP = 0;
  dut.io_SoCBus_R_RLAST = 0;

  dut.io_CLINT_AW_AWREADY = 1;
  dut.io_CLINT_W_WREADY = 1;
  dut.io_CLINT_B_BVALID = 0;
  dut.io_CLINT_B_BID = 0;
  dut.io_CLINT_B_BRESP = 0;
  dut.io_CLINT_AR_ARREADY = 0;
  dut.io_CLINT_R_RVALID = 0;
  dut.io_CLINT_R_RID = 0;
  dut.io_CLINT_R_RDATA = 0;
  dut.io_CLINT_R_RRESP = 0;
  dut.io_CLINT_R_RLAST = 0;

  dut.io_PLIC_AW_AWREADY = 1;
  dut.io_PLIC_W_WREADY = 1;
  dut.io_PLIC_B_BVALID = 0;
  dut.io_PLIC_B_BID = 0;
  dut.io_PLIC_B_BRESP = 0;
  dut.io_PLIC_AR_ARREADY = 1;
  dut.io_PLIC_R_RVALID = 0;
  dut.io_PLIC_R_RID = 0;
  dut.io_PLIC_R_RDATA = 0;
  dut.io_PLIC_R_RRESP = 0;
  dut.io_PLIC_R_RLAST = 0;
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  int status = 0;

  status |= run_test("Xbar denies PLIC read when PMA Readable=false", [] {
    DUT dut;
    defaults(dut);
    reset(dut);

    dut.io_in_AR_ARVALID = 1;
    dut.io_in_AR_ARADDR = PLIC_CLAIM;
    dut.io_in_AR_ARLEN = 0;
    dut.io_in_AR_ARSIZE = 2;
    dut.io_in_AR_ARBURST = 1;
    dut.eval();
    check(dut.io_in_AR_ARREADY, "Xbar did not accept the PLIC read request");
    tick(dut);
    dut.io_in_AR_ARVALID = 0;
    dut.eval();

    for (int cycle = 0; cycle < 8; ++cycle) {
      check(!dut.io_PLIC_AR_ARVALID,
            "PLIC read reached the PLIC port despite PMA read denial");
      tick(dut);
    }
    wait_until(dut, [&] { return dut.io_in_R_RVALID; },
               "denied PLIC read did not return a local response");
    check(dut.io_in_R_RRESP == 3, "denied PLIC read did not return DECERR");
    check(dut.io_in_R_RLAST, "denied PLIC read did not terminate with RLAST");
    dut.io_in_R_RREADY = 1;
    tick(dut);
    dut.io_in_R_RREADY = 0;
    dut.final();
  });

  status |= run_test("Xbar denies PLIC write when PMA Writable=false", [] {
    DUT dut;
    defaults(dut);
    reset(dut);

    dut.io_in_AW_AWVALID = 1;
    dut.io_in_AW_AWADDR = PLIC_CLAIM;
    dut.io_in_AW_AWID = 1;
    dut.io_in_AW_AWLEN = 0;
    dut.io_in_AW_AWSIZE = 2;
    dut.io_in_AW_AWBURST = 1;
    dut.io_in_W_WVALID = 1;
    dut.io_in_W_WDATA = 10;
    dut.io_in_W_WSTRB = 0xf;
    dut.io_in_W_WLAST = 1;
    dut.eval();
    check(dut.io_in_AW_AWREADY && dut.io_in_W_WREADY,
          "Xbar did not collect the denied PLIC write");
    tick(dut);
    dut.io_in_AW_AWVALID = 0;
    dut.io_in_W_WVALID = 0;
    dut.eval();

    for (int cycle = 0; cycle < 8; ++cycle) {
      check(!dut.io_PLIC_AW_AWVALID && !dut.io_PLIC_W_WVALID,
            "PLIC write reached the PLIC port despite PMA write denial");
      tick(dut);
    }
    wait_until(dut, [&] { return dut.io_in_B_BVALID; },
               "denied PLIC write did not return a local response");
    check(dut.io_in_B_BRESP == 3, "denied PLIC write did not return DECERR");
    dut.io_in_B_BREADY = 1;
    tick(dut);
    dut.io_in_B_BREADY = 0;
    dut.final();
  });

  return status;
}
