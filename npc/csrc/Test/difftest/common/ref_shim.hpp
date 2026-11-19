// DiffTest REF 转发 shim 的公共加载代码。
//
// 所有“把一个真实 NEMU REF 包装成带缺陷 REF”的测试 shim 都要先从
// DIFFTEST_REAL_REF 指定的 .so 里取符号，再逐个转发。这里只放加载/取符号
// 的公共部分；每个 shim 自己保留 extern "C" 符号列表，因为“导出哪些符号、
// 不导出哪些符号”正是对应负向用例的规格。
#pragma once

#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <dlfcn.h>

namespace npc_test::ref_shim {

// 打开 DIFFTEST_REAL_REF；未设置或打不开时直接以退出码 2 结束。
inline void *RealHandle() {
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

// 取必需符号；缺失即视为 shim 配置错误。
template <typename Fn> Fn Get(const char *name) {
  void *symbol = dlsym(RealHandle(), name);
  if (symbol == nullptr) {
    std::fprintf(stderr, "dlsym(%s) failed: %s\n", name, dlerror());
    std::exit(2);
  }
  return reinterpret_cast<Fn>(symbol);
}

// 取可选符号；缺失时返回 nullptr，由调用方决定降级行为。
template <typename Fn> Fn GetOptional(const char *name) {
  dlerror();
  return reinterpret_cast<Fn>(dlsym(RealHandle(), name));
}

// REF 的 33*XLEN 状态大小，只查一次。
inline std::size_t RealStateSize() {
  static const std::size_t size =
      Get<std::size_t (*)()>("difftest_state_size")();
  return size;
}

} // namespace npc_test::ref_shim
