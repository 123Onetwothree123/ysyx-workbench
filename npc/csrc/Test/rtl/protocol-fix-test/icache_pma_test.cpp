#include "Vysyx_26030103_ICacheReg.h"
#include "test_common.hpp"
#include <array>
#include <cstdint>
#include <verilated.h>

using DUT = Vysyx_26030103_ICacheReg;

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

static void consume_local_fault(DUT &dut, std::uint32_t address) {
  dut.io_fetch_addr = address;
  dut.io_fetch_valid = 1;
  dut.io_resp_ready = 0;
  dut.eval();
  check(dut.io_fetch_ready, "ICache did not accept an illegal fetch");
  check(!dut.io_axi_AR_ARVALID,
        "ICache issued AXI before accepting an illegal fetch");
  tick(dut);
  dut.io_fetch_valid = 0;

  for (int cycle = 0; cycle < 3; ++cycle) {
    dut.eval();
    check(dut.io_resp_valid && dut.io_resp_fault,
          "illegal fetch did not produce a local fault response");
    check(dut.io_resp_addr == address,
          "illegal fetch response lost the faulting PC");
    check(dut.io_resp_data == 0x00000013U,
          "illegal fetch did not substitute a NOP payload");
    check(dut.io_access_fault_resp == 3,
          "PMA rejection did not report DECERR to debug logic");
    check(!dut.io_axi_AR_ARVALID,
          "illegal fetch touched AXI while response was backpressured");
    tick(dut);
  }

  dut.io_resp_ready = 1;
  tick(dut);
  dut.io_resp_ready = 0;
  dut.eval();
  check(!dut.io_resp_valid,
        "illegal fetch response did not retire after its handshake");
}

#ifndef TEST_CACHE_DISABLED
static void executable_memory_uses_line_refill(DUT &dut,
                                                std::uint32_t address,
                                                std::uint32_t line_address) {
  dut.io_fetch_addr = address;
  dut.io_fetch_valid = 1;
  dut.eval();
  check(dut.io_fetch_ready, "ICache did not accept an executable fetch");
  tick(dut);
  dut.io_fetch_valid = 0;

  wait_until(
      dut, [&] { return bool(dut.io_axi_AR_ARVALID); },
      "executable MROM fetch never issued an AXI refill", 8);
  check(dut.io_axi_AR_ARADDR == line_address,
        "cacheable fetch did not align its line refill");
  check(dut.io_axi_AR_ARLEN == 3,
        "16-byte cache line did not request four AXI beats");
}

static void missing_final_rlast_faults_and_does_not_install(
    DUT &dut, std::uint32_t address, std::uint32_t line_address) {
  dut.io_fetch_addr = address;
  dut.io_fetch_valid = 1;
  dut.io_resp_ready = 1;
  dut.eval();
  check(dut.io_fetch_ready,
        "ICache did not accept the missing-RLAST test fetch");
  tick(dut);
  dut.io_fetch_valid = 0;

  wait_until(dut, [&] { return bool(dut.io_axi_AR_ARVALID); },
             "missing-RLAST test never issued a refill AR", 8);
  check(dut.io_axi_AR_ARADDR == line_address && dut.io_axi_AR_ARLEN == 3,
        "missing-RLAST test did not issue the expected line refill");
  dut.io_axi_AR_ARREADY = 1;
  tick(dut);
  dut.io_axi_AR_ARREADY = 0;

  for (std::uint32_t beat = 0; beat < 4; ++beat) {
    dut.io_axi_R_RDATA = 0x11110000U + beat;
    dut.io_axi_R_RRESP = 0;
    dut.io_axi_R_RLAST = 0; // Deliberately omit RLAST on the expected final beat.
    dut.io_axi_R_RVALID = 1;
    tick(dut);
  }
  dut.io_axi_R_RVALID = 0;
  dut.eval();

  check(dut.io_resp_valid && dut.io_resp_fault,
        "missing final RLAST did not become an instruction access fault");
  check(dut.io_resp_data == 0x00000013U &&
            dut.io_access_fault_resp == 2,
        "missing final RLAST did not return a stable protocol-fault payload");
  check(!dut.io_fetch_ready,
        "ICache left drain state before the late RLAST arrived");

  // Consume the fault response, then prove that an empty response slot alone
  // cannot release the refill while the malformed burst is still undrained.
  tick(dut);
  check(!dut.io_fetch_ready,
        "ICache accepted a new fetch before draining the late RLAST");

  dut.io_axi_R_RDATA = 0xdeadbeefU;
  dut.io_axi_R_RRESP = 0;
  dut.io_axi_R_RLAST = 1;
  dut.io_axi_R_RVALID = 1;
  tick(dut);
  dut.io_axi_R_RVALID = 0;
  dut.io_axi_R_RLAST = 0;
  dut.eval();
  check(dut.io_fetch_ready && !dut.io_perf_refill_resp,
        "late RLAST did not return the ICache to idle");

  // The malformed line must remain invalid.  Re-accessing the same final word
  // must count as a miss and issue another AR rather than hit stale data.
  dut.io_fetch_addr = address;
  dut.io_fetch_valid = 1;
  dut.eval();
  check(dut.io_fetch_ready && !dut.io_perf_hit && dut.io_perf_miss,
        "line with missing final RLAST was incorrectly installed as a hit");
  tick(dut);
  dut.io_fetch_valid = 0;
  wait_until(dut, [&] { return bool(dut.io_axi_AR_ARVALID); },
             "retry after missing final RLAST did not issue a fresh AR", 8);
  check(dut.io_axi_AR_ARADDR == line_address && dut.io_axi_AR_ARLEN == 3,
        "retry after missing final RLAST issued the wrong refill");
}

