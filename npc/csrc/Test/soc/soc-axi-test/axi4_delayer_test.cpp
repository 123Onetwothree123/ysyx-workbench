#include "VAXI4DelayerChisel.h"
#include "test_common.hpp"

#include <array>
#include <cstdint>
#include <verilated.h>

using DUT = VAXI4DelayerChisel;

static void defaults(DUT &dut) {
  dut.io_in_awvalid = 0;
  dut.io_in_awid = 0;
  dut.io_in_awaddr = 0;
  dut.io_in_awlen = 0;
  dut.io_in_awsize = 2;
  dut.io_in_awburst = 1;
  dut.io_in_awlock = 0;
  dut.io_in_awcache = 0;
  dut.io_in_awprot = 0;
  dut.io_in_awqos = 0;
  dut.io_in_wvalid = 0;
  dut.io_in_wdata = 0;
  dut.io_in_wstrb = 0xf;
  dut.io_in_wlast = 0;
  dut.io_in_bready = 1;
  dut.io_in_arvalid = 0;
  dut.io_in_arid = 0;
  dut.io_in_araddr = 0;
  dut.io_in_arlen = 0;
  dut.io_in_arsize = 2;
  dut.io_in_arburst = 1;
  dut.io_in_arlock = 0;
  dut.io_in_arcache = 0;
  dut.io_in_arprot = 0;
  dut.io_in_arqos = 0;
  dut.io_in_rready = 1;

  dut.io_out_awready = 1;
  dut.io_out_wready = 1;
  dut.io_out_bvalid = 0;
  dut.io_out_bid = 0;
  dut.io_out_bresp = 0;
  dut.io_out_arready = 1;
  dut.io_out_rvalid = 0;
  dut.io_out_rid = 0;
  dut.io_out_rdata = 0;
  dut.io_out_rresp = 0;
  dut.io_out_rlast = 0;
}

static void read_address_is_not_replayed(DUT &dut) {
  dut.io_in_arvalid = 1;
  dut.io_in_arid = 1;
  dut.io_in_araddr = 0xa0000000U;
  dut.eval();
  check(dut.io_in_arready && dut.io_out_arvalid,
        "delayer did not forward the initial AR");
  tick(dut);

  dut.io_in_arid = 2;
  dut.io_in_araddr = 0xa0001000U;
  for (int cycle = 0; cycle < 6; ++cycle) {
    dut.eval();
    check(!dut.io_in_arready && !dut.io_out_arvalid,
          "busy delayer replayed a second AR downstream");
    tick(dut);
  }

  dut.io_out_rvalid = 1;
  dut.io_out_rid = 1;
  dut.io_out_rdata = 0x12345678U;
  dut.io_out_rlast = 1;
  dut.eval();
  check(dut.io_out_rready, "delayer did not accept downstream R");
  tick(dut);
  dut.io_out_rvalid = 0;
  dut.io_out_rlast = 0;

  wait_until(dut, [&] { return bool(dut.io_in_rvalid); },
             "delayer never released the delayed R beat");
  check(dut.io_in_rid == 1 && dut.io_in_rdata == 0x12345678U &&
            dut.io_in_rlast,
        "delayer corrupted delayed R payload");
  tick(dut);

  dut.eval();
  check(dut.io_in_arready && dut.io_out_arvalid &&
            dut.io_out_arid == 2 &&
            dut.io_out_araddr == 0xa0001000U,
        "delayer did not release the waiting AR after read completion");
  dut.io_in_arvalid = 0;
}

static void write_burst_and_address_gating(DUT &dut) {
  dut.io_in_awvalid = 1;
  dut.io_in_awid = 3;
  dut.io_in_awaddr = 0xa0002000U;
  dut.io_in_awlen = 2;
  dut.eval();
  check(dut.io_in_awready && dut.io_out_awvalid,
        "delayer did not forward the initial AW");
  tick(dut);

  dut.io_in_awid = 4;
  dut.io_in_awaddr = 0xa0003000U;
  const std::array<std::uint32_t, 3> data = {
      0x11111111U, 0x22222222U, 0x33333333U};

  for (std::size_t beat = 0; beat < data.size(); ++beat) {
    dut.io_in_wvalid = 1;
    dut.io_in_wdata = data[beat];
    dut.io_in_wstrb = 0xf;
    dut.io_in_wlast = beat + 1 == data.size();
    wait_until(dut, [&] {
      check(!dut.io_in_awready && !dut.io_out_awvalid,
            "busy delayer replayed a second AW downstream");
      return bool(dut.io_in_wready);
    }, "delayer never accepted a W burst beat");
    tick(dut);
    dut.io_in_wvalid = 0;

    // Hold the downstream before the delayed beat becomes eligible.  With
    // READY high it may legitimately assert VALID and handshake within the
    // same tick, leaving no level for this cycle-stepped test to observe.
    dut.io_out_wready = 0;
    wait_until(dut, [&] {
      check(!dut.io_in_awready && !dut.io_out_awvalid,
            "busy delayer leaked AW while delaying W");
      return bool(dut.io_out_wvalid);
    }, "delayer never forwarded a W burst beat");
    check(dut.io_out_wdata == data[beat] && dut.io_out_wstrb == 0xf,
          "delayer corrupted W burst data or strobe");
    check(bool(dut.io_out_wlast) == (beat + 1 == data.size()),
          "delayer corrupted WLAST in a burst");

    const auto held_data = dut.io_out_wdata;
    const auto held_last = dut.io_out_wlast;
    tick(dut);
    tick(dut);
    check(dut.io_out_wvalid && dut.io_out_wdata == held_data &&
              dut.io_out_wlast == held_last,
          "delayer changed W payload under downstream backpressure");
    dut.io_out_wready = 1;
    tick(dut);
  }

  dut.io_out_bvalid = 1;
  dut.io_out_bid = 3;
  dut.io_out_bresp = 2;
  wait_until(dut, [&] { return bool(dut.io_out_bready); },
             "delayer never accepted downstream B");
  tick(dut);
  dut.io_out_bvalid = 0;

  wait_until(dut, [&] {
    check(!dut.io_out_awvalid,
          "busy delayer leaked AW while delaying B");
    return bool(dut.io_in_bvalid);
  }, "delayer never released the delayed B response");
  check(dut.io_in_bid == 3 && dut.io_in_bresp == 2,
        "delayer corrupted delayed B payload");
  tick(dut);

  dut.eval();
  check(dut.io_in_awready && dut.io_out_awvalid &&
            dut.io_out_awid == 4 &&
            dut.io_out_awaddr == 0xa0003000U,
        "delayer did not release the waiting AW after B completion");
  dut.io_in_awvalid = 0;
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  return run_test("SoC AXI4 delayer gating and write bursts", [] {
    DUT dut;
    defaults(dut);
    reset(dut);
    read_address_is_not_replayed(dut);
    write_burst_and_address_gating(dut);
    dut.final();
  });
}
