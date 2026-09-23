#include "Vysyx_26030103_IDU.h"
#include "test_common.hpp"
#include <cstdint>
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

static uint32_t fence_encoding(uint8_t fm, uint8_t predecessor,
                               uint8_t successor, uint8_t rs1, uint8_t rd) {
  return (static_cast<uint32_t>(fm & 0xfU) << 28) |
         (static_cast<uint32_t>(predecessor & 0xfU) << 24) |
         (static_cast<uint32_t>(successor & 0xfU) << 20) |
         (static_cast<uint32_t>(rs1 & 0x1fU) << 15) |
         (static_cast<uint32_t>(rd & 0x1fU) << 7) | 0x0fU;
}

static void expect_fence(DUT &dut, uint32_t instruction,
                         const char *description) {
  dut.io_in_bits_Instruction = instruction;
  dut.eval();
  check(dut.io_in_ready && dut.io_out_valid,
        std::string(description) + " was unexpectedly stalled");
  check(dut.io_out_bits_IsFence,
        std::string(description) + " was not decoded as FENCE");
  check(!dut.io_out_bits_ExceptionValid,
        std::string(description) + " raised illegal-instruction");
  check(!dut.io_out_bits_RegisterWrite,
        std::string(description) + " treated reserved rd as a write");
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  return run_test("IDU FENCE reserved rd/rs1 fields", [] {
    DUT dut;
    defaults(dut);

    expect_fence(dut, fence_encoding(0, 0xf, 0xf, 0, 0),
                 "canonical FENCE");

    // Base implementations ignore rd and rs1.  Also present matching pending
    // register writes: these fields must not accidentally create RAW hazards.
    dut.io_ex_valid = 1;
    dut.io_ex_rd = 6;
    dut.io_ex_regWrite = 1;
    dut.io_me_valid = 1;
    dut.io_me_rd = 7;
    dut.io_me_regWrite = 1;
    expect_fence(dut, fence_encoding(0, 0xa, 0x5, 6, 7),
                 "FENCE with nonzero reserved fields");

    dut.io_ex_valid = 0;
    dut.io_me_valid = 0;
    dut.io_in_bits_Instruction = fence_encoding(1, 0, 0, 0, 0);
    dut.eval();
    check(!dut.io_out_bits_IsFence && dut.io_out_bits_ExceptionValid &&
              dut.io_out_bits_ExceptionCause == 2,
          "unsupported fm encoding was silently accepted as base FENCE");
    dut.final();
  });
}
