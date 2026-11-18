#include "VICachePmaBoundaryHarness.h"
#include "test_common.hpp"

#include <cstdint>
#include <verilated.h>

using DUT = VICachePmaBoundaryHarness;

static void defaults(DUT &dut) {
  dut.io_fetch_addr = 0;
  dut.io_fetch_valid = 0;
  dut.io_resp_ready = 0;
  dut.io_axi_AR_ARREADY = 0;
  dut.io_axi_R_RDATA = 0;
  dut.io_axi_R_RRESP = 0;
  dut.io_axi_R_RLAST = 0;
  dut.io_axi_R_RVALID = 0;
  dut.io_flush = 0;
  dut.io_kill = 0;
}

static void accept_fetch(DUT &dut, std::uint32_t address) {
  dut.io_fetch_addr = address;
  dut.io_fetch_valid = 1;
  dut.eval();
  check(dut.io_fetch_ready, "ICache did not accept the test fetch");
  tick(dut);
  dut.io_fetch_valid = 0;
}

static void complete_word_inside_region_uses_axi_control() {
  DUT dut;
  defaults(dut);
  reset(dut);
  accept_fetch(dut, 0x1000U);
  wait_until(
      dut, [&] { return dut.io_axi_AR_ARVALID; },
      "complete executable word did not issue AXI", 8);
  check(dut.io_axi_AR_ARADDR == 0x1000U && dut.io_axi_AR_ARLEN == 0,
        "non-cacheable executable word issued the wrong AXI request");
  dut.final();
}

static void crossing_word_must_fault_locally() {
  DUT dut;
  defaults(dut);
  reset(dut);
  accept_fetch(dut, 0x1004U);

  bool saw_fault = false;
  for (int cycle = 0; cycle < 8 && !saw_fault; ++cycle) {
    dut.eval();
    check(!dut.io_axi_AR_ARVALID, "ICache issued a 4-byte fetch whose final "
                                  "bytes cross PMA EndExclusive");
    if (dut.io_resp_valid) {
      check(dut.io_resp_fault && dut.io_resp_addr == 0x1004U,
            "cross-boundary instruction did not become a local access fault");
      saw_fault = true;
    }
    tick(dut);
  }
  check(saw_fault,
        "cross-boundary instruction produced neither fault nor AXI request");
  dut.final();
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  int failures = 0;
  failures += run_test("ICache in-region word positive control",
                       complete_word_inside_region_uses_axi_control);
  failures += run_test("ICache rejects a fetch crossing PMA end",
                       crossing_word_must_fault_locally);
  return failures == 0 ? 0 : 1;
}
