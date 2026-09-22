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
  dut.io_memory_R_RLAST = 0;
  dut.io_memory_R_RVALID = 0;
}

static void finish_read(DUT &dut, uint32_t data) {
  dut.io_memory_R_RDATA = data;
  dut.io_memory_R_RLAST = 1;
  dut.io_memory_R_RVALID = 1;
  tick(dut); // memory -> skid
  dut.io_memory_R_RVALID = 0;
  dut.eval();
  check(dut.io_ifu_R_RVALID || dut.io_lsu_R_RVALID,
        "read response did not reach the granted master");
  tick(dut); // granted master consumes the skid entry
}

static void test_multibeat_write(DUT &dut) {
  dut.io_memory_AW_AWREADY = 1;
  dut.io_memory_W_WREADY = 1;
  dut.io_lsu_AW_AWADDR = 0x80000100U;
  dut.io_lsu_AW_AWVALID = 1;
  dut.io_lsu_W_WVALID = 1;
  dut.io_lsu_W_WDATA = 0x11111111U;
  dut.io_lsu_W_WLAST = 0;

  tick(dut); // idle -> write request
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
}

static void test_round_robin(DUT &dut) {
  dut.io_memory_AR_ARREADY = 1;
  dut.io_ifu_AR_ARADDR = 0x80001000U;
  dut.io_lsu_AR_ARADDR = 0x80002000U;
  dut.io_ifu_AR_ARVALID = 1;
  dut.io_lsu_AR_ARVALID = 1;

  tick(dut); // last owner was LSU, so simultaneous requests choose IFU
  dut.eval();
  check(dut.io_memory_AR_ARVALID,
        "arbiter did not issue the first simultaneous read");
  const uint32_t first = dut.io_memory_AR_ARADDR;
  tick(dut);
  finish_read(dut, 0xaaaa0001U);

  tick(dut); // choose the other requester
  dut.eval();
  check(dut.io_memory_AR_ARVALID,
        "arbiter did not issue the second simultaneous read");
  const uint32_t second = dut.io_memory_AR_ARADDR;
  check(first != second,
        "round-robin granted the same master twice while both requested");
  check((first == 0x80001000U && second == 0x80002000U) ||
            (first == 0x80002000U && second == 0x80001000U),
        "round-robin forwarded an unexpected address");
  tick(dut);
  finish_read(dut, 0xbbbb0002U);
  dut.io_ifu_AR_ARVALID = 0;
  dut.io_lsu_AR_ARVALID = 0;
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  return run_test("AXI arbiter burst and fairness", [] {
    DUT dut;
    defaults(dut);
    reset(dut);
    test_multibeat_write(dut);
    test_round_robin(dut);
    dut.final();
  });
}
