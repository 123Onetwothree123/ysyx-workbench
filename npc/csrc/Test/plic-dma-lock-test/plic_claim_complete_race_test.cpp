// PLIC leaf regression:
//   1. complete(source A) and claim(source B) in the same cycle must both take
//      effect.  The old separate `claimed := ...` assignments let the later
//      claim update overwrite the complete clear (Chisel last-connect).
//   2. The advertised UART source 10 and the S-context enable/threshold/claim
//      window (0x2080/0x201000/0x201004) must remain usable.
#include "Vysyx_26030103_PLIC.h"
#include "test_common.hpp"
#include <verilated.h>

using DUT = Vysyx_26030103_PLIC;

static constexpr uint32_t BASE = 0x0c000000U;
static constexpr uint32_t CLAIM = BASE + 0x201004U;
static constexpr uint32_t THRESHOLD_S = BASE + 0x201000U;
static constexpr uint32_t ENABLE_S = BASE + 0x002080U;
static constexpr uint32_t PENDING = BASE + 0x001000U;

static void defaults(DUT &dut) {
  dut.sources = 0;
  dut.io_AW_AWVALID = 0;
  dut.io_AW_AWADDR = 0;
  dut.io_AW_AWID = 0;
  dut.io_AW_AWLEN = 0;
  dut.io_AW_AWSIZE = 2;
  dut.io_AW_AWBURST = 1;
  dut.io_AW_AWPROT = 0;
  dut.io_AW_AWCACHE = 0;
  dut.io_AW_AWQOS = 0;
  dut.io_W_WVALID = 0;
  dut.io_W_WDATA = 0;
  dut.io_W_WSTRB = 0;
  dut.io_W_WLAST = 0;
  dut.io_B_BREADY = 0;
  dut.io_AR_ARVALID = 0;
  dut.io_AR_ARADDR = 0;
  dut.io_AR_ARID = 0;
  dut.io_AR_ARLEN = 0;
  dut.io_AR_ARSIZE = 2;
  dut.io_AR_ARBURST = 1;
  dut.io_AR_ARPROT = 0;
  dut.io_AR_ARCACHE = 0;
  dut.io_AR_ARQOS = 0;
  dut.io_R_RREADY = 0;
}

static void begin_write(DUT &dut, uint32_t address, uint32_t data) {
  dut.io_AW_AWVALID = 1;
  dut.io_AW_AWADDR = address;
  dut.io_AW_AWID = 0;
  dut.io_W_WVALID = 1;
  dut.io_W_WDATA = data;
  dut.io_W_WSTRB = 0xf;
  dut.io_W_WLAST = 1;
  dut.eval();
  check(dut.io_AW_AWREADY && dut.io_W_WREADY,
        "PLIC did not accept a single-beat write");
  tick(dut);
  dut.io_AW_AWVALID = 0;
  dut.io_W_WVALID = 0;
  dut.eval();
  check(!dut.io_B_BVALID, "PLIC completed the write too early");
}

static void finish_write(DUT &dut) {
  wait_until(dut, [&] { return dut.io_B_BVALID; },
             "PLIC never returned a write response");
  dut.io_B_BREADY = 1;
  tick(dut);
  dut.io_B_BREADY = 0;
}

static void write_reg(DUT &dut, uint32_t address, uint32_t data) {
  begin_write(dut, address, data);
  finish_write(dut);
}

static void begin_read(DUT &dut, uint32_t address) {
  dut.io_AR_ARVALID = 1;
  dut.io_AR_ARADDR = address;
  dut.io_AR_ARID = 0;
  dut.io_AR_ARLEN = 0;
  dut.io_AR_ARSIZE = 2;
  dut.io_AR_ARBURST = 1;
  dut.io_AR_ARPROT = 0;
  dut.io_AR_ARCACHE = 0;
  dut.io_AR_ARQOS = 0;
  dut.eval();
  check(dut.io_AR_ARREADY, "PLIC did not accept a single-beat read");
  tick(dut);
  dut.io_AR_ARVALID = 0;
}

static uint32_t finish_read(DUT &dut) {
  wait_until(dut, [&] { return dut.io_R_RVALID; },
             "PLIC never returned read data");
  const uint32_t data = dut.io_R_RDATA;
  check(dut.io_R_RLAST, "PLIC single-beat read did not assert RLAST");
  dut.io_R_RREADY = 1;
  tick(dut);
  dut.io_R_RREADY = 0;
  return data;
}

static uint32_t read_reg(DUT &dut, uint32_t address) {
  begin_read(dut, address);
  return finish_read(dut);
}

static void configure_two_sources(DUT &dut) {
  write_reg(dut, BASE + 4U * 1, 7); // source 1, high priority
  write_reg(dut, BASE + 4U * 2, 3); // source 2
  write_reg(dut, ENABLE_S, (1U << 1) | (1U << 2));
  write_reg(dut, THRESHOLD_S, 0);
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  int status = 0;

  status |= run_test("PLIC claim/complete same-cycle race", [] {
    DUT dut;
    defaults(dut);
    reset(dut);
    configure_two_sources(dut);

    // Claim source 1 first, so it is in the claimed state before the race.
    dut.sources = 1U << 1;
    tick(dut);
    tick(dut);
    check(read_reg(dut, CLAIM) == 1, "initial claim did not return source 1");

    // Source 2 becomes pending while source 1 remains claimed.
    dut.sources = (1U << 1) | (1U << 2);
    tick(dut);
    tick(dut);

    // Cycle N: register the complete write for source 1.
    begin_write(dut, CLAIM, 1);
    // Cycle N+1: the complete takes effect while the claim read of source 2
    // is accepted. Both updates must survive.
    begin_read(dut, CLAIM);
    dut.eval();
    check(dut.io_B_BVALID, "complete response missing in race cycle");
    check(dut.io_R_RVALID, "claim response missing in race cycle");
    check(dut.io_R_RDATA == 2,
          "simultaneous claim selected the wrong source");
    dut.io_R_RREADY = 1;
    tick(dut);
    dut.io_R_RREADY = 0;
    dut.io_B_BREADY = 1;
    tick(dut);
    dut.io_B_BREADY = 0;

    // complete(source 1) must have cleared claimed bit 1 even though the
    // same cycle set claimed bit 2.  With source 1 still asserted, it must
    // become pending again and be claimable.
    dut.sources = 1U << 1;
    tick(dut);
    tick(dut);
    check(read_reg(dut, CLAIM) == 1,
          "complete(source 1) was lost when source 2 was claimed");
    check((read_reg(dut, PENDING) & (1U << 1)) == 0,
          "claim did not clear pending source 1 after the race");
    dut.final();
  });

  status |= run_test("PLIC UART source 10 S-context window", [] {
    DUT dut;
    defaults(dut);
    reset(dut);

    write_reg(dut, BASE + 4U * 10, 1); // source 10 priority
    write_reg(dut, ENABLE_S, 1U << 10); // S-context enable bit 10
    write_reg(dut, THRESHOLD_S, 0);     // S-context threshold

    dut.sources = 1U << 10;
    tick(dut);
    tick(dut);
    check(dut.external_irq, "source 10 did not raise external_irq");

    // Model software clearing the UART level before claiming.
    dut.sources = 0;
    tick(dut);
    check(read_reg(dut, CLAIM) == 10,
          "S-context claim did not return UART source 10");
    check((read_reg(dut, PENDING) & (1U << 10)) == 0,
          "claim did not clear UART source 10 pending bit");
    write_reg(dut, CLAIM, 10); // complete source 10
    dut.final();
  });

  return status;
}
