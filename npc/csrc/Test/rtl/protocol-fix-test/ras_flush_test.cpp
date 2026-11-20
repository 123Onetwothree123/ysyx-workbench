#include "Vysyx_26030103_RAS.h"
#include "ports_ras.hpp"
#include "test_common.hpp"
#include <verilated.h>

using namespace ports::RAS;

using DUT = Vysyx_26030103_RAS;

static void defaults(DUT &dut) {
  io_PushValid(dut) = 0;
  io_PushAddress(dut) = 0;
  io_PopValid(dut) = 0;
  io_flush(dut) = 0;
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  return run_test("RAS fence.i invalidation", [] {
    DUT dut;
    defaults(dut);
    TestDriver<DUT> drv(dut);
    drv.Reset();

    io_PushValid(dut) = 1;
    io_PushAddress(dut) = 0x80000120U;
    drv.Tick();
    io_PushValid(dut) = 0;
    dut.eval();
    drv.Expect(io_nonempty(dut) && io_top(dut) == 0x80000120U,
               "RAS push was not visible");

    io_flush(dut) = 1;
    drv.Tick();
    io_flush(dut) = 0;
    dut.eval();
    drv.Expect(!io_nonempty(dut), "fence.i flush left stale RAS state");

    io_PushValid(dut) = 1;
    io_PushAddress(dut) = 0x80000220U;
    io_flush(dut) = 1;
    drv.Tick();
    io_PushValid(dut) = 0;
    io_flush(dut) = 0;
    dut.eval();
    drv.Expect(!io_nonempty(dut), "same-cycle push escaped RAS flush priority");
    dut.final();
  });
}
