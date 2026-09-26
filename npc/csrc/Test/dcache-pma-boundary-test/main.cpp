#include "VDCachePmaLineBoundaryHarness.h"
#include "test_common.hpp"

#include <cstdint>
#include <verilated.h>

using DUT = VDCachePmaLineBoundaryHarness;

static constexpr std::uint32_t kAddress = 0x80000008U;
static constexpr std::uint32_t kReadData = 0x89abcdefU;

static void defaults(DUT &dut) {
  dut.io_req_valid = 0;
  dut.io_req_bits_addr = kAddress;
  dut.io_req_bits_WidthSelect = 2;
  dut.io_req_bits_signed = 0;
  dut.io_resp_ready = 0;
  dut.io_StoreValid = 0;
  dut.io_StoreAddr = 0;
  dut.io_StoreData = 0;
  dut.io_StoreStrb = 0;
  dut.io_AXI_AW_AWREADY = 0;
  dut.io_AXI_W_WREADY = 0;
  dut.io_AXI_B_BID = 0;
  dut.io_AXI_B_BRESP = 0;
  dut.io_AXI_B_BVALID = 0;
  dut.io_AXI_AR_ARREADY = 0;
  dut.io_AXI_R_RID = 0;
  dut.io_AXI_R_RDATA = 0;
  dut.io_AXI_R_RRESP = 0;
  dut.io_AXI_R_RLAST = 0;
  dut.io_AXI_R_RVALID = 0;
  dut.io_flush = 0;
}

static void legal_word_crossing_line_boundary_stays_single_beat() {
  DUT dut;
  defaults(dut);
  reset(dut);

  dut.io_req_valid = 1;
  dut.eval();
  check(dut.io_req_ready, "DCache did not accept the legal boundary word");
  check(!dut.io_req_cacheable,
        "DCache classified a PMA-crossing line as cacheable");
  tick(dut);
  dut.io_req_valid = 0;

  wait_until(dut, [&] { return dut.io_AXI_AR_ARVALID; },
             "DCache did not issue AXI for the legal boundary word");
  check(dut.io_AXI_AR_ARADDR == kAddress,
        "DCache aligned the request down to an illegal line base");
  check(dut.io_AXI_AR_ARLEN == 0 && dut.io_AXI_AR_ARSIZE == 2,
        "DCache widened the legal word into a line refill");
  dut.io_AXI_AR_ARREADY = 1;
  tick(dut);
  dut.io_AXI_AR_ARREADY = 0;

  wait_until(dut, [&] { return dut.io_AXI_R_RREADY; },
             "DCache did not become ready for the response");
  dut.io_AXI_R_RDATA = kReadData;
  dut.io_AXI_R_RRESP = 0;
  dut.io_AXI_R_RLAST = 1;
  dut.io_AXI_R_RVALID = 1;
  tick(dut);
  dut.io_AXI_R_RVALID = 0;
  dut.io_AXI_R_RLAST = 0;

  wait_until(dut, [&] { return dut.io_resp_valid; },
             "DCache did not return the legal boundary word");
  check(!dut.io_resp_bits_fault && dut.io_resp_bits_FaultResp == 0,
        "legal boundary word incorrectly became an access fault");
  check(dut.io_resp_bits_data == kReadData,
        "DCache corrupted the uncached response");
  check(!dut.io_axi_active,
        "DCache waited for nonexistent refill beats after RLAST");

  for (int cycle = 0; cycle < 2; ++cycle) {
    tick(dut);
    dut.eval();
    check(dut.io_resp_valid && !dut.io_resp_bits_fault &&
              dut.io_resp_bits_data == kReadData,
          "DCache response changed under backpressure");
  }

  dut.io_resp_ready = 1;
  tick(dut);
  dut.eval();
  check(!dut.io_resp_valid && dut.io_req_ready,
        "DCache did not return to idle after the response");
  dut.final();
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  return run_test("DCache keeps a legal word single-beat when its line crosses "
                  "PMA end",
                  legal_word_crossing_line_boundary_stays_single_beat);
}
