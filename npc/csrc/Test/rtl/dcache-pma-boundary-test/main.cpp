#include "VDCachePmaLineBoundaryHarness.h"
#include "ports.hpp"
#include "test_common.hpp"

#include <cstdint>
#include <verilated.h>

using namespace ports::DCachePma;

using DUT = VDCachePmaLineBoundaryHarness;

static constexpr std::uint32_t kBoundaryAddress = 0x81000008U;
static constexpr std::uint32_t kUncacheableAddress = 0x80000008U;
static constexpr std::uint32_t kNonLegacyCacheableAddress = 0x30000008U;
static constexpr std::uint32_t kReadData = 0x89abcdefU;

static void defaults(DUT &dut, std::uint32_t address) {
  io_request_valid(dut) = 0;
  io_request_bits_address(dut) = address;
  io_request_bits_WidthSelect(dut) = 2;
  io_request_bits_signed(dut) = 0;
  io_response_ready(dut) = 0;
  io_StoreValid(dut) = 0;
  io_StoreResponse(dut) = 0;
  io_StoreAddress(dut) = 0;
  io_StoreData(dut) = 0;
  io_StoreStrobe(dut) = 0;
  io_AXI_AW_AWREADY(dut) = 0;
  io_AXI_W_WREADY(dut) = 0;
  io_AXI_B_BID(dut) = 0;
  io_AXI_B_BRESP(dut) = 0;
  io_AXI_B_BVALID(dut) = 0;
  io_AXI_AR_ARREADY(dut) = 0;
  io_AXI_R_RID(dut) = 0;
  io_AXI_R_RDATA(dut) = 0;
  io_AXI_R_RRESP(dut) = 0;
  io_AXI_R_RLAST(dut) = 0;
  io_AXI_R_RVALID(dut) = 0;
  io_flush(dut) = 0;
}

static void legal_word_crossing_line_boundary_stays_single_beat() {
  DUT dut;
  defaults(dut, kBoundaryAddress);
  reset(dut);

  io_request_valid(dut) = 1;
  dut.eval();
  check(io_request_ready(dut), "DCache did not accept the legal boundary word");
  check(!io_RequestCacheable(dut),
        "DCache classified a PMA-crossing line as cacheable");
  tick(dut);
  io_request_valid(dut) = 0;

  wait_until(dut, [&] { return io_AXI_AR_ARVALID(dut); },
             "DCache did not issue AXI for the legal boundary word");
  check(io_AXI_AR_ARADDR(dut) == kBoundaryAddress,
        "DCache aligned the request down to an illegal line base");
  check(io_AXI_AR_ARLEN(dut) == 0 && io_AXI_AR_ARSIZE(dut) == 2,
        "DCache widened the legal word into a line refill");
  io_AXI_AR_ARREADY(dut) = 1;
  tick(dut);
  io_AXI_AR_ARREADY(dut) = 0;

  wait_until(dut, [&] { return io_AXI_R_RREADY(dut); },
             "DCache did not become ready for the response");
  io_AXI_R_RDATA(dut) = kReadData;
  io_AXI_R_RRESP(dut) = 0;
  io_AXI_R_RLAST(dut) = 1;
  io_AXI_R_RVALID(dut) = 1;
  tick(dut);
  io_AXI_R_RVALID(dut) = 0;
  io_AXI_R_RLAST(dut) = 0;

  wait_until(dut, [&] { return io_response_valid(dut); },
             "DCache did not return the legal boundary word");
  check(!io_response_bits_fault(dut) && io_response_bits_FaultResponse(dut) == 0,
        "legal boundary word incorrectly became an access fault");
  check(io_response_bits_data(dut) == kReadData,
        "DCache corrupted the uncached response");
  check(!io_AXIActive(dut),
        "DCache waited for nonexistent refill beats after RLAST");

  for (int cycle = 0; cycle < 2; ++cycle) {
    tick(dut);
    dut.eval();
    check(io_response_valid(dut) && !io_response_bits_fault(dut) &&
              io_response_bits_data(dut) == kReadData,
          "DCache response changed under backpressure");
  }

  io_response_ready(dut) = 1;
  tick(dut);
  dut.eval();
  check(!io_response_valid(dut) && io_request_ready(dut),
        "DCache did not return to idle after the response");
  dut.final();
}

