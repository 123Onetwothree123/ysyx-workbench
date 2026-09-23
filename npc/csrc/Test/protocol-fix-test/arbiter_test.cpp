#include "Vysyx_26030103_AXI5Arbiter.h"
#include "test_common.hpp"
#include <verilated.h>

using DUT = Vysyx_26030103_AXI5Arbiter;

static void defaults(DUT &dut) {
  dut.io_ifu_AR_ARADDR = 0;
  dut.io_ifu_AR_ARLEN = 0;
  dut.io_ifu_AR_ARVALID = 0;

  dut.io_lsu_AW_AWADDR = 0;
  dut.io_lsu_AW_AWSIZE = 2;
  dut.io_lsu_AW_AWVALID = 0;
  dut.io_lsu_W_WDATA = 0;
  dut.io_lsu_W_WSTRB = 0xf;
  dut.io_lsu_W_WLAST = 0;
  dut.io_lsu_W_WVALID = 0;
  dut.io_lsu_B_BREADY = 1;
  dut.io_lsu_AR_ARADDR = 0;
  dut.io_lsu_AR_ARLEN = 0;
  dut.io_lsu_AR_ARSIZE = 2;
  dut.io_lsu_AR_ARVALID = 0;
  dut.io_lsu_R_RREADY = 1;

  dut.io_memory_AW_AWREADY = 0;
  dut.io_memory_W_WREADY = 0;
  dut.io_memory_B_BRESP = 0;
  dut.io_memory_B_BVALID = 0;
  dut.io_memory_AR_ARREADY = 0;
  dut.io_memory_R_RDATA = 0;
  dut.io_memory_R_RRESP = 0;
  dut.io_memory_R_RID = 0;
  dut.io_memory_R_RLAST = 0;
  dut.io_memory_R_RVALID = 0;
}

static void finish_read(DUT &dut, uint8_t rid, uint32_t data,
                        bool expect_lsu) {
  dut.io_memory_R_RID = rid;
  dut.io_memory_R_RDATA = data;
  dut.io_memory_R_RLAST = 1;
  dut.io_memory_R_RVALID = 1;
  tick(dut); // memory -> skid
  dut.io_memory_R_RVALID = 0;
  dut.eval();
  check(static_cast<bool>(dut.io_lsu_R_RVALID) == expect_lsu &&
            static_cast<bool>(dut.io_ifu_R_RVALID) == !expect_lsu,
        "read response ID was routed to the wrong master");
  check((expect_lsu ? dut.io_lsu_R_RDATA : dut.io_ifu_R_RDATA) == data,
        "read response data was corrupted by ID routing");
  tick(dut); // granted master consumes the skid entry
  dut.io_memory_R_RLAST = 0;
}

static void test_multibeat_write(DUT &dut) {
  dut.io_memory_AW_AWREADY = 1;
  dut.io_memory_W_WREADY = 1;
  dut.io_lsu_AW_AWADDR = 0x80000100U;
  dut.io_lsu_AW_AWVALID = 1;
  dut.io_lsu_W_WVALID = 1;
  dut.io_lsu_W_WDATA = 0x11111111U;
  dut.io_lsu_W_WLAST = 0;
  // A fetch address must be accepted independently while the LSU write starts.
  dut.io_memory_AR_ARREADY = 1;
  dut.io_ifu_AR_ARADDR = 0x80000040U;
  dut.io_ifu_AR_ARVALID = 1;

  dut.eval();
  check(dut.io_memory_AR_ARVALID && dut.io_memory_AR_ARID == 0,
        "IFU read was blocked by an independent LSU write");
  tick(dut); // accept IFU AR and enter write request
  dut.io_ifu_AR_ARVALID = 0;
  dut.eval();
  check(dut.io_memory_AW_AWVALID && dut.io_memory_W_WVALID,
        "first AW/W beat was not forwarded");
  tick(dut); // AW and first W beat
  dut.io_lsu_AW_AWVALID = 0;

  dut.io_lsu_W_WDATA = 0x22222222U;
  dut.io_lsu_W_WLAST = 0;
  dut.eval();
  check(dut.io_lsu_W_WREADY && dut.io_memory_W_WVALID,
        "arbiter stopped before WLAST on the second beat");
  tick(dut);
  check(!dut.io_lsu_B_BVALID,
        "arbiter entered write response before WLAST");

  dut.io_lsu_W_WDATA = 0x33333333U;
  dut.io_lsu_W_WLAST = 1;
  dut.eval();
  check(dut.io_lsu_W_WREADY && dut.io_memory_W_WLAST,
        "final WLAST beat was not forwarded");
  tick(dut);
  dut.io_lsu_W_WVALID = 0;

  dut.io_memory_B_BRESP = 0;
  dut.io_memory_B_BVALID = 1;
  dut.eval();
  check(dut.io_lsu_B_BVALID && dut.io_lsu_B_BRESP == 0,
        "write response was not returned to LSU");
  tick(dut);
  dut.io_memory_B_BVALID = 0;

  finish_read(dut, 0, 0x1234abcdU, false);
}

