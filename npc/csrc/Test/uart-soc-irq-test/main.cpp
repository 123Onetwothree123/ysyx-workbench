// ysyxSoCFull UART -> PLIC source 10 -> CPU 的定向仿真测试平台。
// 镜像从 MROM 0x30000000 取指；测试程序只使用寄存器，不依赖可写内存，
// 因此不需要 flash/PSRAM/SDRAM 的启动复制流程。
#include "VysyxSoCFull.h"
#include <verilated.h>
#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iterator>
#include <vector>

namespace {
constexpr std::uint32_t MromBase = 0x30000000u;
std::vector<std::uint8_t> Mrom;
std::vector<std::uint8_t> Flash;

std::uint32_t LoadU32(const std::vector<std::uint8_t> &Memory,
                      std::uint32_t Aligned) {
  std::uint32_t value = 0;
  if (Aligned < Memory.size()) {
    const auto count =
        std::min<std::size_t>(4, Memory.size() - static_cast<std::size_t>(Aligned));
    std::memcpy(&value, Memory.data() + Aligned, count);
  }
  return value;
}
} // namespace

extern "C" void mrom_read(std::int32_t Address, std::int32_t *Data) {
  const auto offset = static_cast<std::uint32_t>(Address) - MromBase;
  *Data = static_cast<std::int32_t>(LoadU32(Mrom, offset & ~3u));
}

extern "C" void flash_read(std::int32_t Address, std::int32_t *Data) {
  *Data = static_cast<std::int32_t>(
      LoadU32(Flash, static_cast<std::uint32_t>(Address) & ~3u));
}

static void tick(VysyxSoCFull &dut) {
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
  Flash.assign(std::istreambuf_iterator<char>(in), {});
  Mrom = Flash;

  VysyxSoCFull dut;
  dut.clock = 0;
  dut.reset = 1;
  dut.externalPins_gpio_in = 0;
  dut.externalPins_ps2_clk = 0;
  dut.externalPins_ps2_data = 0;
  // UART 串行输入保持空闲高电平；本测试只用 THRE 中断，不需要注入 RX。
  dut.externalPins_uart_rx = 1;
  dut.debug_gpr_raddr = 0;

  // ysyxSoCFull 内部 CPU 复位经过同步器，留足复位周期。
  for (int i = 0; i < 200; ++i) {
    tick(dut);
  }
  dut.reset = 0;

  for (int cycle = 0; cycle < 500000; ++cycle) {
    tick(dut);
    if (dut.trap_valid) {
      dut.debug_gpr_raddr = 10;
      dut.eval();
      const std::uint32_t code = dut.debug_gpr_rdata;
      std::printf("halt pc=%08x code=%08x cycle=%d\n", dut.trap_pc, code, cycle);
      return code == 0 ? 0 : 1;
    }
  }
  std::printf(
      "timeout pc=%08x commit_pc=%08x trap=%d cause=%08x mcause=%08x "
      "mtvec=%08x mepc=%08x\n",
      dut.debug_arch_pc, dut.debug_pc, dut.debug_trap_valid,
      dut.debug_trap_cause, dut.debug_csr_mcause, dut.debug_csr_mtvec,
      dut.debug_csr_mepc);
  return 1;
}
