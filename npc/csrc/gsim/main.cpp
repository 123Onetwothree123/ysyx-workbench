// direct-NPC的GSIM主机侧harness
// 只依赖GSIM生成的C++类和ramInit/UART/trap端口；镜像按SoC字宽
// 小端写入ramInit，host写口在reset期间同样生效

#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <poll.h>
#include <unistd.h>

#include <deque>
#include <vector>

#include "riscv32e_npc_SimTop.h"

namespace {

constexpr std::uint64_t DefaultMaxCycles = 10000000;
// 生成类的ram_init_data类型随SOCXLEN变化，直接从端口成员推导
using RamWord = decltype(Sriscv32e_npc_SimTop::ram_init_data);
constexpr std::size_t WordBytes = sizeof(RamWord);
constexpr std::uint8_t GprA0Index = 10;

#ifndef NPC_GSIM_CACHE_PADDING
#define NPC_GSIM_CACHE_PADDING 0
#endif
#ifndef NPC_GSIM_RAM_BYTES
#define NPC_GSIM_RAM_BYTES 0
#endif

// CACHE_PADDING以字节为单位，ram_init_index以SoC字为单位；
// 非字对齐时无法用整字写口还原镜像，直接编译失败而不是静默截断
constexpr std::size_t CachePaddingBytes =
    static_cast<std::size_t>(NPC_GSIM_CACHE_PADDING);
static_assert(CachePaddingBytes % WordBytes == 0,
              "GSIM要求CACHE_PADDING按SoC RAM字宽对齐，"
              "否则镜像无法按字写入ram_init");
constexpr std::size_t CachePaddingWords = CachePaddingBytes / WordBytes;
constexpr std::uint64_t RamBytes =
    static_cast<std::uint64_t>(NPC_GSIM_RAM_BYTES);

std::vector<std::uint8_t> ReadImage(const char *path) {
  std::FILE *file = std::fopen(path, "rb");
  if (file == nullptr) {
    std::fprintf(stderr, "无法打开镜像 %s: %s\n", path, std::strerror(errno));
    std::exit(2);
  }
  std::vector<std::uint8_t> image;
  std::uint8_t chunk[4096];
  while (true) {
    const std::size_t count = std::fread(chunk, 1, sizeof(chunk), file);
    image.insert(image.end(), chunk, chunk + count);
    if (count < sizeof(chunk)) {
      break;
    }
  }
  const bool failed = std::ferror(file) != 0;
  std::fclose(file);
  if (failed) {
    std::fprintf(stderr, "读取镜像 %s 失败: %s\n", path, std::strerror(errno));
    std::exit(2);
  }
  return image;
}

void Step(Sriscv32e_npc_SimTop &dut, std::uint64_t count = 1) {
  for (std::uint64_t index = 0; index < count; ++index) {
    dut.step();
  }
}

void LoadImage(Sriscv32e_npc_SimTop &dut,
               const std::vector<std::uint8_t> &image) {
  const std::size_t wordCount = (image.size() + WordBytes - 1) / WordBytes;
  for (std::size_t wordIndex = 0; wordIndex < wordCount; ++wordIndex) {
    RamWord word = 0;
    for (std::size_t byteIndex = 0; byteIndex < WordBytes; ++byteIndex) {
      const std::size_t imageIndex = wordIndex * WordBytes + byteIndex;
      if (imageIndex < image.size()) {
        word |= static_cast<RamWord>(image[imageIndex])
                << (byteIndex * 8U);
      }
    }
    // 镜像整体后移padding，复位PC由RTL同步加padding
    dut.set_ram_init_index(
        static_cast<std::uint32_t>(CachePaddingWords + wordIndex));
    dut.set_ram_init_data(word);
    dut.set_ram_init_valid(1);
    dut.step();
  }
  dut.set_ram_init_valid(0);
  dut.set_ram_init_index(0);
  dut.set_ram_init_data(0);
}

void PumpStdin(std::deque<std::uint8_t> &pending) {
  // 非阻塞地尽量读空stdin，避免每周期一次read系统调用
  while (pending.size() < 256) {
    pollfd descriptor{STDIN_FILENO, POLLIN, 0};
    if (poll(&descriptor, 1, 0) <= 0 || (descriptor.revents & POLLIN) == 0) {
      return;
    }
    std::uint8_t buffer[64];
    const ssize_t count = read(STDIN_FILENO, buffer, sizeof(buffer));
    if (count <= 0) {
      return;
    }
    for (ssize_t index = 0; index < count; ++index) {
      pending.push_back(buffer[index]);
    }
  }
}

}  // namespace

