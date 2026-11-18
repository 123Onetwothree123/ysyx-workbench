#include "Vysyx_26030103_PredictorSelect.h"
#include "test_common.hpp"
#include <verilated.h>

using DUT = Vysyx_26030103_PredictorSelect;

static void defaults(DUT &dut) {
  dut.io_fetchPC = 0x80000100U;
  dut.io_branchHit = 0;
  dut.io_branchTarget = 0;
  dut.io_jalHit = 0;
  dut.io_jalTarget = 0;
  dut.io_jalKind = 0;
  dut.io_rasNonempty = 0;
  dut.io_rasTop = 0;
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  return run_test("predictor Ret priority and JALR immediate", [] {
    DUT dut;
    defaults(dut);
    dut.eval();
    check(!dut.io_predHit, "empty predictor unexpectedly hit");

    // A stale Ret entry without a usable RAS must not steal the target from a
    // valid backward-branch prediction at the same PC.
    dut.io_jalHit = 1;
    dut.io_jalKind = 2; // ysyx_26030103_BTBKind.Ret
    dut.io_jalTarget = 12;
    dut.io_branchHit = 1;
    dut.io_branchTarget = 0x80000080U;
    dut.io_rasNonempty = 0;
    dut.eval();
    check(dut.io_predHit && dut.io_predTarget == 0x80000080U,
          "unusable Ret entry overrode a valid branch target");

    // Ret entries keep their static immediate in the JAL BTB.  Prediction must
    // add it to the dynamic RAS top and apply JALR bit-zero clearing.
    dut.io_rasNonempty = 1;
    dut.io_rasTop = 0x80001231U;
    dut.io_jalTarget = 6;
    dut.eval();
    check(dut.io_predHit && dut.io_predTarget == 0x80001236U,
          "Ret prediction ignored its non-zero JALR immediate");

    // Static JAL/Call entries retain the absolute target stored in the table.
    dut.io_jalKind = 1;
    dut.io_jalTarget = 0x80002000U;
    dut.eval();
    check(dut.io_predHit && dut.io_predTarget == 0x80002000U,
          "static JAL target selection regressed");
    dut.final();
  });
}
