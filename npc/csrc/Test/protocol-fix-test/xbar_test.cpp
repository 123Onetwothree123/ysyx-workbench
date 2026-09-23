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
  dut.io_in_AR_ARID = 0;
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
  dut.io_SoCBus_R_RID = 0;
  dut.io_SoCBus_R_RRESP = 0;
  dut.io_SoCBus_R_RLAST = 0;
  dut.io_SoCBus_R_RVALID = 0;

  dut.io_CLINT_AW_AWREADY = 1;
  dut.io_CLINT_W_WREADY = 1;
  dut.io_CLINT_B_BRESP = 0;
  dut.io_CLINT_B_BVALID = 0;
  dut.io_CLINT_AR_ARREADY = 0;
  dut.io_CLINT_R_RDATA = 0;
  dut.io_CLINT_R_RID = 0;
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

static void external_read_burst(DUT &dut, std::uint32_t address) {
  dut.io_in_AR_ARADDR = address;
  dut.io_in_AR_ARLEN = 3;
  dut.io_in_AR_ARVALID = 1;
  dut.eval();
  check(dut.io_in_AR_ARREADY, "xbar did not accept a mapped MROM read");
  tick(dut);
  dut.io_in_AR_ARVALID = 0;

  dut.eval();
  check(dut.io_SoCBus_AR_ARVALID,
        "mapped MROM read was not forwarded to the SoC bus");
  check(dut.io_SoCBus_AR_ARADDR == address &&
            dut.io_SoCBus_AR_ARLEN == 3,
        "mapped read burst metadata was corrupted");
  dut.io_SoCBus_AR_ARREADY = 1;
  tick(dut);
  dut.io_SoCBus_AR_ARREADY = 0;

  for (unsigned beat = 0; beat < 4; ++beat) {
    dut.io_SoCBus_R_RDATA = 0x1000U + beat;
    dut.io_SoCBus_R_RID = dut.io_in_AR_ARID;
    dut.io_SoCBus_R_RRESP = 0;
    dut.io_SoCBus_R_RLAST = beat == 3;
    dut.io_SoCBus_R_RVALID = 1;
    dut.eval();
    check(dut.io_SoCBus_R_RREADY,
          "xbar did not accept a mapped read response");
    tick(dut);
    dut.io_SoCBus_R_RVALID = 0;
    dut.io_SoCBus_R_RLAST = 0;
    dut.eval();
    check(dut.io_in_R_RVALID && dut.io_in_R_RDATA == 0x1000U + beat,
          "mapped read response was not forwarded upstream");
    check(bool(dut.io_in_R_RLAST) == (beat == 3),
          "mapped read response corrupted RLAST");
    tick(dut);
  }
  dut.io_SoCBus_R_RVALID = 0;
  dut.io_SoCBus_R_RLAST = 0;
  dut.io_in_AR_ARLEN = 0;
}

