// NEMU REF 可选诊断 ABI 回归：
//   - difftest_get_last_instruction() 必须报告刚执行的 PC/指令字；
//   - difftest_get_csr(0x343/0x143) 必须返回完整 XLEN 的 mtval/stval；
//   - 非法 CSR 编号必须明确返回 false，不能伪造 0。
//
// 直接 dlopen REF .so，不依赖 NPC RTL。RV32/RV64 由状态 ABI 大小识别。
#include <dlfcn.h>

#include <array>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <stdexcept>
#include <string>
#include <utility>

namespace {

constexpr std::uint64_t kBase = 0x80000000ULL;

void check(bool condition, const std::string &message) {
  if (!condition)
    throw std::runtime_error(message);
}

template <typename Function> Function load(void *handle, const char *name) {
  dlerror();
  auto *symbol = dlsym(handle, name);
  if (const char *error = dlerror(); error != nullptr)
    throw std::runtime_error(std::string{"missing symbol "} + name + ": " +
                             error);
  return reinterpret_cast<Function>(symbol);
}

struct State64 {
  std::array<std::uint64_t, 32> gpr{};
  std::uint64_t pc{};
};
struct State32 {
  std::array<std::uint32_t, 32> gpr{};
  std::uint32_t pc{};
};
static_assert(sizeof(State32) == 132);
static_assert(sizeof(State64) == 264);

struct Csr64 {
  std::uint64_t mstatus{}, mtvec{}, mepc{}, mcause{};
};
struct Csr32 {
  std::uint32_t mstatus{}, mtvec{}, mepc{}, mcause{};
};

struct Ref {
  using StateSize = std::size_t (*)();
  using Memcpy = void (*)(std::uint64_t, void *, std::size_t, bool);
  using Regcpy = void (*)(void *, bool);
  using Csrcpy = void (*)(void *, bool);
  using Exec = void (*)(std::uint64_t);
  using Trap = void (*)(std::uint64_t, std::uint64_t, std::uint64_t);
  using Init = void (*)(int);
  using SetPlatform = void (*)(int);
  using LastInstruction =
      void (*)(std::uint64_t *, std::uint64_t *);
  using GetCSR = bool (*)(std::uint32_t, std::uint64_t *);

  void *handle{};
  StateSize stateSize{};
  Memcpy memcpy{};
  Regcpy regcpy{};
  Csrcpy csrcpy{};
  Exec exec{};
  Trap trap{};
  Init init{};
  SetPlatform setPlatform{};
  LastInstruction lastInstruction{};
  GetCSR getCSR{};
  bool rv64{};

  explicit Ref(const char *path) {
    handle = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (handle == nullptr)
      throw std::runtime_error(std::string{"dlopen failed: "} + dlerror());
    stateSize = load<StateSize>(handle, "difftest_state_size");
    memcpy = load<Memcpy>(handle, "difftest_memcpy");
    regcpy = load<Regcpy>(handle, "difftest_regcpy");
    csrcpy = load<Csrcpy>(handle, "difftest_csrcpy");
    exec = load<Exec>(handle, "difftest_exec");
    trap = load<Trap>(handle, "difftest_trap");
    init = load<Init>(handle, "difftest_init");
    setPlatform = load<SetPlatform>(handle, "difftest_set_platform");
    // 本测试专门验证新 ABI，因此这两个符号必须存在。
    lastInstruction =
        load<LastInstruction>(handle, "difftest_get_last_instruction");
    getCSR = load<GetCSR>(handle, "difftest_get_csr");
    rv64 = stateSize() == 33 * sizeof(std::uint64_t);
    const auto expected =
        33 * (rv64 ? sizeof(std::uint64_t) : sizeof(std::uint32_t));
    check(stateSize() == expected, "unexpected difftest_state_size");
    setPlatform(0);
    init(0);
  }

  ~Ref() {
    if (handle != nullptr)
      dlclose(handle);
  }