static void fill_line(DUT &dut, std::uint32_t line_address,
                      std::uint32_t data_base) {
  dut.io_fetch_addr = line_address;
  dut.io_fetch_valid = 1;
  dut.io_resp_ready = 0;
  dut.eval();
  check(dut.io_fetch_ready, "ICache did not accept line-fill setup fetch");
  tick(dut);
  dut.io_fetch_valid = 0;

  wait_until(dut, [&] { return bool(dut.io_axi_AR_ARVALID); },
             "line-fill setup never issued AR", 8);
  check(dut.io_axi_AR_ARADDR == line_address && dut.io_axi_AR_ARLEN == 3,
        "line-fill setup issued the wrong burst");
  dut.io_axi_AR_ARREADY = 1;
  tick(dut);
  dut.io_axi_AR_ARREADY = 0;

  for (std::uint32_t beat = 0; beat < 4; ++beat) {
    dut.io_axi_R_RDATA = data_base + beat;
    dut.io_axi_R_RRESP = 0;
    dut.io_axi_R_RLAST = beat == 3;
    dut.io_axi_R_RVALID = 1;
    tick(dut);
  }
  dut.io_axi_R_RVALID = 0;
  dut.io_axi_R_RLAST = 0;
  dut.eval();
  check(dut.io_resp_valid && !dut.io_resp_fault &&
            dut.io_resp_data == data_base,
        "line-fill setup returned the wrong demand word");
  dut.io_resp_ready = 1;
  tick(dut);
  dut.io_resp_ready = 0;
  dut.eval();
  check(dut.io_fetch_ready && !dut.io_resp_valid,
        "line-fill setup did not return ICache to idle");
}

static void unaccepted_wrong_path_refill_is_cancelled(
    DUT &dut, std::uint32_t victim_line) {
  // Same direct-mapped index, different tag.  Besides checking that kill
  // suppresses ARVALID in the rf_req cycle, retain a known victim to prove
  // that a request cancelled before AR handshake has no cache side effect.
  constexpr std::uint32_t kOneTag = 1U << (5 + 4);
  constexpr std::uint32_t kVictimData = 0x51000000U;
  const std::uint32_t wrong_line = victim_line + kOneTag;
  fill_line(dut, victim_line, kVictimData);

  dut.io_fetch_addr = wrong_line;
  dut.io_fetch_valid = 1;
  dut.io_resp_ready = 1;
  dut.eval();
  check(dut.io_fetch_ready && dut.io_perf_miss,
        "wrong-path conflict miss was not accepted");
  tick(dut); // request enters s1
  dut.io_fetch_valid = 0;
  tick(dut); // queued miss enters rf_req
  dut.eval();
  check(dut.io_axi_AR_ARVALID && !dut.io_axi_AR_ARREADY,
        "wrong-path conflict miss did not reach an unaccepted rf_req");

  dut.io_kill = 1;
  dut.eval();
  check(!dut.io_axi_AR_ARVALID,
        "kill did not cancel an AR that had not handshaken");
  tick(dut);
  dut.io_kill = 0;
  dut.eval();
  check(!dut.io_axi_AR_ARVALID && dut.io_fetch_ready && !dut.io_resp_valid,
        "cancelled refill did not return directly to idle");

  dut.io_fetch_addr = victim_line;
  dut.io_fetch_valid = 1;
  dut.io_resp_ready = 0;
  dut.eval();
  check(dut.io_fetch_ready && dut.io_perf_hit && !dut.io_perf_miss,
        "cancelled wrong-path refill evicted the old victim line");
  tick(dut);
  dut.io_fetch_valid = 0;
  dut.eval();
  check(dut.io_resp_valid && dut.io_resp_data == kVictimData,
        "victim hit after refill cancellation returned wrong data");
  check(!dut.io_axi_AR_ARVALID,
        "victim access after refill cancellation unexpectedly re-refilled");
  dut.io_resp_ready = 1;
  tick(dut);
  dut.io_resp_ready = 0;
}

