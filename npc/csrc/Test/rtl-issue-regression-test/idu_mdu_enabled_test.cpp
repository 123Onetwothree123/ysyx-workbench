#include "Vysyx_26030103_IDU.h"
#include "test_common.hpp"

#include <cstdint>
#include <string>
#include <verilated.h>

using DUT = Vysyx_26030103_IDU;

static void defaults(DUT &dut) {
  dut.io_in_valid = 1;
  dut.io_in_bits_Instruction = 0;
  dut.io_in_bits_pc = 0x80000000U;
  dut.io_in_bits_ExceptionValid = 0;
  dut.io_in_bits_pred_taken = 0;
  dut.io_in_bits_pred_target = 0;
  dut.io_out_ready = 1;
  dut.io_ReadDATA1 = 0;
  dut.io_ReadDATA2 = 0;
  dut.io_ex_valid = 0;
  dut.io_ex_rd = 0;
  dut.io_ex_regWrite = 0;
  dut.io_wb_valid = 0;
  dut.io_wb_rd = 0;
  dut.io_wb_regWrite = 0;
  dut.io_ex_memop = 0;
  dut.io_ex_fwd_ready = 0;
  dut.io_ex_fwd_data = 0;
  dut.io_ex_mdu_hidden_writes = 0;
  dut.io_wb_fwd_data = 0;
  dut.io_me_valid = 0;
  dut.io_me_rd = 0;
  dut.io_me_regWrite = 0;
  dut.io_me_memop = 0;
  dut.io_me_fwd_ready = 0;
  dut.io_me_fwd_data = 0;
  dut.io_me2_valid = 0;
  dut.io_me2_rd = 0;
  dut.io_me2_regWrite = 0;
  dut.io_me2_memop = 0;
  dut.io_me2_fwd_ready = 0;
  dut.io_me2_fwd_data = 0;
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  return run_test("RISCV_M enabled IDU decode and hidden-writer hazard", [] {
    DUT dut;
    defaults(dut);

    // funct3 directly selects MUL/MULH/MULHSU/MULHU/DIV/DIVU/REM/REMU in
    // the production MDU operation encoding.
    for (std::uint8_t funct3 = 0; funct3 < 8; ++funct3) {
      dut.io_in_bits_Instruction =
          0x02000033U | (static_cast<std::uint32_t>(funct3) << 12) |
          (5U << 20) | (4U << 15) | (3U << 7);
      dut.eval();
      check(dut.io_in_ready && dut.io_out_valid,
            "enabled M instruction was unexpectedly stalled");
      check(dut.io_out_bits_IsMDU,
            "RISCV_M=y failed to decode an M instruction");
      check(!dut.io_out_bits_ExceptionValid,
            "RISCV_M=y marked an M instruction illegal");
      check(dut.io_out_bits_MDUOp == funct3,
            "M instruction selected the wrong MDU operation");
      check(dut.io_out_bits_RegisterWrite && dut.io_out_bits_Rd == 3,
            "M instruction lost its destination write");
    }

    // ADD x3,x5,x0 depends on a younger, non-head MDU write to x5.  Its value
    // is not on the single EX forwarding port yet, so the scoreboard must
    // hold the consumer until the queued writer retires.
    dut.io_in_bits_Instruction = 0x000281b3U;
    dut.io_ex_mdu_hidden_writes = 1U << 5;
    dut.eval();
    check(!dut.io_in_ready && !dut.io_out_valid && dut.io_perf_stall_raw,
          "IDU ignored a hidden queued-MDU RAW dependency");
    check(!dut.io_perf_stall_raw_loaduse,
          "queued-MDU RAW dependency was misclassified as load-use");

    dut.io_ex_mdu_hidden_writes = 0;
    dut.eval();
    check(dut.io_in_ready && dut.io_out_valid,
          "IDU remained stalled after the hidden MDU writer retired");
    dut.final();
  });
}
