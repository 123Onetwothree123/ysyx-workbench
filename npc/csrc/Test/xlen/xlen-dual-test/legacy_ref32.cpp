#include <cstddef>
#include <cstdint>

// 模拟只支持旧 32 位状态 ABI 的 REF：
//   * difftest_state_size() = 32 * 4 + 4 = 132
//   * difftest_csr_state_size() = 4 * 4 = 16
// RV64 DUT 必须在 difftest_init/首条指令之前按大小拒绝它，而不是把
// 264 字节状态截断成 132 字节后继续执行。
extern "C" std::size_t difftest_state_size() { return 33 * sizeof(std::uint32_t); }

extern "C" std::size_t difftest_csr_state_size() {
    return 4 * sizeof(std::uint32_t);
}

extern "C" void difftest_memcpy(std::uint64_t, void *, std::size_t, bool) {}

extern "C" void difftest_regcpy(void *, bool) {}

extern "C" void difftest_csrcpy(void *, bool) {}