static void clint_burst_decerr(DUT &dut) {
  // The complete CPU elaboration specializes AWLEN to zero because today's
  // LSU only emits single-beat writes.  Enter the otherwise real drain state
  // directly so this C++ regression still checks its WLAST/DECERR behavior.
  dut.rootp->ysyx_26030103_AXI5Xbar__DOT__WriteTargetReg = 1;
  dut.rootp->ysyx_26030103_AXI5Xbar__DOT__writeState = 5;
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

static void unmapped_read_burst_decerr(DUT &dut) {
  dut.io_in_AR_ARADDR = 0x20000000U; // SoC address-map hole
  dut.io_in_AR_ARLEN = 3;
  dut.io_in_AR_ARSIZE = 2;
  dut.io_in_AR_ARBURST = 1;
  dut.io_in_AR_ARVALID = 1;
  dut.eval();
  check(dut.io_in_AR_ARREADY, "xbar did not accept an unmapped read");
  tick(dut);
  dut.io_in_AR_ARVALID = 0;

  // Local DECERR uses the same buffered R path as downstream responses.
  tick(dut);

  // The request must be completed locally and never leak to a real device.
  for (unsigned beat = 0; beat < 4; ++beat) {
    dut.eval();
    check(!dut.io_SoCBus_AR_ARVALID && !dut.io_CLINT_AR_ARVALID,
          "unmapped read was forwarded downstream");
    check(dut.io_in_R_RVALID && dut.io_in_R_RRESP == 3,
          "unmapped read did not return DECERR");
    check(dut.io_in_R_RDATA == 0,
          "unmapped read returned nonzero placeholder data");
    check(bool(dut.io_in_R_RLAST) == (beat == 3),
          "unmapped read burst asserted RLAST on the wrong beat");
    tick(dut);
  }
  dut.eval();
  check(!dut.io_in_R_RVALID,
        "unmapped read response remained valid after RLAST");
  dut.io_in_AR_ARLEN = 0;
}

static void crossing_read_decerr(DUT &dut) {
  // The first word is in MROM, but the second word is outside that manager.
  // Routing based only on the start address would make this request unsafe.
  dut.io_in_AR_ARADDR = 0x3ffffffcU;
  dut.io_in_AR_ARLEN = 1;
  dut.io_in_AR_ARVALID = 1;
  tick(dut);
  dut.io_in_AR_ARVALID = 0;
  tick(dut);
  for (unsigned beat = 0; beat < 2; ++beat) {
    dut.eval();
    check(!dut.io_SoCBus_AR_ARVALID && !dut.io_CLINT_AR_ARVALID,
          "cross-region read was forwarded downstream");
    check(dut.io_in_R_RVALID && dut.io_in_R_RRESP == 3,
          "cross-region read did not return DECERR");
    check(bool(dut.io_in_R_RLAST) == (beat == 1),
          "cross-region read returned the wrong burst length");
    tick(dut);
  }
  dut.io_in_AR_ARLEN = 0;
}

static void unmapped_write_decerr(DUT &dut) {
  dut.io_in_AW_AWADDR = 0x20000000U;
  dut.io_in_AW_AWVALID = 1;
  dut.io_in_W_WDATA = 0xdeadbeefU;
  dut.io_in_W_WLAST = 1;
  dut.io_in_W_WVALID = 1;
  dut.eval();
  check(dut.io_in_AW_AWREADY && dut.io_in_W_WREADY,
        "xbar did not accept an unmapped write");
  tick(dut);
  dut.io_in_AW_AWVALID = 0;
  dut.io_in_W_WVALID = 0;

  dut.eval();
  check(!dut.io_SoCBus_AW_AWVALID && !dut.io_SoCBus_W_WVALID &&
            !dut.io_CLINT_AW_AWVALID && !dut.io_CLINT_W_WVALID,
        "unmapped write was forwarded downstream");
  check(dut.io_in_B_BVALID && dut.io_in_B_BRESP == 3,
        "unmapped write did not return DECERR");
  tick(dut);
}

static void readonly_mrom_write_decerr(DUT &dut) {
  dut.io_in_AW_AWADDR = 0x30000000U;
  dut.io_in_AW_AWVALID = 1;
  dut.io_in_W_WDATA = 0x12345678U;
  dut.io_in_W_WLAST = 1;
  dut.io_in_W_WVALID = 1;
  dut.eval();
  check(dut.io_in_AW_AWREADY && dut.io_in_W_WREADY,
        "xbar did not accept a write targeting read-only MROM");
  tick(dut);
  dut.io_in_AW_AWVALID = 0;
  dut.io_in_W_WVALID = 0;

  dut.eval();
  check(!dut.io_SoCBus_AW_AWVALID && !dut.io_SoCBus_W_WVALID &&
            !dut.io_CLINT_AW_AWVALID && !dut.io_CLINT_W_WVALID,
        "write targeting read-only MROM escaped to a downstream slave");
  check(dut.io_in_B_BVALID && dut.io_in_B_BRESP == 3,
        "write targeting read-only MROM did not return finite DECERR");
  tick(dut);
}

static void return_soc_read(DUT &dut, unsigned id, std::uint32_t data) {
  dut.io_SoCBus_R_RID = id;
  dut.io_SoCBus_R_RDATA = data;
  dut.io_SoCBus_R_RRESP = 0;
  dut.io_SoCBus_R_RLAST = 1;
  dut.io_SoCBus_R_RVALID = 1;
  dut.eval();
  check(dut.io_SoCBus_R_RREADY,
        "xbar did not accept a SoCBus cleanup response");
  tick(dut);
  dut.io_SoCBus_R_RVALID = 0;
  dut.io_SoCBus_R_RLAST = 0;
  dut.eval();
  check(dut.io_in_R_RVALID && dut.io_in_R_RID == id &&
            dut.io_in_R_RDATA == data && dut.io_in_R_RLAST,
        "SoCBus cleanup response was corrupted");
  tick(dut);
}

static void return_clint_read(DUT &dut, unsigned id, std::uint32_t data) {
  dut.io_CLINT_R_RID = id;
  dut.io_CLINT_R_RDATA = data;
  dut.io_CLINT_R_RRESP = 0;
  dut.io_CLINT_R_RLAST = 1;
  dut.io_CLINT_R_RVALID = 1;
  dut.eval();
  check(dut.io_CLINT_R_RREADY,
        "xbar did not accept a CLINT cleanup response");
  tick(dut);
  dut.io_CLINT_R_RVALID = 0;
  dut.io_CLINT_R_RLAST = 0;
  dut.eval();
  check(dut.io_in_R_RVALID && dut.io_in_R_RID == id &&
            dut.io_in_R_RDATA == data && dut.io_in_R_RLAST,
        "CLINT cleanup response was corrupted");
  tick(dut);
}

static void ar_payload_stable_while_stalled(DUT &dut) {
  // Present high RID first, then accept a lower RID while downstream stalls.
  // A raw PriorityEncoder would switch the already-valid AR payload.
  dut.io_SoCBus_AR_ARREADY = 0;
  dut.io_in_AR_ARID = 1;
  dut.io_in_AR_ARADDR = 0x80006000U;
  dut.io_in_AR_ARVALID = 1;
  tick(dut);
  dut.io_in_AR_ARID = 0;
  dut.io_in_AR_ARADDR = 0x80007000U;
  dut.eval();
  check(dut.io_SoCBus_AR_ARVALID && dut.io_SoCBus_AR_ARID == 1 &&
            dut.io_SoCBus_AR_ARADDR == 0x80006000U,
        "initial stalled SoCBus AR payload was incorrect");
  tick(dut);
  dut.io_in_AR_ARVALID = 0;
  for (int cycle = 0; cycle < 3; ++cycle) {
    dut.eval();
    check(dut.io_SoCBus_AR_ARVALID && dut.io_SoCBus_AR_ARID == 1 &&
              dut.io_SoCBus_AR_ARADDR == 0x80006000U,
          "SoCBus AR payload changed while ARREADY was low");
    tick(dut);
  }
  dut.io_SoCBus_AR_ARREADY = 1;
  dut.eval();
  check(dut.io_SoCBus_AR_ARID == 1 &&
            dut.io_SoCBus_AR_ARADDR == 0x80006000U,
        "SoCBus AR grant changed on its handshake cycle");
  tick(dut);
  dut.eval();
  check(dut.io_SoCBus_AR_ARVALID && dut.io_SoCBus_AR_ARID == 0 &&
            dut.io_SoCBus_AR_ARADDR == 0x80007000U,
        "waiting lower SoCBus RID was not issued after held RID");
  tick(dut);
  dut.io_SoCBus_AR_ARREADY = 0;
  return_soc_read(dut, 0, 0x60000000U);
  return_soc_read(dut, 1, 0x70000001U);

  // Exercise the independent CLINT AR grant lock in the same ordering.
  dut.io_CLINT_AR_ARREADY = 0;
  dut.io_in_AR_ARID = 1;
  dut.io_in_AR_ARADDR = 0x0200bff8U;
  dut.io_in_AR_ARVALID = 1;
  tick(dut);
  dut.io_in_AR_ARID = 0;
  dut.io_in_AR_ARADDR = 0x0200bffcU;
  tick(dut);
  dut.io_in_AR_ARVALID = 0;
  for (int cycle = 0; cycle < 3; ++cycle) {
    dut.eval();
    check(dut.io_CLINT_AR_ARVALID && dut.io_CLINT_AR_ARID == 1 &&
              dut.io_CLINT_AR_ARADDR == 0x0200bff8U,
          "CLINT AR payload changed while ARREADY was low");
    tick(dut);
  }
  dut.io_CLINT_AR_ARREADY = 1;
  dut.eval();
  check(dut.io_CLINT_AR_ARID == 1 &&
            dut.io_CLINT_AR_ARADDR == 0x0200bff8U,
        "CLINT AR grant changed on its handshake cycle");
  tick(dut);
  dut.eval();
  check(dut.io_CLINT_AR_ARVALID && dut.io_CLINT_AR_ARID == 0 &&
            dut.io_CLINT_AR_ARADDR == 0x0200bffcU,
        "waiting lower CLINT RID was not issued after held RID");
  tick(dut);
  dut.io_CLINT_AR_ARREADY = 0;
  return_clint_read(dut, 0, 0x02000000U);
  return_clint_read(dut, 1, 0x02000001U);
  dut.io_in_AR_ARID = 0;
}

static void two_inflight_reads_preserve_metadata(DUT &dut) {
  dut.io_SoCBus_AR_ARREADY = 1;

  dut.io_in_AR_ARID = 0;
  dut.io_in_AR_ARADDR = 0x80001000U;
  dut.io_in_AR_ARLEN = 0;
  dut.io_in_AR_ARVALID = 1;
  dut.eval();
  check(dut.io_in_AR_ARREADY, "xbar rejected the first in-flight read");
  tick(dut);

  // The first request is issued downstream while the distinct second RID is
  // accepted from the arbiter in the same cycle.
  dut.io_in_AR_ARID = 1;
  dut.io_in_AR_ARADDR = 0x80002000U;
  dut.eval();
  check(dut.io_in_AR_ARREADY && dut.io_SoCBus_AR_ARVALID &&
            dut.io_SoCBus_AR_ARID == 0 &&
            dut.io_SoCBus_AR_ARADDR == 0x80001000U,
        "xbar did not issue RID 0 while accepting RID 1");
  tick(dut);
  dut.io_in_AR_ARVALID = 0;

  dut.eval();
  check(dut.io_SoCBus_AR_ARVALID &&
            dut.io_SoCBus_AR_ARID == 1 &&
            dut.io_SoCBus_AR_ARADDR == 0x80002000U,
        "second AR was not issued before any R response");
  tick(dut);
  dut.io_SoCBus_AR_ARREADY = 0;

  // An ID cannot be reused until its prior RLAST is consumed.
  dut.io_in_AR_ARID = 0;
  dut.io_in_AR_ARADDR = 0x80003000U;
  dut.io_in_AR_ARVALID = 1;
  dut.eval();
  check(!dut.io_in_AR_ARREADY,
        "xbar reused an RID which was still outstanding");
  dut.io_in_AR_ARVALID = 0;

  // Return RID 1 before RID 0.  The Xbar must use RID rather than request
  // order, and the response buffer must remain stable under backpressure.
  dut.io_in_R_RREADY = 0;
  dut.io_SoCBus_R_RID = 1;
  dut.io_SoCBus_R_RDATA = 0x33334444U;
  dut.io_SoCBus_R_RLAST = 1;
  dut.io_SoCBus_R_RVALID = 1;
  dut.eval();
  check(dut.io_SoCBus_R_RREADY,
        "xbar did not accept the out-of-order RID 1 response");
  tick(dut);
  dut.io_SoCBus_R_RVALID = 0;
  dut.io_SoCBus_R_RLAST = 0;
  for (int cycle = 0; cycle < 3; ++cycle) {
    dut.eval();
    check(dut.io_in_R_RVALID && dut.io_in_R_RID == 1 &&
              dut.io_in_R_RDATA == 0x33334444U && dut.io_in_R_RLAST,
          "buffered RID 1 response changed under backpressure");
    tick(dut);
  }
  dut.io_in_R_RREADY = 1;
  tick(dut);

  dut.io_SoCBus_R_RID = 0;
  dut.io_SoCBus_R_RDATA = 0x11112222U;
  dut.io_SoCBus_R_RLAST = 1;
  dut.io_SoCBus_R_RVALID = 1;
  dut.eval();
  check(dut.io_SoCBus_R_RREADY,
        "xbar did not accept the later RID 0 response");
  tick(dut);
  dut.io_SoCBus_R_RVALID = 0;
  dut.io_SoCBus_R_RLAST = 0;
  dut.eval();
  check(dut.io_in_R_RVALID && dut.io_in_R_RID == 0 &&
            dut.io_in_R_RDATA == 0x11112222U && dut.io_in_R_RLAST,
        "xbar returned RID 0 with corrupted metadata");
  tick(dut);
  dut.io_in_AR_ARID = 0;
}

static void zero_latency_read_response(DUT &dut) {
  dut.io_SoCBus_AR_ARREADY = 0;
  dut.io_in_AR_ARID = 0;
  dut.io_in_AR_ARADDR = 0x80004000U;
  dut.io_in_AR_ARLEN = 0;
  dut.io_in_AR_ARVALID = 1;
  tick(dut);
  dut.io_in_AR_ARVALID = 0;

  // A conforming slave may produce its first R beat in the same cycle as AR
  // handshakes.  The request slot is still in Request state before this edge.
  dut.io_SoCBus_AR_ARREADY = 1;
  dut.io_SoCBus_R_RID = 0;
  dut.io_SoCBus_R_RDATA = 0x55aa55aaU;
  dut.io_SoCBus_R_RRESP = 0;
  dut.io_SoCBus_R_RLAST = 1;
  dut.io_SoCBus_R_RVALID = 1;
  dut.eval();
  check(dut.io_SoCBus_AR_ARVALID && dut.io_SoCBus_R_RREADY,
        "xbar rejected a zero-latency first R beat");
  tick(dut);
  dut.io_SoCBus_AR_ARREADY = 0;
  dut.io_SoCBus_R_RVALID = 0;
  dut.io_SoCBus_R_RLAST = 0;
  dut.eval();
  check(dut.io_in_R_RVALID && dut.io_in_R_RID == 0 &&
            dut.io_in_R_RDATA == 0x55aa55aaU && dut.io_in_R_RLAST,
        "zero-latency first R beat was not buffered correctly");
  tick(dut);
}

static void simultaneous_targets_are_arbitrated(DUT &dut) {
  dut.io_SoCBus_AR_ARREADY = 1;
  dut.io_CLINT_AR_ARREADY = 1;

  dut.io_in_AR_ARID = 0;
  dut.io_in_AR_ARADDR = 0x80005000U;
  dut.io_in_AR_ARVALID = 1;
  tick(dut);
  dut.io_in_AR_ARID = 1;
  dut.io_in_AR_ARADDR = 0x0200bff8U;
  tick(dut);
  dut.io_in_AR_ARVALID = 0;
  dut.eval();
  check(dut.io_CLINT_AR_ARVALID && dut.io_CLINT_AR_ARID == 1,
        "CLINT AR did not issue independently of SoCBus AR");
  tick(dut);
  dut.io_SoCBus_AR_ARREADY = 0;
  dut.io_CLINT_AR_ARREADY = 0;

  dut.io_in_R_RREADY = 0;
  dut.io_SoCBus_R_RID = 0;
  dut.io_SoCBus_R_RDATA = 0xaaaa0000U;
  dut.io_SoCBus_R_RLAST = 1;
  dut.io_SoCBus_R_RVALID = 1;
  dut.io_CLINT_R_RID = 1;
  dut.io_CLINT_R_RDATA = 0xbbbb1111U;
  dut.io_CLINT_R_RLAST = 1;
  dut.io_CLINT_R_RVALID = 1;
  dut.eval();
  check(dut.io_SoCBus_R_RREADY && !dut.io_CLINT_R_RREADY,
        "simultaneous R sources were not arbitrated one at a time");
  tick(dut);
  dut.io_SoCBus_R_RVALID = 0;
  dut.io_SoCBus_R_RLAST = 0;

  for (int cycle = 0; cycle < 2; ++cycle) {
    dut.eval();
    check(dut.io_in_R_RVALID && dut.io_in_R_RID == 0 &&
              dut.io_in_R_RDATA == 0xaaaa0000U &&
              !dut.io_CLINT_R_RREADY,
          "selected SoCBus response was unstable under backpressure");
    tick(dut);
  }

  // Pop SoCBus and capture the waiting CLINT beat on the same edge.
  dut.io_in_R_RREADY = 1;
  dut.eval();
  check(dut.io_CLINT_R_RREADY,
        "waiting CLINT response did not receive arbitration after pop");
  tick(dut);
  dut.io_CLINT_R_RVALID = 0;
  dut.io_CLINT_R_RLAST = 0;
  dut.eval();
  check(dut.io_in_R_RVALID && dut.io_in_R_RID == 1 &&
            dut.io_in_R_RDATA == 0xbbbb1111U && dut.io_in_R_RLAST,
        "arbitrated CLINT response was corrupted");
  tick(dut);
  dut.io_in_AR_ARID = 0;
}

static void read_write_progress_concurrently(DUT &dut) {
  dut.io_SoCBus_AR_ARREADY = 0;
  dut.io_SoCBus_AW_AWREADY = 0;
  dut.io_SoCBus_W_WREADY = 0;

  dut.io_in_AR_ARID = 1;
  dut.io_in_AR_ARADDR = 0x80000800U;
  dut.io_in_AR_ARLEN = 0;
  dut.io_in_AR_ARVALID = 1;
  dut.io_in_AW_AWADDR = 0x80000400U;
  dut.io_in_AW_AWVALID = 1;
  dut.io_in_W_WDATA = 0xa5a55a5aU;
  dut.io_in_W_WLAST = 1;
  dut.io_in_W_WVALID = 1;
  dut.eval();
  check(dut.io_in_AR_ARREADY && dut.io_in_AW_AWREADY &&
            dut.io_in_W_WREADY,
        "xbar did not accept independent read and write requests together");
  tick(dut);
  dut.io_in_AR_ARVALID = 0;
  dut.io_in_AW_AWVALID = 0;
  dut.io_in_W_WVALID = 0;

  dut.eval();
  check(dut.io_SoCBus_AR_ARVALID && dut.io_SoCBus_AW_AWVALID &&
            dut.io_SoCBus_W_WVALID,
        "xbar globally serialized independent read and write channels");
  dut.io_SoCBus_AR_ARREADY = 1;
  dut.io_SoCBus_AW_AWREADY = 1;
  dut.io_SoCBus_W_WREADY = 1;
  tick(dut);
  dut.io_SoCBus_AR_ARREADY = 0;

  dut.io_SoCBus_R_RID = 1;
  dut.io_SoCBus_R_RDATA = 0xcafebabeU;
  dut.io_SoCBus_R_RLAST = 1;
  dut.io_SoCBus_R_RVALID = 1;
  dut.io_SoCBus_B_BRESP = 0;
  dut.io_SoCBus_B_BVALID = 1;
  dut.eval();
  check(dut.io_SoCBus_R_RREADY,
        "concurrent read response did not enter the R buffer");
  check(dut.io_in_B_BVALID && dut.io_in_B_BRESP == 0,
        "concurrent write response did not make progress");
  tick(dut);
  dut.io_SoCBus_R_RVALID = 0;
  dut.io_SoCBus_R_RLAST = 0;
  dut.io_SoCBus_B_BVALID = 0;
  dut.eval();
  check(dut.io_in_R_RVALID && dut.io_in_R_RID == 1 &&
            dut.io_in_R_RDATA == 0xcafebabeU,
        "buffered concurrent read response did not make progress");
  tick(dut);
  dut.io_in_AR_ARID = 0;
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
#ifdef TEST_CHIPLINK
  constexpr const char *kTestName = "AXI xbar PMA routing with ChipLink";
#else
  constexpr const char *kTestName =
      "AXI xbar PMA routing and finite DECERR bursts";
#endif
  return run_test(kTestName, [] {
    DUT dut;
    defaults(dut);
    reset(dut);
    external_burst(dut);
    external_read_burst(dut, 0x30000000U);
#ifdef TEST_CHIPLINK
    external_read_burst(dut, 0xc0000000U);
#endif
    clint_burst_decerr(dut);
    unmapped_read_burst_decerr(dut);
    crossing_read_decerr(dut);
    unmapped_write_decerr(dut);
    readonly_mrom_write_decerr(dut);
    ar_payload_stable_while_stalled(dut);
    two_inflight_reads_preserve_metadata(dut);
    zero_latency_read_response(dut);
    simultaneous_targets_are_arbitrated(dut);
    read_write_progress_concurrently(dut);
    dut.final();
  });
}
