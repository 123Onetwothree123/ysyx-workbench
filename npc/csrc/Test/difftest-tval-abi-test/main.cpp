// NEMU DiffTest tval 注入 ABI 回归。
//
// 直接加载 REF 共享库，不依赖 NPC RTL：
//   1. difftest_trap(no, epc, tval) 必须把 tval 写入 mtval；
//   2. 委托到 S 模式时必须写入 stval；
//   3. 旧接口 difftest_raise_intr(no) 必须清零暂存 tval，不能污染下一次异常。
// RV32/RV64 均可运行，按 difftest_state_size() 自动识别 XLEN。

#include <dlfcn.h>

#include <array>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <stdexcept>
#include <string>

namespace {

constexpr std::uint64_t kBase = 0x80000000ULL;

void check(bool condition, const std::string &message) {
  if (!condition) {
    throw std::runtime_error(message);
  }
}

template <typename Function> Function load(void *handle, const char *name) {
  dlerror();
  auto *symbol = dlsym(handle, name);
  if (const char *error = dlerror(); error != nullptr) {
    throw std::runtime_error(std::string{"missing symbol "} + name + ": " +
                             error);
  }
  return reinterpret_cast<Function>(symbol);
}

struct Ref {
  using StateSize = std::size_t (*)();
  using Memcpy = void (*)(std::uint64_t, void *, std::size_t, bool);
  using Regcpy = void (*)(void *, bool);
  using Csrcpy = void (*)(void *, bool);
  using Exec = void (*)(std::uint64_t);
  using Trap = void (*)(std::uint64_t, std::uint64_t, std::uint64_t);
  using RaiseIntr = void (*)(std::uint64_t);
  using Init = void (*)(int);
  using SetPlatform = void (*)(int);

  void *handle{};
  StateSize stateSize{};
  Memcpy memcpy{};
  Regcpy regcpy{};
  Csrcpy csrcpy{};
  Exec exec{};
  Trap trap{};
  RaiseIntr raiseIntr{};
  Init init{};
  SetPlatform setPlatform{};
  bool rv64{};

  explicit Ref(const char *path) {
    handle = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (handle == nullptr) {
      throw std::runtime_error(std::string{"dlopen failed: "} + dlerror());
    }
    stateSize = load<StateSize>(handle, "difftest_state_size");
    memcpy = load<Memcpy>(handle, "difftest_memcpy");
    regcpy = load<Regcpy>(handle, "difftest_regcpy");
    csrcpy = load<Csrcpy>(handle, "difftest_csrcpy");
    exec = load<Exec>(handle, "difftest_exec");
    trap = load<Trap>(handle, "difftest_trap");
    raiseIntr = load<RaiseIntr>(handle, "difftest_raise_intr");
    init = load<Init>(handle, "difftest_init");
    setPlatform = load<SetPlatform>(handle, "difftest_set_platform");
    rv64 = stateSize() == 33 * sizeof(std::uint64_t);
    const auto expected = 33 * (rv64 ? sizeof(std::uint64_t)
                                     : sizeof(std::uint32_t));
    check(stateSize() == expected, "unexpected difftest_state_size");
    setPlatform(0);
    init(0);
  }

  ~Ref() {
    if (handle != nullptr) {
      dlclose(handle);
    }
  }
};

// 与 REF 私有 CPU_state 无关，只用固定 ABI 的 32 个 GPR + PC。
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
  std::uint64_t mstatus{};
  std::uint64_t mtvec{};
  std::uint64_t mepc{};
  std::uint64_t mcause{};
};
struct Csr32 {
  std::uint32_t mstatus{};
  std::uint32_t mtvec{};
  std::uint32_t mepc{};
  std::uint32_t mcause{};
};
static_assert(sizeof(Csr32) == 16);
static_assert(sizeof(Csr64) == 32);

template <typename State, typename Csr, typename Word>
class Harness {
public:
  Harness(Ref &ref, std::size_t codeSize) : ref_(ref) {
    check(sizeof(State) == ref.stateSize(), "state width mismatch");
    std::array<std::uint8_t, 4096> code{};
    check(codeSize <= code.size(), "code too large");
    code_ = code;
  }

  void writeCode(std::size_t offset, std::initializer_list<std::uint32_t> words) {
    check(offset + words.size() * 4 <= code_.size(), "code overflow");
    std::size_t i = 0;
    for (const auto word : words) {
      std::memcpy(code_.data() + offset + i * 4, &word, 4);
      ++i;
    }
  }

  void load() {
    ref_.memcpy(kBase, code_.data(), code_.size(), true);
  }

  void setPC(Word pc) {
    State state{};
    state.pc = pc;
    ref_.regcpy(&state, true);
  }

  State readState() {
    State state{};
    ref_.regcpy(&state, false);
    return state;
  }

  Csr readCsr() {
    Csr csr{};
    ref_.csrcpy(&csr, false);
    return csr;
  }

  void writeCsr(Csr &csr) { ref_.csrcpy(&csr, true); }

private:
  Ref &ref_;
  std::array<std::uint8_t, 4096> code_{};
};

