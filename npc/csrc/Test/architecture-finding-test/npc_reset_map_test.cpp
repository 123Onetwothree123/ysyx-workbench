#include "Vriscv32e_npc_SimTop.h"
#include "test_common.hpp"

#include <cstdint>
#include <iostream>
#include <verilated.h>

using DUT = Vriscv32e_npc_SimTop;

namespace {

void tick(DUT &dut) {
  dut.clock = 0;
  dut.eval();
  dut.clock = 1;
  dut.eval();
  dut.clock = 0;
  dut.eval();
}

void direct_npc_reset_pc_must_match_ram_image_base() {
  DUT dut;
  dut.clock = 0;
  dut.reset = 1;
  dut.ram_init_valid = 0;
  dut.ram_init_index = 0;
  dut.ram_init_data = 0x02a00513U; // addi a0, x0, 42
  dut.debug_gpr_raddr = 10;
  dut.ram_init_valid = 1;
  tick(dut);
  tick(dut);
  dut.ram_init_valid = 0;
  dut.reset = 0;
  dut.eval();

  constexpr std::uint32_t expected_direct_reset = 0x80000000U;
  const auto actual = static_cast<std::uint32_t>(dut.debug_arch_pc);
  if (actual != expected_direct_reset) {
    std::cerr << "SIM_TOP=npc CPU reset PC is 0x" << std::hex << actual
              << "; its AXIRAM image begins at 0x80000000\n";
    throw std::runtime_error("direct NPC reset PC does not match RAM base");
  }

  constexpr std::uint32_t program = 0x02a00513U; // addi a0, x0, 42
  bool saw_first_instruction = false;
  for (int cycle = 0; cycle < 100; ++cycle) {
    tick(dut);
    if (dut.debug_commit && dut.debug_pc == expected_direct_reset &&
        dut.debug_instructions == program) {
      saw_first_instruction = true;
      break;
    }
  }
  check(saw_first_instruction,
        "CPU did not retire the first instruction loaded into direct AXIRAM");
}

} // namespace

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  return run_test("SIM_TOP=npc reset PC matches AXIRAM base",
                  direct_npc_reset_pc_must_match_ram_image_base);
}
