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

static void expect_disabled_m_instruction_illegal(DUT &dut, uint8_t funct3) {
  // funct7=0000001/opcode=OP is one of MUL[H]*, DIV[U] or REM[U].  This
  // test is built from the RV32_M=n production RTL, so every one must remain
  // a precise illegal instruction instead of entering an absent MDU path.
  dut.io_in_bits_Instruction = 0x02000033U |
                               (static_cast<uint32_t>(funct3 & 0x7U) << 12);
  dut.eval();
  check(dut.io_in_ready && dut.io_out_valid,
        "disabled M instruction was unexpectedly stalled");
  check(dut.io_out_bits_ExceptionValid,
        "RV32_M=n accepted an M instruction");
  check(dut.io_out_bits_ExceptionCause == 2,
        "disabled M instruction did not raise illegal-instruction cause 2");
}

static void expect_wfi_nop(DUT &dut) {
  constexpr uint32_t kWfi = 0x10500073U;
  constexpr uint32_t kPc = 0x80000120U;
  dut.io_in_bits_Instruction = kWfi;
  dut.io_in_bits_pc = kPc;
  dut.eval();

  check(dut.io_in_ready && dut.io_out_valid,
        "WFI was unexpectedly stalled");
  check(!dut.io_out_bits_ExceptionValid,
        "M-mode WFI raised illegal-instruction");
  check(dut.io_out_bits_snpc == kPc + 4U,
        "WFI did not preserve the sequential PC");
  check(!dut.io_out_bits_RegisterWrite,
        "WFI unexpectedly enabled a GPR write");
  check(!dut.io_out_bits_MemoryValid && !dut.io_out_bits_MemoryWrite,
        "WFI unexpectedly enabled a memory access");
  check(!dut.io_out_bits_IsCsrrw && !dut.io_out_bits_IsCsrrs &&
            !dut.io_out_bits_IsCsrrc,
        "WFI unexpectedly decoded as a CSR operation");
  check(!dut.io_out_bits_IsEcall && !dut.io_out_bits_IsEbreak &&
            !dut.io_out_bits_IsMret,
        "WFI unexpectedly decoded as another SYSTEM operation");
}

static void expect_illegal_system(DUT &dut, uint32_t instruction,
                                  const char *description) {
  dut.io_in_bits_Instruction = instruction;
  dut.eval();
  check(dut.io_in_ready && dut.io_out_valid,
        std::string(description) + " was unexpectedly stalled");
  check(dut.io_out_bits_ExceptionValid,
        std::string(description) + " was accepted as WFI");
  check(dut.io_out_bits_ExceptionCause == 2,
        std::string(description) + " did not raise illegal-instruction cause 2");
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  return run_test("IDU FENCE/WFI and RV32_M-disabled illegal traps", [] {
    DUT dut;
    defaults(dut);

    expect_fence(dut, fence_encoding(0, 0xf, 0xf, 0, 0), "canonical FENCE");

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
    expect_fence(dut, fence_encoding(1, 0, 0, 0, 0),
                 "FENCE with reserved fm encoding");
    expect_fence(dut, fence_encoding(8, 0x3, 0x3, 0, 0),
                 "FENCE.TSO conservatively implemented as a full FENCE");

    expect_wfi_nop(dut);
    // Only the exact standard WFI encoding is legal: a neighbouring immediate
    // and a nonzero reserved rd must remain precise illegal instructions.
    expect_illegal_system(dut, 0x10400073U, "reserved SYSTEM immediate");
    expect_illegal_system(dut, 0x105000f3U, "WFI encoding with nonzero rd");

    for (uint8_t funct3 = 0; funct3 < 8; ++funct3)
      expect_disabled_m_instruction_illegal(dut, funct3);
    dut.final();
  });
}
