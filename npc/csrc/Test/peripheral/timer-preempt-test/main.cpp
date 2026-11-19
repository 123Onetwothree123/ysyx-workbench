#include "Vriscv32e_npc_SimTop.h"
#include "../common/verilator_harness.hpp"
#include <cstdint>
#include <cstdio>
#include <vector>

int main(int argc, char **argv) {
  std::vector<std::uint8_t> image;
  if (!npc_test::load_image(argc, argv, image)) {
    return 2;
  }
  Vriscv32e_npc_SimTop dut;
  npc_test::reset_and_load(dut, image);

  // 8 个 2000 周期的时间片，加上启动/流水线余量，20 万周期足够。
  for (int cycle = 0; cycle < 200000; ++cycle) {
    npc_test::tick(dut);
    int code = 0;
    if (npc_test::halt(dut, code)) {
      return code;
    }
  }
  return npc_test::timeout(dut);
}
