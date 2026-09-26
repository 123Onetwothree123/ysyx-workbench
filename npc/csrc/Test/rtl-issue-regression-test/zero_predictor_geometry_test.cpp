#include "VZeroPredictorGeometryHarness.h"
#include "test_common.hpp"

#include <cstdint>
#include <verilated.h>

using DUT = VZeroPredictorGeometryHarness;

static void defaults(DUT &dut) {
  dut.io_btbLookupPC = 0;
  dut.io_btbUpdateValid = 0;
  dut.io_btbUpdatePC = 0;
  dut.io_btbUpdateTarget = 0;
  dut.io_btbFlush = 0;
  dut.io_jalBtbLookupPC = 0;
  dut.io_jalBtbUpdateValid = 0;
  dut.io_jalBtbUpdatePC = 0;
  dut.io_jalBtbUpdateTarget = 0;
  dut.io_jalBtbUpdateKind = 0;
  dut.io_jalBtbFlush = 0;
  dut.io_rasPushValid = 0;
  dut.io_rasPushAddr = 0;
  dut.io_rasPopValid = 0;
  dut.io_rasFlush = 0;
}

static void one_set_jal_btb_preserves_kind_and_replaces_by_tag() {
  DUT dut;
  defaults(dut);
  reset(dut);

  constexpr std::uint32_t first_pc = 0x80000300U >> 2;
  constexpr std::uint32_t second_pc = 0x80000400U >> 2;
  dut.io_jalBtbUpdateValid = 1;
  dut.io_jalBtbUpdatePC = first_pc;
  dut.io_jalBtbUpdateTarget = 0x80003000U;
  dut.io_jalBtbUpdateKind = 2;
  tick(dut);
  dut.io_jalBtbUpdateValid = 0;
  dut.io_jalBtbLookupPC = first_pc;
  dut.eval();
  check(dut.io_jalBtbHit && dut.io_jalBtbTarget == 0x80003000U &&
            dut.io_jalBtbKind == 2,
        "one-set JAL BTB did not retain target and kind");

  dut.io_jalBtbUpdateValid = 1;
  dut.io_jalBtbUpdatePC = second_pc;
  dut.io_jalBtbUpdateTarget = 0x80004000U;
  dut.io_jalBtbUpdateKind = 3;
  tick(dut);
  dut.io_jalBtbUpdateValid = 0;
  dut.io_jalBtbLookupPC = second_pc;
  dut.eval();
  check(dut.io_jalBtbHit && dut.io_jalBtbTarget == 0x80004000U &&
            dut.io_jalBtbKind == 3,
        "one-set JAL BTB did not replace target and kind");
  dut.io_jalBtbLookupPC = first_pc;
  dut.eval();
  check(!dut.io_jalBtbHit,
        "one-set JAL BTB reported the replaced tag as a hit");
  dut.final();
}

static void one_set_btb_replaces_by_tag() {
  DUT dut;
  defaults(dut);
  reset(dut);

  constexpr std::uint32_t first_pc = 0x80000100U >> 2;
  constexpr std::uint32_t second_pc = 0x80000200U >> 2;
  dut.io_btbUpdateValid = 1;
  dut.io_btbUpdatePC = first_pc;
  dut.io_btbUpdateTarget = 0x80001000U;
  tick(dut);
  dut.io_btbUpdateValid = 0;
  dut.io_btbLookupPC = first_pc;
  dut.eval();
  check(dut.io_btbHit && dut.io_btbTarget == 0x80001000U,
        "one-set BTB did not retain its first update");

  dut.io_btbUpdateValid = 1;
  dut.io_btbUpdatePC = second_pc;
  dut.io_btbUpdateTarget = 0x80002000U;
  tick(dut);
  dut.io_btbUpdateValid = 0;
  dut.io_btbLookupPC = second_pc;
  dut.eval();
  check(dut.io_btbHit && dut.io_btbTarget == 0x80002000U,
        "one-set BTB did not replace its sole entry");
  dut.io_btbLookupPC = first_pc;
  dut.eval();
  check(!dut.io_btbHit,
        "one-set BTB reported the replaced tag as a hit");
  dut.final();
}

static void one_entry_ras_push_pop_and_overwrite() {
  DUT dut;
  defaults(dut);
  reset(dut);

  dut.io_rasPushValid = 1;
  dut.io_rasPushAddr = 0x80000104U;
  tick(dut);
  dut.io_rasPushAddr = 0x80000204U;
  tick(dut);
  dut.io_rasPushValid = 0;
  dut.eval();
  check(dut.io_rasNonempty && dut.io_rasTop == 0x80000204U,
        "one-entry RAS did not overwrite its sole entry when full");

  dut.io_rasPopValid = 1;
  tick(dut);
  dut.io_rasPopValid = 0;
  dut.eval();
  check(!dut.io_rasNonempty, "one-entry RAS did not become empty after pop");

  dut.io_rasPushValid = 1;
  dut.io_rasPopValid = 1;
  dut.io_rasPushAddr = 0x80000304U;
  tick(dut);
  dut.io_rasPushValid = 0;
  dut.io_rasPopValid = 0;
  dut.eval();
  check(dut.io_rasNonempty && dut.io_rasTop == 0x80000304U,
        "empty one-entry RAS mishandled simultaneous pop+push");
  dut.final();
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  int failures = 0;
  failures += run_test("BTBBits=0 implements a one-set BTB",
                       one_set_btb_replaces_by_tag);
  failures += run_test("JalBTBBits=0 implements a one-set kind-carrying BTB",
                       one_set_jal_btb_preserves_kind_and_replaces_by_tag);
  failures += run_test("RASBits=0 implements a one-entry RAS",
                       one_entry_ras_push_pop_and_overwrite);
  return failures == 0 ? 0 : 1;
}