static void accepted_refill_is_drained_after_kill(
    DUT &dut, std::uint32_t line_address) {
  constexpr std::uint32_t kDataBase = 0x61000000U;
  dut.io_fetch_addr = line_address;
  dut.io_fetch_valid = 1;
  dut.io_resp_ready = 1;
  dut.eval();
  check(dut.io_fetch_ready, "accepted-kill test fetch was not accepted");
  tick(dut);
  dut.io_fetch_valid = 0;
  wait_until(dut, [&] { return bool(dut.io_axi_AR_ARVALID); },
             "accepted-kill test never issued AR", 8);
  dut.io_axi_AR_ARREADY = 1;
  tick(dut); // AR handshake: transaction must now be drained
  dut.io_axi_AR_ARREADY = 0;

  dut.io_kill = 1;
  dut.eval();
  check(!dut.io_axi_AR_ARVALID,
        "kill after AR handshake did not leave the R transaction drainable");
  tick(dut);
  dut.io_kill = 0;

  // A different line must remain backpressured while the accepted burst is
  // outstanding.  In particular, kill must not falsely return the MSHR idle.
  dut.io_fetch_addr = line_address + 0x40U;
  dut.io_fetch_valid = 1;
  for (std::uint32_t beat = 0; beat < 3; ++beat) {
    dut.io_axi_R_RDATA = kDataBase + beat;
    dut.io_axi_R_RRESP = 0;
    dut.io_axi_R_RLAST = 0;
    dut.io_axi_R_RVALID = 1;
    dut.eval();
    check(!dut.io_fetch_ready,
          "ICache stopped draining or accepted another line after kill");
    tick(dut);
  }
  dut.io_fetch_valid = 0;
  dut.io_axi_R_RDATA = kDataBase + 3;
  dut.io_axi_R_RLAST = 1;
  dut.io_axi_R_RVALID = 1;
  tick(dut);
  dut.io_axi_R_RVALID = 0;
  dut.io_axi_R_RLAST = 0;
  dut.eval();
  check(dut.io_fetch_ready && !dut.io_resp_valid,
        "killed demand did not disappear after accepted burst drained");

  // Once AR has handshaken the old victim was already invalidated.  The burst
  // must drain normally and may install its successfully returned line.
  dut.io_fetch_addr = line_address + 8U;
  dut.io_fetch_valid = 1;
  dut.io_resp_ready = 0;
  dut.eval();
  check(dut.io_fetch_ready && dut.io_perf_hit && !dut.io_perf_miss,
        "successfully drained accepted refill was not installed");
  tick(dut);
  dut.io_fetch_valid = 0;
  dut.eval();
  check(dut.io_resp_valid && dut.io_resp_data == kDataBase + 2,
        "installed refill returned the wrong word after demand kill");
  dut.io_resp_ready = 1;
  tick(dut);
  dut.io_resp_ready = 0;
}

