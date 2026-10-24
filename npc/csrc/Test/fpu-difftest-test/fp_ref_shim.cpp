// 浮点 DiffTest REF 包装器，用于两条负向路径：
//
//   DIFFTEST_SHIM_CORRUPT_FP：完整转发真实 REF，但在 NPC 读取 REF 状态时
//     篡改一个已经写过的 f 寄存器，验证 NPC 确实逐 f 寄存器比较，而不是
//     只比较整数。
//
//   DIFFTEST_SHIM_LEGACY_FP：转发所有整数/内存/trap 符号，但故意不导出
//     difftest_fp_*。这模拟还没有浮点 ABI 的旧 REF；NPC 必须明确告警并
//     跳过 freg/fcsr 比较，整数比对继续，不能误报分叉。
//
// 用法：DIFFTEST_REAL_REF=/path/to/real-ref.so NPC ... --diff <this-shim.so>
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
  dlerror();
  return reinterpret_cast<Fn>(dlsym(RealHandle(), name));
}

} // namespace

extern "C" {

std::size_t difftest_state_size() {
  return Get<std::size_t (*)()>("difftest_state_size")();
}

std::size_t difftest_csr_state_size() {
  return Get<std::size_t (*)()>("difftest_csr_state_size")();
}

void difftest_memcpy(std::uint64_t addr, void *buf, std::size_t n,
                     bool direction) {
  Get<void (*)(std::uint64_t, void *, std::size_t, bool)>(
      "difftest_memcpy")(addr, buf, n, direction);
}

void difftest_regcpy(void *dut, bool direction) {
  Get<void (*)(void *, bool)>("difftest_regcpy")(dut, direction);
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

#ifndef DIFFTEST_SHIM_LEGACY_FP

// 与 nemu/include/difftest-def.h 的版本 1 布局一致。
struct FPState {
  std::uint64_t freg[32];
  std::uint64_t fcsr;
};

static_assert(sizeof(FPState) == 33 * sizeof(std::uint64_t));

std::uint32_t difftest_fp_abi_version() {
  return Get<std::uint32_t (*)()>("difftest_fp_abi_version")();
}

std::size_t difftest_fp_state_size() {
  return Get<std::size_t (*)()>("difftest_fp_state_size")();
}

void difftest_fp_regcpy(void *dut, bool direction) {
  Get<void (*)(void *, bool)>("difftest_fp_regcpy")(dut, direction);
#ifdef DIFFTEST_SHIM_CORRUPT_FP
  // direction=false 表示 NPC 正在读取 REF。只在 f2 已经写入后篡改低位，
  // 使第一条真正的浮点结果退休时出现明确、可定位的 FPR 差异。
  if (!direction) {
    auto *state = static_cast<FPState *>(dut);
    if ((state->freg[2] & 0xffffffffULL) != 0) {
      state->freg[2] ^= 1ULL;
    }
  }
#endif
}

#endif // DIFFTEST_SHIM_LEGACY_FP

} // extern "C"
