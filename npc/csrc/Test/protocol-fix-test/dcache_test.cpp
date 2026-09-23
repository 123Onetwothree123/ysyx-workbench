#include "Vysyx_26030103_DCache.h"
#include "test_common.hpp"
#include <array>
#include <cstdint>
#include <verilated.h>

using DUT = Vysyx_26030103_DCache;

static void defaults(DUT &dut) {
  dut.io_req_valid = 0;
  dut.io_req_bits_addr = 0;
  dut.io_req_bits_WidthSelect = 2;
  dut.io_req_bits_signed = 0;
  dut.io_resp_ready = 1;
  dut.io_StoreValid = 0;
  dut.io_StoreAddr = 0;
  dut.io_StoreData = 0;
  dut.io_StoreStrb = 0;
  dut.io_AXI_AR_ARREADY = 0;
  dut.io_AXI_R_RDATA = 0;
  dut.io_AXI_R_RRESP = 0;
  dut.io_AXI_R_RLAST = 0;
  dut.io_AXI_R_RVALID = 0;
  dut.io_flush = 0;
}

static void accept_refill_ar(DUT &dut, uint32_t line_base,
                             unsigned stall_cycles) {
  wait_until(dut, [&] { return dut.io_AXI_AR_ARVALID; },
             "DCache refill never issued AR");
  check(dut.io_AXI_AR_ARADDR == line_base,
        "DCache burst did not start at the cache-line base");
  check(dut.io_AXI_AR_ARLEN == 3,
        "16-byte DCache line did not use one four-beat burst");
  // ARBURST is a compile-time constant and Verilator removes the port from
  // this leaf-module test; ARLEN/ARSIZE still prove the four 32-bit beats.
  check(dut.io_AXI_AR_ARSIZE == 2,
        "DCache refill did not request 32-bit beats");

  for (unsigned cycle = 0; cycle < stall_cycles; ++cycle) {
    const uint32_t held_addr = dut.io_AXI_AR_ARADDR;
    const uint8_t held_len = dut.io_AXI_AR_ARLEN;
    tick(dut);
    dut.eval();
    check(dut.io_AXI_AR_ARVALID && dut.io_AXI_AR_ARADDR == held_addr &&
              dut.io_AXI_AR_ARLEN == held_len,
          "DCache changed burst AR while backpressured");
  }
  dut.io_AXI_AR_ARREADY = 1;
  tick(dut);
  dut.io_AXI_AR_ARREADY = 0;
  dut.eval();
  check(!dut.io_AXI_AR_ARVALID,
        "DCache issued more than one AR for a normal line refill");
}

static void send_refill(DUT &dut, const std::array<uint32_t, 4> &words,
                        int error_beat = -1, uint8_t error_resp = 0) {
  uint32_t lfsr = 0x5a17U;
  for (unsigned beat = 0; beat < words.size(); ++beat) {
    // Deterministic pseudo-random R-channel gaps model memory-side
    // backpressure without making the regression flaky.
    lfsr = (lfsr >> 1) ^ ((0U - (lfsr & 1U)) & 0xb400U);
    const unsigned gap = lfsr % 4U;
    for (unsigned cycle = 0; cycle < gap; ++cycle) {
      dut.eval();
      check(dut.io_AXI_R_RREADY,
            "DCache withdrew RREADY in the middle of its burst");
      check(!dut.io_AXI_AR_ARVALID,
            "DCache reissued AR between burst response beats");
      tick(dut);
    }

    dut.io_AXI_R_RDATA = words[beat];
    dut.io_AXI_R_RRESP = static_cast<int>(beat) == error_beat ? error_resp : 0;
    dut.io_AXI_R_RLAST = beat + 1 == words.size();
    dut.io_AXI_R_RVALID = 1;
    tick(dut);
    dut.io_AXI_R_RVALID = 0;
    dut.io_AXI_R_RLAST = 0;
    dut.io_AXI_R_RRESP = 0;
  }
}

static void accept_single_ar(DUT &dut, uint32_t address) {
  wait_until(dut, [&] { return dut.io_AXI_AR_ARVALID; },
             "DCache early-RLAST fallback never issued its single-beat AR");
  check(dut.io_AXI_AR_ARADDR == address && dut.io_AXI_AR_ARLEN == 0 &&
            dut.io_AXI_AR_ARSIZE == 2,
        "DCache early-RLAST fallback requested the wrong remaining word");
  dut.io_AXI_AR_ARREADY = 1;
  tick(dut);
  dut.io_AXI_AR_ARREADY = 0;
}