static void exposed_ar_holds_until_handshake(DUT &dut,
                                             std::uint32_t line_address) {
  constexpr std::uint32_t kOneTag = 1U << (5 + 4);
  constexpr std::uint32_t kVictimData = 0x52000000U;
  const std::uint32_t wrong_line = line_address + kOneTag;
  fill_line(dut, line_address, kVictimData);

  dut.io_fetch_addr = wrong_line;
  dut.io_fetch_valid = 1;
  dut.io_resp_ready = 1;
  dut.eval();
  check(dut.io_fetch_ready, "AR-stability test fetch was not accepted");
  tick(dut);
  dut.io_fetch_valid = 0;
  wait_until(dut, [&] { return bool(dut.io_axi_AR_ARVALID); },
             "AR-stability test never issued AR", 8);
  const std::uint32_t held_addr = dut.io_axi_AR_ARADDR;
  const std::uint8_t held_len = dut.io_axi_AR_ARLEN;

  // Let VALID be sampled for one full stalled cycle.  From this point AXI
  // forbids withdrawing it even though no address handshake happened yet.
  check(!dut.io_axi_AR_ARREADY, "AR-stability test unexpectedly saw READY");
  tick(dut);
  dut.io_kill = 1;
  dut.eval();
  check(dut.io_axi_AR_ARVALID && dut.io_axi_AR_ARADDR == held_addr &&
            dut.io_axi_AR_ARLEN == held_len,
        "kill withdrew or changed an already-exposed stalled AR");
  tick(dut); // sticky discard is recorded while VALID remains stalled
  dut.io_kill = 0;
  dut.eval();
  check(dut.io_axi_AR_ARVALID && dut.io_axi_AR_ARADDR == held_addr &&
            dut.io_axi_AR_ARLEN == held_len,
        "stalled AR payload was not stable after kill deasserted");

  dut.io_axi_AR_ARREADY = 1;
  tick(dut);
  dut.io_axi_AR_ARREADY = 0;
  for (std::uint32_t beat = 0; beat < 4; ++beat) {
    dut.io_axi_R_RDATA = 0x62000000U + beat;
    dut.io_axi_R_RRESP = 0;
    dut.io_axi_R_RLAST = beat == 3;
    dut.io_axi_R_RVALID = 1;
    tick(dut);
  }
  dut.io_axi_R_RVALID = 0;
  dut.io_axi_R_RLAST = 0;
  dut.eval();
  check(dut.io_fetch_ready && !dut.io_resp_valid,
        "previously exposed killed AR did not drain back to idle");

  // Although AXI forced the exposed request to complete, it was marked
  // discard before its handshake and therefore must not evict the victim.
  dut.io_fetch_addr = line_address;
  dut.io_fetch_valid = 1;
  dut.io_resp_ready = 0;
  dut.eval();
  check(dut.io_fetch_ready && dut.io_perf_hit,
        "discarded exposed wrong-path AR evicted the victim line");
  tick(dut);
  dut.io_fetch_valid = 0;
  dut.eval();
  check(dut.io_resp_valid && dut.io_resp_data == kVictimData,
        "victim after exposed-AR discard returned wrong data");
  dut.io_resp_ready = 1;
  tick(dut);
  dut.io_resp_ready = 0;
}