static void test_round_robin(DUT &dut) {
  dut.io_memory_AR_ARREADY = 1;
  dut.io_ifu_AR_ARADDR = 0x80001000U;
  dut.io_lsu_AR_ARADDR = 0x80002000U;
  dut.io_ifu_AR_ARVALID = 1;
  dut.io_lsu_AR_ARVALID = 1;

  dut.eval();
  check(dut.io_memory_AR_ARVALID,
        "arbiter did not issue the first simultaneous read");
  const uint32_t first = dut.io_memory_AR_ARADDR;
  const uint8_t first_id = dut.io_memory_AR_ARID;
  tick(dut); // first AR is outstanding; no response yet

  dut.eval();
  check(dut.io_memory_AR_ARVALID,
        "arbiter did not allow the second read to become outstanding");
  const uint32_t second = dut.io_memory_AR_ARADDR;
  const uint8_t second_id = dut.io_memory_AR_ARID;
  check(first != second,
        "round-robin granted the same master twice while both requested");
  check((first == 0x80001000U && second == 0x80002000U) ||
            (first == 0x80002000U && second == 0x80001000U),
        "round-robin forwarded an unexpected address");
  check(first_id != second_id && first_id <= 1 && second_id <= 1,
        "arbiter did not assign distinct IFU/LSU read IDs");
  tick(dut); // second AR accepted before either response
  dut.io_ifu_AR_ARVALID = 0;
  dut.io_lsu_AR_ARVALID = 0;

  // Return responses in reverse order and without a bubble.  The second beat
  // must enter the skid buffer in the same cycle the first is consumed, with
  // ownership switching solely from RID.
  dut.io_memory_R_RID = second_id;
  dut.io_memory_R_RDATA = 0xbbbb0002U;
  dut.io_memory_R_RLAST = 1;
  dut.io_memory_R_RVALID = 1;
  tick(dut);
  dut.eval();
  check(static_cast<bool>(dut.io_lsu_R_RVALID) == (second_id == 1) &&
            static_cast<bool>(dut.io_ifu_R_RVALID) == (second_id == 0),
        "first interleaved RID was routed to the wrong master");

  dut.io_memory_R_RID = first_id;
  dut.io_memory_R_RDATA = 0xaaaa0001U;
  tick(dut); // consume previous skid entry and capture this response
  dut.eval();
  check(static_cast<bool>(dut.io_lsu_R_RVALID) == (first_id == 1) &&
            static_cast<bool>(dut.io_ifu_R_RVALID) == (first_id == 0),
        "same-cycle skid pop/push lost the new response owner");
  check((first_id == 1 ? dut.io_lsu_R_RDATA : dut.io_ifu_R_RDATA) ==
            0xaaaa0001U,
        "same-cycle skid pop/push corrupted response data");
  dut.io_memory_R_RVALID = 0;
  dut.io_memory_R_RLAST = 0;
  tick(dut);
}

static void test_stalled_ar_grant_is_stable(DUT &dut) {
  dut.io_memory_AR_ARREADY = 0;
  dut.io_lsu_AR_ARADDR = 0x80006000U;
  dut.io_lsu_AR_ARVALID = 1;
  dut.io_ifu_AR_ARVALID = 0;
  dut.eval();
  check(dut.io_memory_AR_ARVALID && dut.io_memory_AR_ARID == 1,
        "LSU stalled AR was not selected");
  const auto held_addr = dut.io_memory_AR_ARADDR;
  tick(dut);

  // A lower-ID IFU request arriving later must not steal the stalled channel.
  dut.io_ifu_AR_ARADDR = 0x80007000U;
  dut.io_ifu_AR_ARVALID = 1;
  for (int cycle = 0; cycle < 3; ++cycle) {
    dut.eval();
    check(dut.io_memory_AR_ARVALID && dut.io_memory_AR_ARID == 1 &&
              dut.io_memory_AR_ARADDR == held_addr,
          "arbiter changed AR payload while ARREADY was low");
    tick(dut);
  }
  dut.io_memory_AR_ARREADY = 1;
  tick(dut);
  dut.io_lsu_AR_ARVALID = 0;
  dut.eval();
  check(dut.io_memory_AR_ARVALID && dut.io_memory_AR_ARID == 0 &&
            dut.io_memory_AR_ARADDR == 0x80007000U,
        "IFU request did not issue after stalled LSU AR handshook");
  tick(dut);
  dut.io_ifu_AR_ARVALID = 0;

  // Release both outstanding IDs before the next test.
  finish_read(dut, 1, 0x60006000U, true);
  finish_read(dut, 0, 0x70007000U, false);
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  return run_test("AXI arbiter concurrent reads/writes and ID routing", [] {
    DUT dut;
    defaults(dut);
    reset(dut);
    test_multibeat_write(dut);
    test_stalled_ar_grant_is_stable(dut);
    test_round_robin(dut);
    dut.final();
  });
}