// csrr t0, mtval / csrr t0, stval / mret / csrw medeleg, t0
constexpr std::uint32_t kCsrrMtval = 0x343022f3U;
constexpr std::uint32_t kCsrrStval = 0x143022f3U;
constexpr std::uint32_t kMret = 0x30200073U;
constexpr std::uint32_t kLuiT0Two = 0x000022b7U;      // lui t0, 0x2 (bit13)
constexpr std::uint32_t kCsrwMedeleg = 0x30229073U;
constexpr std::uint32_t kAuipcT0 = 0x00000297U;       // auipc t0, 0
constexpr std::uint32_t kAddiT0Stvec = 0xfa028293U;  // t0 -> 0x80000040
constexpr std::uint32_t kCsrwStvec = 0x10529073U;
// 用 NAPOT 配置 PMP[0]=0x80000000..0x88000000（128MiB），
// 让 S 模式能取指并读取 stval。
constexpr std::uint32_t kLuiPmpNapt = 0x210002b7U;    // lui t0, 0x21000
constexpr std::uint32_t kAddiPmpNapt = 0xfff28293U;   // addi t0, t0, -1
constexpr std::uint32_t kCsrwPmpAddr0 = 0x3b029073U;
constexpr std::uint32_t kAddiPmpCfg = 0x01f00293U;    // addi t0, x0, 0x1f
constexpr std::uint32_t kCsrwPmpCfg0 = 0x3a029073U;

template <typename State, typename Csr, typename Word>
void runChecks(Ref &ref, Word expectedTval) {
  Harness<State, Csr, Word> h(ref, 0x200);
  // M 模式 trap 目标：读 mtval。
  h.writeCode(0x00, {kCsrrMtval, kMret});
  // S 模式 trap 目标：读 stval。
  h.writeCode(0x40, {kCsrrStval, kMret});
  // 配置 PMP、medeleg[13] 和 stvec，然后 mret 进入 S 模式。
  h.writeCode(0x80, {kLuiPmpNapt, kAddiPmpNapt, kCsrwPmpAddr0,
                     kAddiPmpCfg, kCsrwPmpCfg0, 0x00000013U /* nop */,
                     kLuiT0Two, kCsrwMedeleg, kAuipcT0,
                     kAddiT0Stvec, kCsrwStvec, kMret});
  h.load();

  constexpr Word kTrapPC = static_cast<Word>(kBase + 0x100);
  constexpr Word kMTrapEntry = static_cast<Word>(kBase + 0x00);
  constexpr Word kSTrapEntry = static_cast<Word>(kBase + 0x40);
  constexpr Word kMRetEntry = static_cast<Word>(kBase + 0x80);

  // 1) M 模式：difftest_trap 写入 mtval。
  Csr csr{};
  csr.mstatus = 0;
  csr.mtvec = kMTrapEntry;
  h.writeCsr(csr);
  ref.trap(2, kTrapPC, expectedTval);
  check(h.readState().pc == kMTrapEntry, "M trap did not enter mtvec");
  check(h.readCsr().mepc == kTrapPC, "M trap mepc mismatch");
  ref.exec(1);
  check(h.readState().gpr[5] == expectedTval,
        "difftest_trap did not update mtval");

  // 2) 旧接口必须清空 tval：先保留一个非零值，再走 raise_intr。
  ref.raiseIntr(2);
  ref.exec(1);
  check(h.readState().gpr[5] == 0,
        "difftest_raise_intr leaked the previous tval into mtval");

  // 3) 委托到 S：medeleg[13]=1，特权级 S 上注入 cause=13。
  csr = {};
  csr.mstatus = 0x800; // MPP=S
  csr.mtvec = kMTrapEntry;
  csr.mepc = kSTrapEntry; // mret 返回到 0x40，从 stvec 进入 S trap
  h.writeCsr(csr);
  h.setPC(kMRetEntry);
  ref.exec(12); // 设置 PMP/CSR 后 mret -> S @ 0x40
  const auto afterMret = h.readState();
  check(afterMret.pc == kSTrapEntry, "mret did not enter S trap entry");
  ref.trap(13, kTrapPC, expectedTval);
  const auto afterTrap = h.readState();
  if (afterTrap.pc != kSTrapEntry) {
    const auto csrNow = h.readCsr();
    throw std::runtime_error(
        "S trap did not enter stvec (pc=" +
        std::to_string(afterTrap.pc) + " mepc=" + std::to_string(csrNow.mepc) +
        " mcause=" + std::to_string(csrNow.mcause) +
        " mstatus=" + std::to_string(csrNow.mstatus) + ")");
  }
  ref.exec(1);
  const auto afterExec = h.readState();
  const auto stval = afterExec.gpr[5];
  if (stval != expectedTval) {
    throw std::runtime_error(
        "delegated difftest_trap did not update stval (got " +
        std::to_string(stval) + ", want " + std::to_string(expectedTval) +
        ", pc=" + std::to_string(afterExec.pc) + ")");
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
    constexpr std::uint64_t kTval64 = 0xdeadbeefcafebabeULL;
    if (ref.rv64) {
      runChecks<State64, Csr64, std::uint64_t>(ref, kTval64);
      std::cout << "[PASS] RV64 difftest_trap mtval/stval injection\n";
    } else {
      constexpr std::uint32_t kTval32 = 0xcafebabeU;
      runChecks<State32, Csr32, std::uint32_t>(ref, kTval32);
      std::cout << "[PASS] RV32 difftest_trap mtval/stval injection\n";
    }
    return 0;
  } catch (const std::exception &error) {
    std::cerr << "[FAIL] difftest_trap ABI: " << error.what() << '\n';
    return 1;
  }
}
