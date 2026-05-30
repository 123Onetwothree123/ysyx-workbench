#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>

#include <verilated.h>
#include "VRV32I.h"

namespace {

constexpr std::uint64_t kDefaultCycles = 1000;
constexpr int kResetCycles = 5;

std::uint64_t parse_cycles(int argc, char **argv) {
  for (int i = 1; i < argc; ++i) {
    constexpr const char *prefix = "--cycles=";
    constexpr std::size_t prefix_len = 9;
    if (std::strncmp(argv[i], prefix, prefix_len) == 0) {
      return std::strtoull(argv[i] + prefix_len, nullptr, 0);
    }
  }
  return kDefaultCycles;
}

const char *find_image_arg(int argc, char **argv) {
  for (int i = 1; i < argc; ++i) {
    if (std::strcmp(argv[i], "--elf") == 0) {
      ++i;
      continue;
    }
    if (std::strncmp(argv[i], "--cycles=", 9) == 0) {
      continue;
    }
    if (argv[i][0] != '-') {
      return argv[i];
    }
  }
  return nullptr;
}

void tick(VerilatedContext &context, VRV32I &top) {
  top.clock = 0;
  top.eval();
  context.timeInc(1);

  top.clock = 1;
  top.eval();
  context.timeInc(1);
}

} // namespace

int main(int argc, char **argv) {
  VerilatedContext context;
  context.commandArgs(argc, argv);

  VRV32I top(&context);
  const std::uint64_t max_cycles = parse_cycles(argc, argv);
  const char *image = find_image_arg(argc, argv);

  if (image != nullptr) {
    std::printf("image: %s (not loaded by this minimal harness yet)\n", image);
  }

  top.io_InstructionReadDATA = 0x00000013U; // addi x0, x0, 0
  top.io_MemoryReadDATA = 0;

  top.reset = 1;
  for (int i = 0; i < kResetCycles; ++i) {
    tick(context, top);
  }

  top.reset = 0;
  for (std::uint64_t cycle = 0; cycle < max_cycles && !context.gotFinish(); ++cycle) {
    top.io_InstructionReadDATA = 0x00000013U;
    top.io_MemoryReadDATA = 0;
    tick(context, top);

    if ((cycle & 0x3fU) == 0) {
      std::printf(
          "cycle=%llu pc=0x%08x mem_we=%u mem_addr=0x%08x\n",
          static_cast<unsigned long long>(cycle),
          static_cast<unsigned>(top.io_InstructionAddress),
          static_cast<unsigned>(top.io_MemWE),
          static_cast<unsigned>(top.io_MemAddr));
    }
  }

  top.final();
  std::printf("simulation finished at time=%llu\n",
              static_cast<unsigned long long>(context.time()));
  return 0;
}
