// 主机侧 DiffTest 测试共用的 REF 共享库加载器。
//
// “直接 dlopen NEMU REF，不依赖 NPC RTL”这类回归都要做同样的事：打开 .so，
// 逐符号取函数指针，缺符号时明确失败。各测试自己的断言风格（抛异常 /
// 累计 failures）差异很大，所以这里只统一加载部分。
#pragma once

#include <dlfcn.h>

#include <stdexcept>
#include <string>

namespace npc_test {

class RefLibrary {
public:
  explicit RefLibrary(const char *path) {
    handle_ = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (handle_ == nullptr) {
      throw std::runtime_error(std::string{"dlopen failed: "} + dlerror());
    }
  }

  ~RefLibrary() {
    if (handle_ != nullptr) {
      dlclose(handle_);
    }
  }

  RefLibrary(const RefLibrary &) = delete;
  RefLibrary &operator=(const RefLibrary &) = delete;

  // 取必需符号；缺失时抛异常，避免用空指针继续跑。
  template <typename Function> Function symbol(const char *name) const {
    dlerror();
    void *entry = dlsym(handle_, name);
    if (const char *error = dlerror(); error != nullptr) {
      throw std::runtime_error(std::string{"missing symbol "} + name + ": " +
                               error);
    }
    return reinterpret_cast<Function>(entry);
  }

private:
  void *handle_{};
};

} // namespace npc_test
