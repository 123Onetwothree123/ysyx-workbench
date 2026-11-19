#include "VZeroPredictorGeometryHarness.h"
#include "test_common.hpp"

#include <cstdint>
#include <verilated.h>

using DUT = VZeroPredictorGeometryHarness;

static void defaults(DUT &dut) {
  dut.io_BTBLookupPC = 0;
  dut.io_BTBUpdateValid = 0;
  dut.io_BTBUpdatePC = 0;
  dut.io_BTBUpdateTarget = 0;
  dut.io_BTBFlush = 0;
  dut.io_JalBTBLookupPC = 0;
  dut.io_JalBTBUpdateValid = 0;
  dut.io_JalBTBUpdatePC = 0;
  dut.io_JalBTBUpdateTarget = 0;
  dut.io_JalBTBUpdateKind = 0;
  dut.io_JalBTBFlush = 0;
  dut.io_RASPushValid = 0;
  dut.io_RASPushAddress = 0;
  dut.io_RASPopValid = 0;
  dut.io_RASFlush = 0;
}

static void one_set_jal_btb_preserves_kind_and_replaces_by_tag() {
  DUT dut;
  defaults(dut);
  reset(dut);

  constexpr std::uint32_t first_pc = 0x80000300U >> 2;
  constexpr std::uint32_t second_pc = 0x80000400U >> 2;
  dut.io_JalBTBUpdateValid = 1;
  dut.io_JalBTBUpdatePC = first_pc;
  dut.io_JalBTBUpdateTarget = 0x80003000U;
  dut.io_JalBTBUpdateKind = 2;
  tick(dut);
  dut.io_JalBTBUpdateValid = 0;
  dut.io_JalBTBLookupPC = first_pc;
  dut.eval();
  check(dut.io_JalBTBHit && dut.io_JalBTBTarget == 0x80003000U &&
            dut.io_JalBTBKind == 2,
        "one-set JAL BTB did not retain target and kind");

  dut.io_JalBTBUpdateValid = 1;
  dut.io_JalBTBUpdatePC = second_pc;
  dut.io_JalBTBUpdateTarget = 0x80004000U;
  dut.io_JalBTBUpdateKind = 3;
  tick(dut);
  dut.io_JalBTBUpdateValid = 0;
  dut.io_JalBTBLookupPC = second_pc;
  dut.eval();
  check(dut.io_JalBTBHit && dut.io_JalBTBTarget == 0x80004000U &&
            dut.io_JalBTBKind == 3,
        "one-set JAL BTB did not replace target and kind");
  dut.io_JalBTBLookupPC = first_pc;
  dut.eval();
  check(!dut.io_JalBTBHit,
        "one-set JAL BTB reported the replaced tag as a hit");
  dut.final();
}

static void one_set_btb_replaces_by_tag() {
  DUT dut;
  defaults(dut);
  reset(dut);

  constexpr std::uint32_t first_pc = 0x80000100U >> 2;
  constexpr std::uint32_t second_pc = 0x80000200U >> 2;
  dut.io_BTBUpdateValid = 1;
  dut.io_BTBUpdatePC = first_pc;
  dut.io_BTBUpdateTarget = 0x80001000U;
  tick(dut);
  dut.io_BTBUpdateValid = 0;
  dut.io_BTBLookupPC = first_pc;
  dut.eval();
  check(dut.io_BTBHit && dut.io_BTBTarget == 0x80001000U,
        "one-set BTB did not retain its first update");

  dut.io_BTBUpdateValid = 1;
  dut.io_BTBUpdatePC = second_pc;
  dut.io_BTBUpdateTarget = 0x80002000U;
  tick(dut);
  dut.io_BTBUpdateValid = 0;
  dut.io_BTBLookupPC = second_pc;
  dut.eval();
  check(dut.io_BTBHit && dut.io_BTBTarget == 0x80002000U,
        "one-set BTB did not replace its sole entry");
  dut.io_BTBLookupPC = first_pc;
  dut.eval();
  check(!dut.io_BTBHit,
        "one-set BTB reported the replaced tag as a hit");
  dut.final();
}

static void one_entry_ras_push_pop_and_overwrite() {
  DUT dut;
  defaults(dut);
  reset(dut);

  dut.io_RASPushValid = 1;
  dut.io_RASPushAddress = 0x80000104U;
  tick(dut);
  dut.io_RASPushAddress = 0x80000204U;
  tick(dut);
  dut.io_RASPushValid = 0;
  dut.eval();
  check(dut.io_RASNonempty && dut.io_RASTop == 0x80000204U,
        "one-entry RAS did not overwrite its sole entry when full");

  dut.io_RASPopValid = 1;
  tick(dut);
  dut.io_RASPopValid = 0;
  dut.eval();
  check(!dut.io_RASNonempty, "one-entry RAS did not become empty after pop");

  dut.io_RASPushValid = 1;
  dut.io_RASPopValid = 1;
  dut.io_RASPushAddress = 0x80000304U;
  tick(dut);
  dut.io_RASPushValid = 0;
  dut.io_RASPopValid = 0;
  dut.eval();
  check(dut.io_RASNonempty && dut.io_RASTop == 0x80000304U,
        "empty one-entry RAS mishandled simultaneous pop+push");
  dut.final();
}

static void context_flush_clears_all_predictor_state() {
  DUT dut;
  defaults(dut);
  reset(dut);

  constexpr std::uint32_t pc = 0x80000500U >> 2;
  dut.io_BTBUpdateValid = 1;
  dut.io_BTBUpdatePC = pc;
  dut.io_BTBUpdateTarget = 0x80000400U;
  dut.io_JalBTBUpdateValid = 1;
  dut.io_JalBTBUpdatePC = pc;
  dut.io_JalBTBUpdateTarget = 0x80000600U;
  dut.io_JalBTBUpdateKind = 1;
  dut.io_RASPushValid = 1;
  dut.io_RASPushAddress = 0x80000504U;
  tick(dut);
  dut.io_BTBUpdateValid = 0;
  dut.io_JalBTBUpdateValid = 0;
  dut.io_RASPushValid = 0;
  dut.io_BTBLookupPC = pc;
  dut.io_JalBTBLookupPC = pc;
  dut.eval();
  check(dut.io_BTBHit && dut.io_JalBTBHit && dut.io_RASNonempty,
        "predictor state was not populated before the context flush");

  // 顶层在 satp/sfence.vma/trap/mret/sret 时同时拉高这三路 flush。
  dut.io_BTBFlush = 1;
  dut.io_JalBTBFlush = 1;
  dut.io_RASFlush = 1;
  tick(dut);
  dut.io_BTBFlush = 0;
  dut.io_JalBTBFlush = 0;
  dut.io_RASFlush = 0;
  dut.io_BTBLookupPC = pc;
  dut.io_JalBTBLookupPC = pc;
  dut.eval();
  check(!dut.io_BTBHit,
        "branch BTB entry survived a context flush and could hit a non-control PC");
  check(!dut.io_JalBTBHit, "JAL BTB entry survived a context flush");
  check(!dut.io_RASNonempty, "RAS return address survived a context flush");
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
  failures += run_test("context flush clears BTB/JAL-BTB/RAS",
                       context_flush_clears_all_predictor_state);
  return failures == 0 ? 0 : 1;
}
