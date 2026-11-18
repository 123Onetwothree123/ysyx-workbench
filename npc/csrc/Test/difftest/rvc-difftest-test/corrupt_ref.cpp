// 故意制造一次 DiffTest 分叉的 REF 包装器。
//
// 除 difftest_regcpy 外，所有符号都原样转发给真实 NEMU REF。当地址方向为
// DIFFTEST_TO_DUT（NPC 读取 REF 状态）时，把 x5/t0 加 1，保证第一条退休
// 指令后必然出现 GPR 差异。用于验证 NPC 会打印“第一次分叉报告”，而不是
// 只返回一个模糊的失败码。
//
// 用法：DIFFTEST_REAL_REF=/path/to/real-ref.so NPC ... --diff corrupt-ref.so
#include <dlfcn.h>

#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstdlib>

namespace {

void *RealHandle() {
  static void *handle = [] {
    const char *path = std::getenv("DIFFTEST_REAL_REF");
    if (path == nullptr) {
      std::fprintf(stderr, "DIFFTEST_REAL_REF is not set\n");
      std::exit(2);
    }
    void *h = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (h == nullptr) {
      std::fprintf(stderr, "dlopen failed: %s\n", dlerror());
      std::exit(2);
    }
    return h;
  }();
  return handle;
}

template <typename Fn> Fn Get(const char *name) {
  void *symbol = dlsym(RealHandle(), name);
  if (symbol == nullptr) {
    std::fprintf(stderr, "dlsym(%s) failed: %s\n", name, dlerror());
    std::exit(2);
  }
  return reinterpret_cast<Fn>(symbol);
}

template <typename Fn> Fn GetOptional(const char *name) {
  return reinterpret_cast<Fn>(dlsym(RealHandle(), name));
}

std::size_t RealStateSize() {
  static const std::size_t size =
      Get<std::size_t (*)()>("difftest_state_size")();
  return size;
}

} // namespace

extern "C" {

std::size_t difftest_state_size() { return RealStateSize(); }

std::size_t difftest_csr_state_size() {
  return Get<std::size_t (*)()>("difftest_csr_state_size")();
}

bool difftest_memory_range_supported(std::uint32_t addr, std::size_t n) {
  return Get<bool (*)(std::uint32_t, std::size_t)>(
      "difftest_memory_range_supported")(addr, n);
}

void difftest_memcpy(std::uint64_t addr, void *buf, std::size_t n,
                     bool direction) {
  Get<void (*)(std::uint64_t, void *, std::size_t, bool)>(
      "difftest_memcpy")(addr, buf, n, direction);
}

void difftest_regcpy(void *dut, bool direction) {
  Get<void (*)(void *, bool)>("difftest_regcpy")(dut, direction);
  // 0 == DIFFTEST_TO_DUT：NPC 正在读 REF 状态。此时注入一个稳定的 GPR
  // 差异；TO_REF 的初始化同步不受影响。
  if (!direction) {
    const std::size_t state_size = RealStateSize();
    if (state_size == 33 * sizeof(std::uint32_t)) {
      static_cast<std::uint32_t *>(dut)[5] += 1;
    } else if (state_size == 33 * sizeof(std::uint64_t)) {
      static_cast<std::uint64_t *>(dut)[5] += 1;
    } else {
      std::fprintf(stderr, "unexpected difftest state size: %zu\n", state_size);
      std::exit(2);
    }
  }
}

void difftest_csrcpy(void *dut, bool direction) {
  Get<void (*)(void *, bool)>("difftest_csrcpy")(dut, direction);
}

void difftest_exec(std::uint64_t n) {
  Get<void (*)(std::uint64_t)>("difftest_exec")(n);
}

void difftest_raise_intr(std::uint64_t no) {
  Get<void (*)(std::uint64_t)>("difftest_raise_intr")(no);
}

void difftest_trap(std::uint64_t no, std::uint64_t epc, std::uint64_t tval) {
  Get<void (*)(std::uint64_t, std::uint64_t, std::uint64_t)>(
      "difftest_trap")(no, epc, tval);
}

void difftest_init(int port) {
  Get<void (*)(int)>("difftest_init")(port);
}

void difftest_set_platform(int ysyxsoc) {
  Get<void (*)(int)>("difftest_set_platform")(ysyxsoc);
}

void difftest_get_last_instruction(std::uint64_t *pc, std::uint64_t *inst) {
  using Fn = void (*)(std::uint64_t *, std::uint64_t *);
  if (auto fn = GetOptional<Fn>("difftest_get_last_instruction")) {
    fn(pc, inst);
  } else {
    *pc = 0;
    *inst = 0;
  }
}

bool difftest_get_csr(std::uint32_t csr, std::uint64_t *value) {
  using Fn = bool (*)(std::uint32_t, std::uint64_t *);
  if (auto fn = GetOptional<Fn>("difftest_get_csr")) {
    return fn(csr, value);
  }
  return false;
}

} // extern "C"
