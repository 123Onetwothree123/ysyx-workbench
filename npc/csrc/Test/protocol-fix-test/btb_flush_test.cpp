#include "Vysyx_26030103_BTB.h"
#include "test_common.hpp"
#include <verilated.h>

using DUT = Vysyx_26030103_BTB;

static void defaults(DUT &dut) {
  dut.io_lookup_pc = 0;
  dut.io_update_valid = 0;
  dut.io_update_pc = 0;
  dut.io_update_target = 0;
  dut.io_flush = 0;
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  return run_test("BTB fence.i invalidation", [] {
    DUT dut;
    defaults(dut);
    reset(dut);

    constexpr uint32_t pc_shifted = 0x80000100U >> 2;
    dut.io_update_valid = 1;
    dut.io_update_pc = pc_shifted;
    dut.io_update_target = 0x80000080U;
    tick(dut);
    dut.io_update_valid = 0;
    dut.io_lookup_pc = pc_shifted;
    dut.eval();
    check(dut.io_hit && dut.io_target == 0x80000080U,
          "BTB update was not visible");

    dut.io_flush = 1;
    tick(dut);
    dut.io_flush = 0;
    dut.eval();
    check(!dut.io_hit, "fence.i flush left a stale BTB entry valid");

    // Flush has priority over a same-cycle training event.
    dut.io_update_valid = 1;
    dut.io_flush = 1;
    tick(dut);
    dut.io_update_valid = 0;
    dut.io_flush = 0;
    dut.eval();
    check(!dut.io_hit, "same-cycle update escaped BTB flush priority");
    dut.final();
  });
}