static void early_restart_accepts_only_arrived_words(
    DUT &dut, std::uint32_t line_address) {
  constexpr std::array<std::uint32_t, 4> kWords = {
      0x71000000U, 0x71000001U, 0x71000002U, 0x71000003U};
  constexpr std::uint32_t kOtherHitData = 0x72000000U;
  const std::uint32_t demand = line_address + 4U; // keyword is beat 1
  const std::uint32_t other_hit_line = line_address + 0x40U;

  // Keep a complete line at another index so the test can distinguish a
  // legal full hit-under-refill from an illegal second miss allocation.
  fill_line(dut, other_hit_line, kOtherHitData);

  dut.io_fetch_addr = demand;
  dut.io_fetch_valid = 1;
  dut.io_resp_ready = 0;
  dut.eval();
  check(dut.io_fetch_ready, "early-restart demand was not accepted");
  tick(dut);
  dut.io_fetch_valid = 0;
  wait_until(dut, [&] { return bool(dut.io_axi_AR_ARVALID); },
             "early-restart demand never issued AR", 8);
  dut.io_axi_AR_ARREADY = 1;
  tick(dut);
  dut.io_axi_AR_ARREADY = 0;

  for (std::uint32_t beat = 0; beat < 2; ++beat) {
    dut.io_axi_R_RDATA = kWords[beat];
    dut.io_axi_R_RRESP = 0;
    dut.io_axi_R_RLAST = 0;
    dut.io_axi_R_RVALID = 1;
    tick(dut);
  }
  dut.io_axi_R_RVALID = 0;
  dut.eval();
  check(dut.io_resp_valid && dut.io_resp_addr == demand &&
            dut.io_resp_data == kWords[1],
        "keyword did not early-restart the original demand");

  // Beat 0 arrived before the keyword.  It may be accepted in the same cycle
  // that the original response retires, while the burst is still in flight.
  dut.io_fetch_addr = line_address;
  dut.io_fetch_valid = 1;
  dut.io_resp_ready = 1;
  // A later R beat in this exact cycle used to overwrite resp_data_reg with
  // old s1_waits=true after the new partial hit had latched beat 0.
  dut.io_axi_R_RDATA = kWords[2];
  dut.io_axi_R_RRESP = 0;
  dut.io_axi_R_RLAST = 0;
  dut.io_axi_R_RVALID = 1;
  dut.eval();
  check(dut.io_fetch_ready && dut.io_perf_hit,
        "already-arrived word was blocked until line tail");
  tick(dut);
  dut.io_axi_R_RVALID = 0;
  dut.io_fetch_valid = 0;
  dut.io_resp_ready = 0;
  dut.eval();
  check(dut.io_resp_valid && dut.io_resp_data == kWords[0],
        "partial-line hit returned the wrong arrived word");
  dut.io_resp_ready = 1;
  tick(dut);
  dut.io_resp_ready = 0;

  // A different uncached line is still a second miss and must remain
  // backpressured while the single MSHR is occupied.
  dut.io_fetch_addr = line_address + 0x80U;
  dut.io_fetch_valid = 1;
  for (int cycle = 0; cycle < 2; ++cycle) {
    dut.eval();
    check(!dut.io_fetch_ready && !dut.io_resp_valid,
          "second line miss bypassed an occupied single MSHR");
    tick(dut);
  }
  dut.io_fetch_valid = 0;

  // A complete hit at another index is independent of the MSHR and must not
  // wait for the tail beat of this refill.
  dut.io_fetch_addr = other_hit_line;
  dut.io_fetch_valid = 1;
  dut.eval();
  check(dut.io_fetch_ready && dut.io_perf_hit,
        "full hit at another index was blocked by refill tail");
  tick(dut);
  dut.io_fetch_valid = 0;
  dut.eval();
  check(dut.io_resp_valid && dut.io_resp_data == kOtherHitData,
        "full hit-under-refill returned wrong data");
  dut.io_resp_ready = 1;
  tick(dut);
  dut.io_resp_ready = 0;

  // The original demand has retired (s1_waits=false).  A later, unrelated
  // redirect must not discard this background fill.
  dut.io_kill = 1;
  tick(dut);
  dut.io_kill = 0;

  // Beat 3 has not arrived.  Hold its request under backpressure and make sure
  // no stale/half-filled data becomes a response before the R handshake.
  dut.io_fetch_addr = line_address + 12U;
  dut.io_fetch_valid = 1;
  for (int cycle = 0; cycle < 2; ++cycle) {
    dut.eval();
    check(!dut.io_fetch_ready && !dut.io_resp_valid,
          "unarrived word escaped partial-line backpressure");
    tick(dut);
  }

  dut.io_axi_R_RDATA = kWords[3];
  dut.io_axi_R_RRESP = 0;
  dut.io_axi_R_RLAST = 1;
  dut.io_axi_R_RVALID = 1;
  tick(dut);
  dut.io_axi_R_RVALID = 0;
  dut.io_axi_R_RLAST = 0;
  dut.eval();
  check(dut.io_fetch_ready && dut.io_perf_hit,
        "final word did not become fetchable after its R handshake");
  tick(dut); // held fetch is accepted after the line becomes complete
  dut.io_fetch_valid = 0;
  dut.eval();
  check(dut.io_resp_valid && dut.io_resp_data == kWords[3],
        "background refill was discarded by a later unrelated kill");
  dut.io_resp_ready = 1;
  tick(dut);
  dut.io_resp_ready = 0;
}

