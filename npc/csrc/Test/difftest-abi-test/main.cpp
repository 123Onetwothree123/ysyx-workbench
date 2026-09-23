#include <dlfcn.h>

#include <array>
#include <cstddef>
#include <cstdint>
#include <iostream>
#include <stdexcept>
#include <string>

namespace {

struct RV32DifftestState {
  std::array<std::uint32_t, 32> gpr{};
  std::uint32_t pc{};
};

static_assert(sizeof(RV32DifftestState) == 132);

struct GuardedState {
  std::array<std::uint64_t, 4> before{};
  RV32DifftestState state{};
  std::array<std::uint64_t, 4> after{};
};

void check(bool condition, const std::string &message) {
  if (!condition) {
    throw std::runtime_error(message);
  }
}

template <typename Function>
Function load(void *handle, const char *name) {
  dlerror();
  auto *symbol = dlsym(handle, name);
  if (const char *error = dlerror(); error != nullptr) {
    throw std::runtime_error(std::string{"missing symbol "} + name + ": " +
                             error);
  }
  return reinterpret_cast<Function>(symbol);
}

} // namespace

int main(int argc, char **argv) {
  if (argc != 2 && argc != 3) {
    std::cerr << "usage: " << argv[0]
              << " <riscv32-nemu-interpreter-so> [--rve]\n";
    return 2;
  }
  const bool rve = argc == 3 && std::string{argv[2]} == "--rve";
  if (argc == 3 && !rve) {
    std::cerr << "unknown option: " << argv[2] << '\n';
    return 2;
  }

  try {
    void *handle = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (handle == nullptr) {
      const char *error = dlerror();
      throw std::runtime_error(std::string{"dlopen failed: "} +
                               (error ? error : "unknown"));
    }

    using StateSize = std::size_t (*)();
    using RegCopy = void (*)(void *, bool);
    const auto state_size = load<StateSize>(handle, "difftest_state_size");
    const auto regcpy = load<RegCopy>(handle, "difftest_regcpy");

    check(state_size() == sizeof(RV32DifftestState),
          "reference reports a non-fixed DiffTest state size");

    constexpr std::uint64_t kBefore = 0x0123456789abcdefULL;
    constexpr std::uint64_t kAfter = 0xfedcba9876543210ULL;
    GuardedState guarded;
    guarded.before.fill(kBefore);
    guarded.after.fill(kAfter);
    for (std::size_t i = 0; i < guarded.state.gpr.size(); ++i) {
      guarded.state.gpr[i] = 0x10000000U + static_cast<std::uint32_t>(i);
    }
    guarded.state.pc = 0x81234560U;

    constexpr bool kToDUT = false;
    constexpr bool kToREF = true;
    regcpy(&guarded.state, kToREF);

    guarded.state = {};
    regcpy(&guarded.state, kToDUT);

    check(guarded.before == std::array<std::uint64_t, 4>{
                                  kBefore, kBefore, kBefore, kBefore},
          "difftest_regcpy wrote before the 132-byte state buffer");
    check(guarded.after == std::array<std::uint64_t, 4>{
                                 kAfter, kAfter, kAfter, kAfter},
          "difftest_regcpy wrote past the 132-byte state buffer");
    for (std::size_t i = 0; i < guarded.state.gpr.size(); ++i) {
      const auto expected =
          rve && i >= 16
              ? 0U
              : 0x10000000U + static_cast<std::uint32_t>(i);
      check(guarded.state.gpr[i] == expected,
            "GPR changed across the fixed ABI round trip");
    }
    check(guarded.state.pc == 0x81234560U,
          "PC changed across the fixed ABI round trip");

    dlclose(handle);
    std::cout << "[PASS] fixed 32-GPR + PC DiffTest ABI has no overrun ("
              << (rve ? "RVE" : "RV32I") << ")\n";
    return 0;
  } catch (const std::exception &error) {
    std::cerr << "[FAIL] DiffTest ABI: " << error.what() << '\n';
    return 1;
  }
}