static void send_single_r(DUT &dut, uint32_t data) {
  dut.io_AXI_R_RDATA = data;
  dut.io_AXI_R_RRESP = 0;
  dut.io_AXI_R_RLAST = 1;
  dut.io_AXI_R_RVALID = 1;
  tick(dut);
  dut.io_AXI_R_RVALID = 0;
  dut.io_AXI_R_RLAST = 0;
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  return run_test("DCache flush preserves request and suppresses refill", [] {
    DUT dut;
    defaults(dut);
    reset(dut);

    dut.io_req_bits_addr = 0x80000004U;
    dut.io_req_valid = 1;
    dut.eval();
    check(dut.io_req_ready, "DCache did not accept the initial miss");
    tick(dut);
    dut.io_req_valid = 0;
    dut.eval();
    check(dut.io_AXI_AR_ARVALID,
          "DCache did not present ARVALID for the miss");
    const uint32_t address = dut.io_AXI_AR_ARADDR;

    dut.io_flush = 1;
    tick(dut);
    dut.io_flush = 0;
    dut.eval();
    check(dut.io_AXI_AR_ARVALID,
          "flush withdrew ARVALID before ARREADY");
    check(dut.io_AXI_AR_ARADDR == address,
          "flush changed a stalled AR address");

    tick(dut);
    dut.eval();
    check(dut.io_AXI_AR_ARVALID,
          "stalled ARVALID was not held for a second cycle");

    // Finish the single four-beat burst used to refill this 16-byte line.
    // A flush may suppress installation of the line, but the accepted demand
    // load must still receive exactly one response so its caller cannot hang.
    constexpr std::array<uint32_t, 4> words = {
        0x11111111U, 0x12345678U, 0x33333333U, 0x44444444U};
    accept_refill_ar(dut, 0x80000000U, 2);
    send_refill(dut, words);

    dut.eval();
    check(dut.io_resp_valid,
          "flush cancelled an accepted DCache demand response");
    check(!dut.io_resp_bits_fault,
          "successful flushed demand was incorrectly reported as a fault");
    check(dut.io_resp_bits_data == 0x12345678U,
          "flushed demand returned the wrong requested word");
    tick(dut); // consume the response
    dut.eval();
    check(dut.io_req_ready,
          "DCache did not return to idle after the preserved response");

    // The data may satisfy the original load, but the line crossed a flush and
    // therefore must not have been installed as a valid cache line.
    dut.io_req_valid = 1;
    tick(dut);
    dut.io_req_valid = 0;
    accept_refill_ar(dut, 0x80000000U, 1);

    // An error on the demand word remains a precise load fault even though
    // the other beats of the burst complete successfully.  It also prevents
    // the partially received line from becoming valid.
    send_refill(dut, words, 1, 2);
    dut.io_resp_ready = 0;
    dut.eval();
    check(dut.io_resp_valid && dut.io_resp_bits_fault,
          "keyword RRESP error was not preserved until response handshake");
    check(dut.io_resp_bits_FaultResp == 2,
          "DCache lost the keyword RRESP code");
    for (int cycle = 0; cycle < 3; ++cycle) {
      tick(dut);
      dut.eval();
      check(dut.io_resp_valid && dut.io_resp_bits_fault &&
                dut.io_resp_bits_FaultResp == 2,
            "DCache fault response was unstable under backpressure");
    }
    dut.io_resp_ready = 1;
    tick(dut); // consume the precise fault

    // The failed refill must not leave a valid partial line.  Re-accessing it
    // therefore issues a fresh burst rather than returning a cache hit.
    dut.io_req_bits_addr = 0x80000004U;
    dut.io_req_valid = 1;
    dut.eval();
    check(dut.io_req_ready,
          "DCache did not accept a retry after a failed refill");
    tick(dut);
    dut.io_req_valid = 0;
    accept_refill_ar(dut, 0x80000000U, 0);

    // Model a malformed response that terminates its burst too early.  The cache
    // must recover by requesting each remaining word exactly once.
    dut.io_AXI_R_RDATA = words[0];
    dut.io_AXI_R_RRESP = 0;
    dut.io_AXI_R_RLAST = 1;
    dut.io_AXI_R_RVALID = 1;
    tick(dut);
    dut.io_AXI_R_RVALID = 0;
    dut.io_AXI_R_RLAST = 0;
    for (unsigned beat = 1; beat < words.size(); ++beat) {
      accept_single_ar(dut, 0x80000000U + beat * 4U);
      send_single_r(dut, words[beat]);
    }

    dut.eval();
    check(dut.io_resp_valid && !dut.io_resp_bits_fault &&
              dut.io_resp_bits_data == words[1],
          "DCache early-RLAST recovery returned the wrong demand response");
    tick(dut);

    // A completed fallback refill is installable: the same access must hit
    // without issuing another AR.
    dut.io_req_valid = 1;
    dut.eval();
    check(dut.io_req_ready,
          "DCache did not accept a hit after early-RLAST recovery");
    tick(dut);
    dut.io_req_valid = 0;
    dut.eval();
    check(dut.io_resp_valid && !dut.io_AXI_AR_ARVALID &&
              dut.io_resp_bits_data == words[1],
          "DCache did not install the fully recovered cache line");
    tick(dut);
    dut.final();
  });
}
