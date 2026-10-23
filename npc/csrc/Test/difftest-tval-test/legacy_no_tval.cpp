// 模拟缺少 difftest_trap 扩展的旧 REF：把真实 REF 的旧 ABI 符号逐个转发，
// 但故意不导出 difftest_trap。用来验证 NPC 会明确告警并在非零 tval 的
// trap 上拒绝继续比对，而不是静默使用旧行为。
#include <dlfcn.h>

#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <string>

namespace {

void *real() {
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

template <typename Fn> Fn get(const char *name) {
  return reinterpret_cast<Fn>(dlsym(real(), name));
}

} // namespace

extern "C" {

std::size_t difftest_state_size() {
  return get<std::size_t (*)()>("difftest_state_size")();
}

std::size_t difftest_csr_state_size() {
  return get<std::size_t (*)()>("difftest_csr_state_size")();
}

bool difftest_memory_range_supported(std::uint32_t addr, std::size_t n) {
  return get<bool (*)(std::uint32_t, std::size_t)>(
      "difftest_memory_range_supported")(addr, n);
}

void difftest_memcpy(std::uint32_t addr, void *buf, std::size_t n,
                     bool direction) {
  get<void (*)(std::uint32_t, void *, std::size_t, bool)>("difftest_memcpy")(
      addr, buf, n, direction);
}

void difftest_regcpy(void *dut, bool direction) {
  get<void (*)(void *, bool)>("difftest_regcpy")(dut, direction);
}

void difftest_csrcpy(void *dut, bool direction) {
  get<void (*)(void *, bool)>("difftest_csrcpy")(dut, direction);
}

void difftest_exec(std::uint64_t n) {
  get<void (*)(std::uint64_t)>("difftest_exec")(n);
}

void difftest_raise_intr(std::uint64_t no) {
  get<void (*)(std::uint64_t)>("difftest_raise_intr")(no);
}

void difftest_init(int port) {
  get<void (*)(int)>("difftest_init")(port);
}

void difftest_set_platform(int ysyxsoc) {
  get<void (*)(int)>("difftest_set_platform")(ysyxsoc);
}

} // extern "C"
