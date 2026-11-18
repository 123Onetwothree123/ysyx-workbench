#include "Vysyx_26030103_RAS.h"
#include "test_common.hpp"
#include <verilated.h>

using DUT = Vysyx_26030103_RAS;

static void defaults(DUT &dut) {
  dut.io_push_valid = 0;
  dut.io_push_addr = 0;
  dut.io_pop_valid = 0;
  dut.io_flush = 0;
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  return run_test("RAS fence.i invalidation", [] {
    DUT dut;
    defaults(dut);
    reset(dut);

    dut.io_push_valid = 1;
    dut.io_push_addr = 0x80000120U;
    tick(dut);
    dut.io_push_valid = 0;
    dut.eval();
    check(dut.io_nonempty && dut.io_top == 0x80000120U,
          "RAS push was not visible");

    dut.io_flush = 1;
    tick(dut);
    dut.io_flush = 0;
    dut.eval();
    check(!dut.io_nonempty, "fence.i flush left stale RAS state");

    dut.io_push_valid = 1;
    dut.io_push_addr = 0x80000220U;
    dut.io_flush = 1;
    tick(dut);
    dut.io_push_valid = 0;
    dut.io_flush = 0;
    dut.eval();
    check(!dut.io_nonempty, "same-cycle push escaped RAS flush priority");
    dut.final();
  });
}
