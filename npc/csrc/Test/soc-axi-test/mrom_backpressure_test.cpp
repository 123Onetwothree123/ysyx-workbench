#include "VAXI4MROM.h"
#include "test_common.hpp"

#include <cstdint>
#include <verilated.h>

extern "C" void mrom_read(std::int32_t address, std::int32_t *data) {
  *data = static_cast<std::int32_t>(
      0xa5000000U ^ static_cast<std::uint32_t>(address));
}

using DUT = VAXI4MROM;

static std::uint32_t expected(std::uint32_t address) {
  return 0xa5000000U ^ address;
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  return run_test("SoC MROM holds R payload under backpressure", [] {
    DUT dut;
    dut.auto_in_arvalid = 0;
    dut.auto_in_arid = 0;
    dut.auto_in_araddr = 0;
    dut.auto_in_arlen = 0;
    dut.auto_in_rready = 0;
    reset(dut);

    constexpr std::uint32_t base = 0x30000000U;
    dut.auto_in_arvalid = 1;
    dut.auto_in_arid = 5;
    dut.auto_in_araddr = base;
    dut.auto_in_arlen = 2;
    dut.eval();
    check(dut.auto_in_arready, "MROM did not accept AR");
    tick(dut);
    dut.auto_in_arvalid = 0;

    check(!dut.auto_in_rvalid,
          "MROM asserted RVALID before its synchronous read completed");
    tick(dut);
    dut.eval();
    check(dut.auto_in_rvalid && dut.auto_in_rid == 5,
          "MROM did not present the first read beat");
    check(dut.auto_in_rdata == expected(base) && !dut.auto_in_rlast,
          "MROM returned incorrect first-beat payload");

    const auto held_data = dut.auto_in_rdata;
    for (int cycle = 0; cycle < 6; ++cycle) {
      tick(dut);
      check(dut.auto_in_rvalid && dut.auto_in_rid == 5 &&
                dut.auto_in_rdata == held_data && !dut.auto_in_rlast,
            "MROM changed R payload while RREADY was low");
    }

    dut.auto_in_rready = 1;
    tick(dut);
    check(!dut.auto_in_rvalid,
          "MROM did not insert its synchronous inter-beat load cycle");
    tick(dut);
    check(dut.auto_in_rvalid &&
              dut.auto_in_rdata == expected(base + 4U) &&
              !dut.auto_in_rlast,
          "MROM returned incorrect second read beat");
    tick(dut);
    check(!dut.auto_in_rvalid,
          "MROM did not load the final beat synchronously");
    tick(dut);
    check(dut.auto_in_rvalid &&
              dut.auto_in_rdata == expected(base + 8U) &&
              dut.auto_in_rlast,
          "MROM returned incorrect final read beat");
    tick(dut);
    check(!dut.auto_in_rvalid && dut.auto_in_arready,
          "MROM did not return idle after RLAST handshake");
    dut.final();
  });
}
