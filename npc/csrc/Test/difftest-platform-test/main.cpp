#include <array>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <dlfcn.h>
#include <iostream>
#include <stdexcept>
#include <string>

namespace {

constexpr bool kToDUT = false;
constexpr bool kToREF = true;
constexpr std::uint32_t kFlash = 0x30000000u;

struct CPUState {
  std::array<std::uint32_t, 32> gpr{};
  std::uint32_t pc{};
};

struct CSRState {
  std::uint32_t mstatus{};
  std::uint32_t mtvec{};
  std::uint32_t mepc{};
  std::uint32_t mcause{};
};

static_assert(sizeof(CPUState) == 132);
static_assert(sizeof(CSRState) == 16);

int failures = 0;

void check(bool condition, const std::string &message) {
  if (!condition) {
    ++failures;
    std::cerr << "[FAIL] " << message << '\n';
  }
}

template <typename T> T load(void *handle, const char *name) {
  dlerror();
  void *symbol = dlsym(handle, name);
  if (const char *error = dlerror(); error != nullptr)
    throw std::runtime_error(std::string{name} + ": " + error);
  return reinterpret_cast<T>(symbol);
}

constexpr std::uint32_t csrw(std::uint32_t csr, std::uint32_t rs1) {
  return (csr << 20) | (rs1 << 15) | (1u << 12) | 0x73u;
}

} // namespace

int main(int argc, char **argv) try {
  if (argc != 2)
    throw std::runtime_error("usage: difftest_platform_test REF_SO");
  void *handle = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
  if (handle == nullptr)
    throw std::runtime_error(dlerror());

  using Init = void (*)(int);
  using SetPlatform = void (*)(int);
  using RangeSupported = bool (*)(std::uint32_t, std::size_t);
  using Memcpy = void (*)(std::uint32_t, void *, std::size_t, bool);
  using Regcpy = void (*)(void *, bool);
  using CSRcpy = void (*)(void *, bool);
  using Exec = void (*)(std::uint64_t);
  using Raise = void (*)(std::uint64_t);

  const auto init = load<Init>(handle, "difftest_init");
  const auto set_platform =
      load<SetPlatform>(handle, "difftest_set_platform");
  const auto supported = load<RangeSupported>(
      handle, "difftest_memory_range_supported");
  const auto memcopy = load<Memcpy>(handle, "difftest_memcpy");
  const auto regcopy = load<Regcpy>(handle, "difftest_regcpy");
  const auto csrcopy = load<CSRcpy>(handle, "difftest_csrcpy");
  const auto exec = load<Exec>(handle, "difftest_exec");
  const auto raise = load<Raise>(handle, "difftest_raise_intr");

  set_platform(0);
  init(0);
  check(supported(0x80000000u, 4), "direct NPC RAM was rejected");
  check(!supported(kFlash, 4), "ordinary NEMU exposed the SoC flash map");
  check(!supported(0x0f000000u, 4), "ordinary NEMU exposed SoC SRAM");
  check(!supported(0xa0000048u, 4),
        "ordinary NEMU RTC was shadowed by SoC SDRAM");

  set_platform(1);
  init(0);
  check(supported(kFlash, 4), "full-SoC flash image range was rejected");
  check(supported(0x0f007ffcu, 4), "upper 24 KiB of 32 KiB SRAM missing");
  check(!supported(0x0f007ffcu, 8), "cross-SRAM copy was accepted");
  check(supported(0x803ffffcu, 4), "upper end of 4 MiB PSRAM missing");
  check(!supported(0x80400000u, 4), "address beyond SoC PSRAM was accepted");
  check(supported(0xa0000000u, 4), "full-SoC SDRAM range was rejected");

  std::array<std::uint32_t, 65> image{};
  image[0] = csrw(0x305u, 1); // mtvec <- x1
  image[1] = csrw(0x341u, 2); // mepc <- x2
  image[2] = csrw(0x300u, 3); // mstatus <- x3
  image[3] = 0x00000073u;     // ecall
  image[64] = 0x30200073u;    // mret at +0x100
  memcopy(kFlash, image.data(), image.size() * sizeof(image[0]), kToREF);
  std::array<std::uint32_t, 65> image_readback{};
  memcopy(kFlash, image_readback.data(),
          image_readback.size() * sizeof(image_readback[0]), kToDUT);
  check(image_readback == image, "full-SoC flash DiffTest copy corrupted data");

  CPUState cpu{};
  cpu.pc = kFlash;
  cpu.gpr[1] = kFlash + 0x103u; // reserved MODE=3 -> Direct
  cpu.gpr[2] = 0x81234567u;
  cpu.gpr[3] = 1u << 3; // MIE
  regcopy(&cpu, kToREF);
  CSRState csr{};
  csrcopy(&csr, kToREF);

  exec(1);
  csrcopy(&csr, kToDUT);
  check(csr.mtvec == kFlash + 0x100u,
        "mtvec reserved MODE did not normalize to Direct");

  exec(1);
  csrcopy(&csr, kToDUT);
  check(csr.mepc == 0x81234564u, "mepc did not enforce IALIGN=32");

  exec(2); // csrw mstatus; ecall
  regcopy(&cpu, kToDUT);
  csrcopy(&csr, kToDUT);
  check(cpu.pc == kFlash + 0x100u, "synchronous trap ignored mtvec BASE");
  check(csr.mepc == kFlash + 12u && csr.mcause == 11u,
        "ecall mepc/mcause state is incorrect");
  check(csr.mstatus == 0x1880u,
        "trap did not set MPP/MPIE and clear MIE like the DUT");

  exec(1); // mret
  regcopy(&cpu, kToDUT);
  csrcopy(&csr, kToDUT);
  check(cpu.pc == kFlash + 12u, "mret did not jump to mepc");
  check(csr.mstatus == 0x88u,
        "mret did not restore MIE/set MPIE/clear MPP like the DUT");

  cpu.pc = kFlash + 0x20u;
  regcopy(&cpu, kToREF);
  csr = {.mstatus = 1u << 3,
         .mtvec = kFlash + 0x101u,
         .mepc = 0,
         .mcause = 0};
  csrcopy(&csr, kToREF);
  raise(0x80000007u);
  regcopy(&cpu, kToDUT);
  csrcopy(&csr, kToDUT);
  check(cpu.pc == kFlash + 0x100u + 4u * 7u,
        "vectored interrupt target is incorrect");
  check(csr.mepc == kFlash + 0x20u && csr.mcause == 0x80000007u,
        "injected interrupt CSR state is incorrect");

  dlclose(handle);
  if (failures != 0) {
    std::cerr << failures << " DiffTest platform/CSR check(s) failed\n";
    return 1;
  }
  std::cout << "[PASS] DiffTest platform map, full-SoC image and CSR semantics\n";
  return 0;
} catch (const std::exception &error) {
  std::cerr << "[FAIL] " << error.what() << '\n';
  return 1;
}