static void refill_error_revokes_partial_words(DUT &dut,
                                               std::uint32_t line_address) {
  dut.io_fetch_addr = line_address; // keyword is beat 0
  dut.io_fetch_valid = 1;
  dut.io_resp_ready = 0;
  dut.eval();
  check(dut.io_fetch_ready, "refill-error demand was not accepted");
  tick(dut);
  dut.io_fetch_valid = 0;
  wait_until(dut, [&] { return bool(dut.io_axi_AR_ARVALID); },
             "refill-error test never issued AR", 8);
  dut.io_axi_AR_ARREADY = 1;
  tick(dut);
  dut.io_axi_AR_ARREADY = 0;

  dut.io_axi_R_RDATA = 0x73000000U;
  dut.io_axi_R_RRESP = 0;
  dut.io_axi_R_RLAST = 0;
  dut.io_axi_R_RVALID = 1;
  tick(dut);
  dut.io_axi_R_RVALID = 0;
  dut.eval();
  check(dut.io_resp_valid && dut.io_resp_data == 0x73000000U,
        "successful keyword did not early-restart before later error");
  dut.io_resp_ready = 1;
  tick(dut);
  dut.io_resp_ready = 0;

  // Beat 0 is currently partial-valid.  A non-keyword error arriving in this
  // cycle must revoke that permission combinationally, before the sticky error
  // register is updated at the edge.
  dut.io_fetch_addr = line_address;
  dut.io_fetch_valid = 1;
  dut.io_axi_R_RDATA = 0xdead0001U;
  dut.io_axi_R_RRESP = 2;
  dut.io_axi_R_RLAST = 0;
  dut.io_axi_R_RVALID = 1;
  dut.eval();
  check(!dut.io_fetch_ready && !dut.io_resp_valid,
        "partial word remained fetchable in the RRESP-error cycle");
  tick(dut);
  dut.io_axi_R_RVALID = 0;
  dut.io_axi_R_RRESP = 0;
  for (int cycle = 0; cycle < 2; ++cycle) {
    dut.eval();
    check(!dut.io_fetch_ready && !dut.io_resp_valid,
          "sticky refill error did not revoke prior partial words");
    tick(dut);
  }

  for (std::uint32_t beat = 2; beat < 4; ++beat) {
    dut.io_axi_R_RDATA = 0x73000000U + beat;
    dut.io_axi_R_RRESP = 0;
    dut.io_axi_R_RLAST = beat == 3;
    dut.io_axi_R_RVALID = 1;
    dut.eval();
    check(!dut.io_fetch_ready,
          "errored refill exposed a partial word before its tail drained");
    tick(dut);
  }
  dut.io_axi_R_RVALID = 0;
  dut.io_axi_R_RLAST = 0;
  dut.eval();
  check(dut.io_fetch_ready && dut.io_perf_miss && !dut.io_perf_hit,
        "line containing a non-keyword RRESP error was installed");
  tick(dut); // held fetch starts a clean retry
  dut.io_fetch_valid = 0;
  wait_until(dut, [&] { return bool(dut.io_axi_AR_ARVALID); },
             "errored line did not issue a clean retry", 8);
}
#else
static void executable_memory_uses_single_beat(DUT &dut,
                                               std::uint32_t address,
                                               std::uint32_t response_data) {
  dut.io_fetch_addr = address;
  dut.io_fetch_valid = 1;
  dut.eval();
  check(dut.io_fetch_ready, "cache-disabled ICache did not accept a fetch");
  // 口径修正后，缓存禁用配置下 perf_hit/perf_miss/ARLEN 都是编译期常量
  // （非缓存取指不算 miss），firtool 会移除这些常量输出端口——缓存禁用时
  // 不存在"缺失"，因此这里不再观察 perf_miss。
  tick(dut);
  dut.io_fetch_valid = 0;

  wait_until(dut, [&] { return bool(dut.io_axi_AR_ARVALID); },
             "cache-disabled fetch never issued an AXI read", 8);
  check(dut.io_axi_AR_ARADDR == address,
        "cache-disabled fetch aligned an address as if refilling a line");
  dut.io_axi_AR_ARREADY = 1;
  tick(dut);
  dut.io_axi_AR_ARREADY = 0;
  dut.io_axi_R_RDATA = response_data;
  dut.io_axi_R_RRESP = 0;
  dut.io_axi_R_RLAST = 1;
  dut.io_axi_R_RVALID = 1;
  tick(dut);
  dut.io_axi_R_RVALID = 0;
  dut.io_axi_R_RLAST = 0;
  dut.eval();

  check(dut.io_resp_valid && !dut.io_resp_fault,
        "cache-disabled AXI response did not complete the fetch");
  check(dut.io_resp_addr == address && dut.io_resp_data == response_data,
        "cache-disabled fetch returned the wrong address or data");
  check(dut.io_access_fault_resp == 0,
        "successful cache-disabled fetch retained a fault response code");

  dut.io_resp_ready = 1;
  tick(dut);
  dut.io_resp_ready = 0;
  dut.eval();
  check(!dut.io_resp_valid,
        "cache-disabled response remained valid after its handshake");
}
#endif

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
#ifdef TEST_NPC
#ifdef TEST_CACHE_DISABLED
  constexpr const char *kTestName =
      "disabled ICache single-beat path for direct NPC";