  std::pair<std::uint64_t, std::uint64_t> last() {
    std::uint64_t pc = 0, inst = 0;
    lastInstruction(&pc, &inst);
    return {pc, inst};
  }
};

template <typename State>
void loadCode(Ref &ref, std::array<std::uint8_t, 4096> &code) {
  ref.memcpy(kBase, code.data(), code.size(), true);
  State state{};
  state.pc = static_cast<decltype(state.pc)>(kBase);
  ref.regcpy(&state, true);
}

template <typename Csr>
void setMtvec(Ref &ref) {
  Csr csr{};
  csr.mtvec = static_cast<decltype(csr.mtvec)>(kBase + 0x100);
  ref.csrcpy(&csr, true);
}

void runChecks(Ref &ref) {
  std::array<std::uint8_t, 4096> code{};
  const std::array<std::uint32_t, 5> words{
      0x123452b7U, // lui t0, 0x12345
      0x67828293U, // addi t0, t0, 0x678
      0x00000333U, // add t1, t0, zero
      0x34329073U, // csrw mtval, t0
      0x00000013U, // nop
  };
  for (std::size_t index = 0; index < words.size(); ++index)
    std::memcpy(code.data() + index * 4, &words[index], 4);

  const auto checkAt = [&](std::uint64_t offset, std::uint32_t expected) {
    const auto [pc, inst] = ref.last();
    check(pc == kBase + offset,
          "last instruction PC mismatch: got 0x" + std::to_string(pc));
    check(inst == expected,
          "last instruction word mismatch: got 0x" + std::to_string(inst));
  };

  if (ref.rv64) {
    State64 state{};
    check(sizeof(state) == ref.stateSize(), "RV64 state width mismatch");
    loadCode<State64>(ref, code);
  } else {
    State32 state{};
    check(sizeof(state) == ref.stateSize(), "RV32 state width mismatch");
    loadCode<State32>(ref, code);
  }

  for (std::size_t index = 0; index < 4; ++index) {
    ref.exec(1);
    checkAt(index * 4, words[index]);
  }

  std::uint64_t value = 0;
  check(ref.getCSR(0x343U, &value), "REF failed to read mtval");
  check(value == 0x12345678ULL, "mtval value was truncated/corrupted");

  check(!ref.getCSR(0x7ffU, &value), "unknown CSR unexpectedly succeeded");
  ref.lastInstruction(nullptr, nullptr); // 空指针必须安全，不能崩溃

  // 异常指令同样必须被记录：ecall 在 base+0x20，不进入退休路径，
  // 但失败报告仍应能指出 REF 看到的正是这条指令。
  constexpr std::uint32_t kEcall = 0x00000073U;
  std::memcpy(code.data() + 0x20, &kEcall, 4);
  ref.memcpy(kBase, code.data(), code.size(), true);
  if (ref.rv64) {
    State64 state{};
    state.pc = static_cast<std::uint64_t>(kBase + 0x20);
    ref.regcpy(&state, true);
  } else {
    State32 state{};
    state.pc = static_cast<std::uint32_t>(kBase + 0x20);
    ref.regcpy(&state, true);
  }
  ref.exec(1);
  checkAt(0x20, kEcall);

  const std::uint64_t fullTval = 0xdeadbeefcafebabeULL;
  if (ref.rv64) {
    setMtvec<Csr64>(ref);
  } else {
    setMtvec<Csr32>(ref);
  }
  ref.trap(2, kBase + 0x40, fullTval);
  value = 0;
  check(ref.getCSR(0x343U, &value), "REF failed to read mtval after trap");
  if (ref.rv64) {
    check(value == fullTval, "RV64 mtval lost its high 32 bits");
  } else {
    check(value == static_cast<std::uint32_t>(fullTval),
          "RV32 mtval was not truncated to 32 bits");
  }
}

} // namespace

int main(int argc, char **argv) {
  if (argc != 2) {
    std::cerr << "usage: " << argv[0] << " <nemu-ref.so>\n";
    return 2;
  }
  try {
    Ref ref{argv[1]};
    runChecks(ref);
    std::cout << "[PASS] REF last-instruction + CSR diagnostic ABI ("
              << (ref.rv64 ? "RV64" : "RV32") << ")\n";
    return 0;
  } catch (const std::exception &error) {
    std::cerr << "[FAIL] REF diagnostic ABI: " << error.what() << '\n';
    return 1;
  }
}
