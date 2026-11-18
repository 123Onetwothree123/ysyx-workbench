// ysyxSoCFull 浮点/trap 调试端口端口级探针。
//
// 不经过 DiffTest：直接把 main.S 镜像放进 MROM，运行到 custom halt 后
// 通过 full-SoC 顶层端口回读 f2/f3、fcsr 和最后一次 trap 的 tval。
// 这样可以把“端口没有真正接到 CPU 黑盒”与“DiffTest 逻辑有问题”分开。
#include "VysyxSoCFull.h"
#include <verilated.h>
#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iterator>
#include <vector>

namespace {
constexpr std::uint32_t MromBase = 0x30000000u;
constexpr std::uint32_t F1_5 = 0x3fc00000u;
constexpr std::uint32_t F3_0 = 0x40400000u;
constexpr std::uint32_t FcsrValue = 0x40u;
constexpr std::uint32_t TrapTval = 0x30000001u;
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
  dut.externalPins_uart_rx = 1;
  dut.debug_gpr_raddr = 0;
  dut.debug_fpr_raddr = 0;

  std::array<bool, 32> fprWritten{};
  bool fpValid = false;

  auto tick = [&]() {
    dut.clock = 0;
    dut.eval();
    // 写口是上升沿前的组合信号；在沿前采样才能对应本次退休。
    if (dut.debug_fpr_wen) {
      const auto index = static_cast<unsigned>(dut.debug_fpr_waddr) & 31u;
      fprWritten[index] = true;
    }
    if (dut.debug_fp_valid)
      fpValid = true;
    dut.clock = 1;
    dut.eval();
  };

  for (int i = 0; i < 200; ++i)
    tick();
  dut.reset = 0;

  bool halted = false;
  for (int cycle = 0; cycle < 500000; ++cycle) {
    tick();
    if (dut.trap_valid) {
      halted = true;
      break;
    }
  }
  if (!halted) {
    std::fprintf(stderr,
                 "超时: pc=%08x commit_pc=%08x trap=%d cause=%08x\n",
                 dut.debug_arch_pc, dut.debug_pc, dut.debug_trap_valid,
                 dut.debug_trap_cause);
    return 1;
  }

  dut.debug_gpr_raddr = 10;
  dut.eval();
  const auto haltCode = static_cast<std::uint32_t>(dut.debug_gpr_rdata);

  auto readFpr = [&](unsigned index) -> std::uint64_t {
    dut.debug_fpr_raddr = index & 31u;
    dut.eval();
    return static_cast<std::uint64_t>(dut.debug_fpr_rdata) |
           (static_cast<std::uint64_t>(dut.debug_fpr_rdata_hi) << 32U);
  };
  const auto f2 = readFpr(2);
  const auto f3 = readFpr(3);
  dut.eval();
  const auto fcsr = static_cast<std::uint32_t>(dut.debug_fcsr) & 0xffu;
  const auto trapTval = static_cast<std::uint64_t>(dut.debug_trap_tval) |
                        (static_cast<std::uint64_t>(dut.debug_trap_tval_hi) << 32U);

  std::printf("halt code=%u f2=%016llx f3=%016llx fcsr=%02x "
              "tval=%016llx fp_valid=%u fpr_wen(f2)=%u fpr_wen(f3)=%u\n",
              haltCode,
              static_cast<unsigned long long>(f2),
              static_cast<unsigned long long>(f3), fcsr,
              static_cast<unsigned long long>(trapTval),
              static_cast<unsigned>(dut.debug_fp_valid),
              static_cast<unsigned>(fprWritten[2]),
              static_cast<unsigned>(fprWritten[3]));

  const bool ok = haltCode == 0 && fpValid && dut.debug_fp_valid &&
                  f2 == F1_5 && f3 == F3_0 && fcsr == FcsrValue &&
                  trapTval == TrapTval && fprWritten[2] && fprWritten[3] &&
                  dut.debug_fpr_rdata_hi == 0 &&
                  dut.debug_trap_tval_hi == 0;
  if (!ok) {
    std::fprintf(stderr, "full-SoC 浮点/trap 调试端口检查失败\n");
    return 1;
  }
  std::printf("full-SoC 浮点/trap 调试端口检查 PASS\n");
  return 0;
}
