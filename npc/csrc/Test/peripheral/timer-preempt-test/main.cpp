#include "Vriscv32e_npc_SimTop.h"
#include <verilated.h>
#include <cstdint>
#include <cstdio>
#include <fstream>
#include <iterator>
#include <vector>

static void tick(Vriscv32e_npc_SimTop &dut) {
  dut.clock = 0;
  dut.eval();
  dut.clock = 1;
  dut.eval();
}

int main(int argc, char **argv) {
  if (argc < 2) {
    std::fprintf(stderr, "用法: %s main.bin\n", argv[0]);
    return 2;
  }
  Verilated::commandArgs(argc, argv);
  std::ifstream in(argv[1], std::ios::binary);
  if (!in) {
    std::fprintf(stderr, "无法打开镜像 %s\n", argv[1]);
    return 2;
  }
  std::vector<std::uint8_t> image{std::istreambuf_iterator<char>(in), {}};
  Vriscv32e_npc_SimTop dut;
  dut.clock = 0;
  dut.reset = 1;
  dut.ram_init_valid = 0;
  dut.ram_init_index = 0;
  dut.ram_init_data = 0;
  dut.uart_rx_valid = 0;
  dut.uart_rx_data = 0;
  dut.debug_gpr_raddr = 0;

  for (int i = 0; i < 20; ++i) {
    tick(dut);
  }
  const std::size_t words = (image.size() + 3) / 4;
  for (std::size_t word = 0; word < words; ++word) {
    std::uint32_t value = 0;
    for (std::size_t byte = 0; byte < 4; ++byte) {
      const std::size_t index = word * 4 + byte;
      if (index < image.size()) {
        value |= static_cast<std::uint32_t>(image[index]) << (8 * byte);
      }
    }
    dut.ram_init_index = static_cast<std::uint32_t>(word);
    dut.ram_init_data = value;
    dut.ram_init_valid = 1;
    tick(dut);
  }
  dut.ram_init_valid = 0;
  dut.reset = 0;

  // 8 个 2000 周期的时间片，加上启动/流水线余量，20 万周期足够。
  for (int cycle = 0; cycle < 200000; ++cycle) {
    tick(dut);
    if (dut.trap_valid) {
      dut.debug_gpr_raddr = 10;
      dut.eval();
      const std::uint32_t code = dut.debug_gpr_rdata;
      std::printf("halt pc=%08x code=%08x\n", dut.trap_pc, code);
      return code == 0 ? 0 : 1;
    }
  }
  std::printf("timeout pc=%08x\n", dut.debug_arch_pc);
  return 1;
}
