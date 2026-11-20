#include "Vysyx_26030103_BTB.h"
#include "ports_btb.hpp"
#include "test_common.hpp"
#include <verilated.h>

using namespace ports::BTB;

using DUT = Vysyx_26030103_BTB;

static void defaults(DUT &dut) {
  io_LookupPC(dut) = 0;
  io_UpdateValid(dut) = 0;
  io_UpdatePC(dut) = 0;
  io_UpdateTarget(dut) = 0;
  io_flush(dut) = 0;
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  return run_test("BTB fence.i invalidation", [] {
    DUT dut;
    defaults(dut);
    reset(dut);

    constexpr uint32_t pc_shifted = 0x80000100U >> 2;
    io_UpdateValid(dut) = 1;
    io_UpdatePC(dut) = pc_shifted;
    io_UpdateTarget(dut) = 0x80000080U;
    tick(dut);
    io_UpdateValid(dut) = 0;
    io_LookupPC(dut) = pc_shifted;
    dut.eval();
    check(io_hit(dut) && io_target(dut) == 0x80000080U,
          "BTB update was not visible");

    io_flush(dut) = 1;
    tick(dut);
    io_flush(dut) = 0;
    dut.eval();
    check(!io_hit(dut), "fence.i flush left a stale BTB entry valid");

    // Flush has priority over a same-cycle training event.
    io_UpdateValid(dut) = 1;
    io_flush(dut) = 1;
    tick(dut);
    io_UpdateValid(dut) = 0;
    io_flush(dut) = 0;
    dut.eval();
    check(!io_hit(dut), "same-cycle update escaped BTB flush priority");
    dut.final();
  });
}
