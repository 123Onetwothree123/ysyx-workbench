#include "VStageConnectHarness.h"
#include "test_common.hpp"

#include <verilated.h>

using DUT = VStageConnectHarness;

static void normal_stall_holds_current_item_control() {
  DUT dut;
  dut.io_in_valid = 0;
  dut.io_in_bits = 0;
  dut.io_out_ready = 0;
  dut.io_flush = 0;
  dut.io_flushCurrent = 0;
  reset(dut);

  dut.io_in_valid = 1;
  dut.io_in_bits = 0xa5a55a5aU;
  dut.eval();
  check(dut.io_in_ready, "empty StageConnect did not accept control input");
  tick(dut);
  dut.io_in_valid = 0;
  for (int cycle = 0; cycle < 3; ++cycle) {
    dut.eval();
    check(dut.io_out_valid && dut.io_out_bits == 0xa5a55a5aU,
          "StageConnect changed a normally stalled item");
    tick(dut);
  }
  dut.io_out_ready = 1;
  tick(dut);
  dut.eval();
  check(!dut.io_out_valid, "control item was not consumed after ready");
  dut.final();
}

static void flush_current_false_must_not_drop_stalled_output() {
  DUT dut;
  dut.io_in_valid = 0;
  dut.io_in_bits = 0;
  dut.io_out_ready = 0;
  dut.io_flush = 0;
  dut.io_flushCurrent = 0;
  reset(dut);

  // Fill the one-entry elastic register while keeping its consumer stalled.
  dut.io_in_valid = 1;
  dut.io_in_bits = 0x12345678U;
  dut.eval();
  check(dut.io_in_ready, "empty StageConnect did not accept input");
  tick(dut);
  dut.io_in_valid = 0;
  dut.eval();
  check(dut.io_out_valid && dut.io_out_bits == 0x12345678U,
        "StageConnect did not hold the accepted item");

  // flushCurrent=false promises that the current Right-side item is not the
  // younger item being flushed.  Since ready is low, it cannot fire on this
  // cycle and therefore must still be present after the edge.
  dut.io_flush = 1;
  dut.io_flushCurrent = 0;
  dut.eval();
  check(dut.io_out_valid,
        "flushCurrent=false hid the current item in the flush cycle");
  tick(dut);
  dut.io_flush = 0;
  dut.eval();
  check(dut.io_out_valid && dut.io_out_bits == 0x12345678U,
        "flushCurrent=false dropped a current item which never fired");

  dut.io_out_ready = 1;
  tick(dut);
  dut.eval();
  check(!dut.io_out_valid,
        "StageConnect did not consume the preserved item after ready");
  dut.final();
}

static void preserved_current_fires_but_younger_input_is_flushed() {
  DUT dut;
  dut.io_in_valid = 0;
  dut.io_in_bits = 0;
  dut.io_out_ready = 0;
  dut.io_flush = 0;
  dut.io_flushCurrent = 0;
  reset(dut);

  dut.io_in_valid = 1;
  dut.io_in_bits = 0x11111111U;
  tick(dut);

  dut.io_out_ready = 1;
  dut.io_in_bits = 0x22222222U;
  dut.io_flush = 1;
  dut.eval();
  check(dut.io_out_valid && dut.io_out_bits == 0x11111111U && dut.io_in_ready,
        "preserved current item did not fire on the flush cycle");
  tick(dut);

  dut.io_in_valid = 0;
  dut.io_flush = 0;
  dut.eval();
  check(!dut.io_out_valid,
        "younger Left item was incorrectly loaded during a flush");
  dut.final();
}

static void kill_current_hides_and_clears_stalled_output() {
  DUT dut;
  dut.io_in_valid = 0;
  dut.io_in_bits = 0;
  dut.io_out_ready = 0;
  dut.io_flush = 0;
  dut.io_flushCurrent = 0;
  reset(dut);

  dut.io_in_valid = 1;
  dut.io_in_bits = 0x33333333U;
  tick(dut);
  dut.io_in_valid = 0;
  dut.io_flush = 1;
  dut.io_flushCurrent = 1;
  dut.eval();
  check(!dut.io_out_valid,
        "flushCurrent did not hide the killed current item immediately");
  tick(dut);

  dut.io_flush = 0;
  dut.io_flushCurrent = 0;
  dut.eval();
  check(!dut.io_out_valid,
        "flushCurrent did not clear the killed current item at the edge");
  dut.final();
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  int failures = 0;
  failures += run_test("StageConnect normal stall positive control",
                       normal_stall_holds_current_item_control);
  failures += run_test("StageConnect preserves a stalled current item on flush",
                       flush_current_false_must_not_drop_stalled_output);
  failures += run_test("StageConnect flushes younger input after current fire",
                       preserved_current_fires_but_younger_input_is_flushed);
  failures += run_test("StageConnect kill-current hides stalled output",
                       kill_current_hides_and_clears_stalled_output);
  return failures == 0 ? 0 : 1;
}