#else
  constexpr const char *kTestName = "ICache PMA enforcement for direct NPC";
#endif
#elif defined(TEST_CACHE_DISABLED)
  constexpr const char *kTestName = "disabled ICache single-beat path for SoC";
#elif defined(TEST_CHIPLINK)
  constexpr const char *kTestName =
      "ICache PMA enforcement with ChipLink enabled";
#else
  constexpr const char *kTestName =
      "ICache PMA enforcement with ChipLink disabled";
#endif
  return run_test(kTestName, [] {
    DUT dut;
    defaults(dut);
    reset(dut);

    // Every one of these addresses used to be treated as cacheable and could
    // generate a four-beat line read into a device or an unmapped hole.
    constexpr std::array<std::uint32_t, 5> kIllegalFetches = {
        0x02000000U, // CLINT
        0x10000000U, // UART
        0x21000000U, // VGA/MMIO
        0x20000000U, // unmapped hole
#ifdef TEST_NPC
        0x80040000U // first byte beyond the direct NPC's 256 KiB RAM
#elif defined(TEST_CHIPLINK)
        0x40000000U // ChipLink MMIO remains non-executable
#else
        0xc0000000U // ChipLink memory is a hole when the link is disabled
#endif
    };
    for (const auto address : kIllegalFetches) {
      consume_local_fault(dut, address);
    }

#ifdef TEST_CACHE_DISABLED
#ifdef TEST_NPC
    constexpr std::uint32_t kExecutableAddress = 0x80000004U;
#else
    constexpr std::uint32_t kExecutableAddress = 0x30000004U;
#endif
    defaults(dut);
    executable_memory_uses_single_beat(dut, kExecutableAddress, 0x00000013U);
    // A second request to exactly the same word must still go to AXI.  This
    // proves that the singleton placeholder array did not accidentally turn
    // the disabled path into a one-entry cache while silencing the warning.
    executable_memory_uses_single_beat(dut, kExecutableAddress, 0x00100093U);
#else
#ifdef TEST_NPC
    constexpr std::uint32_t kCacheTestBase = 0x80000000U;
    executable_memory_uses_line_refill(dut, 0x80000004U, 0x80000000U);
#else
    constexpr std::uint32_t kCacheTestBase = 0x30000000U;
    executable_memory_uses_line_refill(dut, 0x30000004U, 0x30000000U);
#endif
#if defined(TEST_CHIPLINK) && !defined(TEST_NPC)
    // With the link enabled, its MEM aperture is both executable and cacheable.
    reset(dut);
    defaults(dut);
    executable_memory_uses_line_refill(dut, 0xc0000004U, 0xc0000000U);
#endif

    defaults(dut);
    reset(dut);
#ifdef TEST_NPC
    missing_final_rlast_faults_and_does_not_install(
        dut, 0x8000000cU, 0x80000000U);
#else
    missing_final_rlast_faults_and_does_not_install(
        dut, 0x3000000cU, 0x30000000U);
#endif

    defaults(dut);
    reset(dut);
    unaccepted_wrong_path_refill_is_cancelled(dut, kCacheTestBase + 0x1000U);

    defaults(dut);
    reset(dut);
    accepted_refill_is_drained_after_kill(dut, kCacheTestBase + 0x2000U);

    defaults(dut);
    reset(dut);
    exposed_ar_holds_until_handshake(dut, kCacheTestBase + 0x3000U);

    defaults(dut);
    reset(dut);
    early_restart_accepts_only_arrived_words(dut,
                                             kCacheTestBase + 0x4000U);

    defaults(dut);
    reset(dut);
    refill_error_revokes_partial_words(dut, kCacheTestBase + 0x5000U);
#endif
    dut.final();
  });
}