static void uncacheable_pma_region_never_refills() {
  DUT dut;
  defaults(dut, kUncacheableAddress);
  reset(dut);

  io_request_valid(dut) = 1;
  dut.eval();
  check(io_request_ready(dut), "DCache did not accept the uncacheable PMA read");
  check(!io_RequestCacheable(dut),
        "DCache ignored PMA Cacheable=false in a 0x8 address region");
  check(!io_perf_miss(dut),
        "uncacheable PMA read was incorrectly counted as a cache miss");
  tick(dut);
  io_request_valid(dut) = 0;

  wait_until(dut, [&] { return io_AXI_AR_ARVALID(dut); },
             "DCache did not issue the uncacheable AXI read");
  check(io_AXI_AR_ARADDR(dut) == kUncacheableAddress,
        "uncacheable PMA read was aligned to a cache-line base");
  check(io_AXI_AR_ARLEN(dut) == 0,
        "Cacheable=false PMA read incorrectly issued a refill burst");
  io_AXI_AR_ARREADY(dut) = 1;
  tick(dut);
  io_AXI_AR_ARREADY(dut) = 0;

  wait_until(dut, [&] { return io_AXI_R_RREADY(dut); },
             "DCache did not wait for the uncacheable read response");
  io_AXI_R_RDATA(dut) = kReadData;
  io_AXI_R_RRESP(dut) = 0;
  io_AXI_R_RLAST(dut) = 1;
  io_AXI_R_RVALID(dut) = 1;
  tick(dut);
  io_AXI_R_RVALID(dut) = 0;
  io_AXI_R_RLAST(dut) = 0;

  wait_until(dut, [&] { return io_response_valid(dut); },
             "DCache did not return the uncacheable read response");
  check(!io_response_bits_fault(dut) && io_response_bits_data(dut) == kReadData,
        "DCache corrupted the uncacheable PMA response");
  io_response_ready(dut) = 1;
  tick(dut);
  dut.final();
}

static void non_legacy_cacheable_region_refills_and_hits() {
  DUT dut;
  defaults(dut, kNonLegacyCacheableAddress);
  reset(dut);

  io_request_valid(dut) = 1;
  dut.eval();
  check(io_request_ready(dut), "DCache did not accept the 0x3 cacheable read");
  check(io_RequestCacheable(dut),
        "DCache retained the historical 0x8/0xa cacheability restriction");
  check(io_perf_miss(dut), "cold cacheable request was not counted as a miss");
  tick(dut);
  io_request_valid(dut) = 0;

  wait_until(dut, [&] { return io_AXI_AR_ARVALID(dut); },
             "DCache did not issue the cache-line refill");
  check(io_AXI_AR_ARADDR(dut) == 0x30000000U,
        "cacheable 0x3 request did not align to its line base");
  check(io_AXI_AR_ARLEN(dut) == 3 && io_AXI_AR_ARSIZE(dut) == 2,
        "cacheable 0x3 request did not issue a four-word refill");
  io_AXI_AR_ARREADY(dut) = 1;
  tick(dut);
  io_AXI_AR_ARREADY(dut) = 0;

  constexpr std::uint32_t beats[4] = {
      0x11111111U, 0x22222222U, kReadData, 0x44444444U};
  for (int beat = 0; beat < 4; ++beat) {
    wait_until(dut, [&] { return io_AXI_R_RREADY(dut); },
               "DCache stopped accepting refill data");
    io_AXI_R_RDATA(dut) = beats[beat];
    io_AXI_R_RRESP(dut) = 0;
    io_AXI_R_RLAST(dut) = beat == 3;
    io_AXI_R_RVALID(dut) = 1;
    tick(dut);
    io_AXI_R_RVALID(dut) = 0;
    io_AXI_R_RLAST(dut) = 0;
  }

  wait_until(dut, [&] { return io_response_valid(dut); },
             "DCache did not return the critical refill word");
  check(!io_response_bits_fault(dut) && io_response_bits_data(dut) == kReadData,
        "DCache returned the wrong critical refill word");
  io_response_ready(dut) = 1;
  tick(dut);
  io_response_ready(dut) = 0;
  wait_until(dut, [&] { return !io_AXIActive(dut) && io_request_ready(dut); },
             "DCache did not finish the cacheable refill");

  io_request_valid(dut) = 1;
  dut.eval();
  check(io_request_ready(dut) && io_RequestCacheable(dut) && io_perf_hit(dut),
        "second 0x3 request did not hit the installed cache line");
  tick(dut);
  io_request_valid(dut) = 0;
  dut.eval();
  check(!io_AXI_AR_ARVALID(dut),
        "cache hit unexpectedly issued another AXI request");
  wait_until(dut, [&] { return io_response_valid(dut); },
             "DCache did not return the cached 0x3 word");
  check(!io_response_bits_fault(dut) && io_response_bits_data(dut) == kReadData,
        "DCache cache hit returned wrong data");
  io_response_ready(dut) = 1;
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