int main(int argc, char **argv) {
  if (argc < 2) {
    std::fprintf(stderr, "用法: %s <image.bin> [max-cycles]\n", argv[0]);
    return 2;
  }
  std::uint64_t maxCycles = DefaultMaxCycles;
  if (argc >= 3) {
    maxCycles = std::strtoull(argv[2], nullptr, 0);
  }
  const std::vector<std::uint8_t> image = ReadImage(argv[1]);
  if (RamBytes != 0 &&
      (CachePaddingBytes > RamBytes ||
       image.size() > RamBytes - CachePaddingBytes)) {
    std::fprintf(stderr,
                 "镜像过大：%zu 字节 + %zu 字节填充，超过 RAM %llu 字节\n",
                 image.size(), CachePaddingBytes,
                 static_cast<unsigned long long>(RamBytes));
    return 2;
  }

  Sriscv32e_npc_SimTop *dut = new Sriscv32e_npc_SimTop();
  dut->set_clock(0);
  dut->set_reset(1);
  dut->set_ram_init_valid(0);
  dut->set_ram_init_index(0);
  dut->set_ram_init_data(0);
  dut->set_uart_rx_valid(0);
  dut->set_uart_rx_data(0);
  dut->set_debug_gpr_raddr(GprA0Index);

  LoadImage(*dut, image);
  Step(*dut, 10);
  dut->set_reset(0);

  std::deque<std::uint8_t> uartPending;
  std::uint64_t cycles = 0;
  bool halted = false;
  std::uint32_t haltCode = 1;
  std::uint32_t haltPc = 0;
  std::uint32_t haltPcHigh = 0;

  while (cycles < maxCycles) {
    PumpStdin(uartPending);
    const bool uartValid =
        !uartPending.empty() && dut->get_uart_rx_ready() != 0;
    dut->set_uart_rx_valid(uartValid ? 1 : 0);
    dut->set_uart_rx_data(uartValid ? uartPending.front() : 0);
    dut->step();
    ++cycles;
    if (uartValid) {
      uartPending.pop_front();
    }
    if (dut->get_uart_tx_valid() != 0) {
      std::putchar(dut->get_uart_tx_data());
      std::fflush(stdout);
    }
    // trap_valid是custom halt提交后一拍的脉冲；a0此时已经是最终值
    if (dut->get_trap_valid() != 0) {
      haltPc = dut->get_trap_pc();
      haltPcHigh = dut->get_trap_pc_hi();
      haltCode = dut->get_debug_gpr_rdata();
      halted = true;
      break;
    }
  }

  if (!halted) {
    std::fprintf(stderr, "仿真超时：%llu 周期内未收到 halt\n",
                 static_cast<unsigned long long>(maxCycles));
    delete dut;
    return 1;
  }

  if (haltCode == 0) {
    if (haltPcHigh != 0) {
      std::printf("HIT GOOD TRAP at pc = 0x%08x%08x, cycles = %llu\n",
                  haltPcHigh, haltPc,
                  static_cast<unsigned long long>(cycles));
    } else {
      std::printf("HIT GOOD TRAP at pc = 0x%08x, cycles = %llu\n", haltPc,
                  static_cast<unsigned long long>(cycles));
    }
    delete dut;
    return 0;
  }

  if (haltPcHigh != 0) {
    std::printf("HIT BAD TRAP at pc = 0x%08x%08x, code = %u, cycles = %llu\n",
                haltPcHigh, haltPc, haltCode,
                static_cast<unsigned long long>(cycles));
  } else {
    std::printf("HIT BAD TRAP at pc = 0x%08x, code = %u, cycles = %llu\n",
                haltPc, haltCode, static_cast<unsigned long long>(cycles));
  }
  std::fflush(stdout);
  std::fprintf(stderr, "last trap cause = 0x%08x, tval = 0x%08x\n",
               dut->get_debug_trap_cause(), dut->get_debug_trap_tval());
  delete dut;
  return 1;
}
