#include "VDCachePmaLineBoundaryHarness.h"
#include "test_common.hpp"

#include <cstdint>
#include <verilated.h>

using DUT = VDCachePmaLineBoundaryHarness;

static constexpr std::uint32_t kBoundaryAddress = 0x81000008U;
static constexpr std::uint32_t kUncacheableAddress = 0x80000008U;
static constexpr std::uint32_t kNonLegacyCacheableAddress = 0x30000008U;
static constexpr std::uint32_t kReadData = 0x89abcdefU;

static void defaults(DUT &dut, std::uint32_t address) {
  dut.io_req_valid = 0;
  dut.io_req_bits_addr = address;
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
  defaults(dut, kBoundaryAddress);
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
  check(dut.io_AXI_AR_ARADDR == kBoundaryAddress,
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

static void uncacheable_pma_region_never_refills() {
  DUT dut;
  defaults(dut, kUncacheableAddress);
  reset(dut);

  dut.io_req_valid = 1;
  dut.eval();
  check(dut.io_req_ready, "DCache did not accept the uncacheable PMA read");
  check(!dut.io_req_cacheable,
        "DCache ignored PMA Cacheable=false in a 0x8 address region");
  check(!dut.io_perf_miss,
        "uncacheable PMA read was incorrectly counted as a cache miss");
  tick(dut);
  dut.io_req_valid = 0;

  wait_until(dut, [&] { return dut.io_AXI_AR_ARVALID; },
             "DCache did not issue the uncacheable AXI read");
  check(dut.io_AXI_AR_ARADDR == kUncacheableAddress,
        "uncacheable PMA read was aligned to a cache-line base");
  check(dut.io_AXI_AR_ARLEN == 0,
        "Cacheable=false PMA read incorrectly issued a refill burst");
  dut.io_AXI_AR_ARREADY = 1;
  tick(dut);
  dut.io_AXI_AR_ARREADY = 0;

  wait_until(dut, [&] { return dut.io_AXI_R_RREADY; },
             "DCache did not wait for the uncacheable read response");
  dut.io_AXI_R_RDATA = kReadData;
  dut.io_AXI_R_RRESP = 0;
  dut.io_AXI_R_RLAST = 1;
  dut.io_AXI_R_RVALID = 1;
  tick(dut);
  dut.io_AXI_R_RVALID = 0;
  dut.io_AXI_R_RLAST = 0;

  wait_until(dut, [&] { return dut.io_resp_valid; },
             "DCache did not return the uncacheable read response");
  check(!dut.io_resp_bits_fault && dut.io_resp_bits_data == kReadData,
        "DCache corrupted the uncacheable PMA response");
  dut.io_resp_ready = 1;
  tick(dut);
  dut.final();
}

static void non_legacy_cacheable_region_refills_and_hits() {
  DUT dut;
  defaults(dut, kNonLegacyCacheableAddress);
  reset(dut);

  dut.io_req_valid = 1;
  dut.eval();
  check(dut.io_req_ready, "DCache did not accept the 0x3 cacheable read");
  check(dut.io_req_cacheable,
        "DCache retained the historical 0x8/0xa cacheability restriction");
  check(dut.io_perf_miss, "cold cacheable request was not counted as a miss");
  tick(dut);
  dut.io_req_valid = 0;

  wait_until(dut, [&] { return dut.io_AXI_AR_ARVALID; },
             "DCache did not issue the cache-line refill");
  check(dut.io_AXI_AR_ARADDR == 0x30000000U,
        "cacheable 0x3 request did not align to its line base");
  check(dut.io_AXI_AR_ARLEN == 3 && dut.io_AXI_AR_ARSIZE == 2,
        "cacheable 0x3 request did not issue a four-word refill");
  dut.io_AXI_AR_ARREADY = 1;
  tick(dut);
  dut.io_AXI_AR_ARREADY = 0;

  constexpr std::uint32_t beats[4] = {
      0x11111111U, 0x22222222U, kReadData, 0x44444444U};
  for (int beat = 0; beat < 4; ++beat) {
    wait_until(dut, [&] { return dut.io_AXI_R_RREADY; },
               "DCache stopped accepting refill data");
    dut.io_AXI_R_RDATA = beats[beat];
    dut.io_AXI_R_RRESP = 0;
    dut.io_AXI_R_RLAST = beat == 3;
    dut.io_AXI_R_RVALID = 1;
    tick(dut);
    dut.io_AXI_R_RVALID = 0;
    dut.io_AXI_R_RLAST = 0;
  }

  wait_until(dut, [&] { return dut.io_resp_valid; },
             "DCache did not return the critical refill word");
  check(!dut.io_resp_bits_fault && dut.io_resp_bits_data == kReadData,
        "DCache returned the wrong critical refill word");
  dut.io_resp_ready = 1;
  tick(dut);
  dut.io_resp_ready = 0;
  wait_until(dut, [&] { return !dut.io_axi_active && dut.io_req_ready; },
             "DCache did not finish the cacheable refill");

  dut.io_req_valid = 1;
  dut.eval();
  check(dut.io_req_ready && dut.io_req_cacheable && dut.io_perf_hit,
        "second 0x3 request did not hit the installed cache line");
  tick(dut);
  dut.io_req_valid = 0;
  dut.eval();
  check(!dut.io_AXI_AR_ARVALID,
        "cache hit unexpectedly issued another AXI request");
  wait_until(dut, [&] { return dut.io_resp_valid; },
             "DCache did not return the cached 0x3 word");
  check(!dut.io_resp_bits_fault && dut.io_resp_bits_data == kReadData,
        "DCache cache hit returned wrong data");
  dut.io_resp_ready = 1;
  tick(dut);
  dut.final();
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  int failures = 0;
  failures += run_test(
      "DCache keeps a legal word single-beat when its line crosses PMA end",
      legal_word_crossing_line_boundary_stays_single_beat);
  failures += run_test("DCache honors PMA Cacheable=false",
                       uncacheable_pma_region_never_refills);
  failures += run_test("DCache caches a PMA-approved non-0x8/0xa region",
                       non_legacy_cacheable_region_refills_and_hits);
  return failures == 0 ? 0 : 1;
}
