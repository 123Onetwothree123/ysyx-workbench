#include "Vriscv32e_npc_SimTop.h"
#include "../common/verilator_harness.hpp"
#include <cstdint>
#include <vector>

int main(int argc, char **argv) {
  std::vector<std::uint8_t> image;
  if (!npc_test::load_image(argc, argv, image)) {
    return 2;
  }
  Vriscv32e_npc_SimTop dut;
  npc_test::reset_and_load(dut, image);

  bool injected = false;
  for (int cycle = 0; cycle < 20000; ++cycle) {
    // PLIC/UART 初始化完成后注入一个接收字节。
    if (!injected && cycle == 300) {
      dut.uart_rx_data = static_cast<std::uint8_t>('Z');
      dut.uart_rx_valid = 1;
      injected = true;
    } else {
      dut.uart_rx_valid = 0;
    }
    npc_test::tick(dut);
    int code = 0;
    if (npc_test::halt(dut, code)) {
      return code;
    }
  }
  return npc_test::timeout(dut);
}
