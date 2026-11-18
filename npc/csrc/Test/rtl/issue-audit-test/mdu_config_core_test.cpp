// 审计：MDU 未覆盖配置的算法正确性（乘法 shift-add + Booth/拆分/早退，
// 除法 non-restoring + radix4/iter2/早退）。
//
// 背景：仓库现有回归只执行过 MUL=shift_add/plain/iter1/split1/early_out=n
// 与 DIV=restoring/radix2/iter1（或 wallace/plain）；booth 编码、MUL_SPLIT、
// early-out、non-restoring、radix>2、iter>1 这些代码路径没有任何执行证据。
//
// 本测试用一份组合配置的 RTL：
//   MUL_IMPL=shift_add MUL_ENCODING=booth MUL_RADIX=4 MUL_ITER_BITS=4
//   MUL_SPLIT=2 MUL_EARLY_OUT=y
//   DIV_IMPL=nonrestoring DIV_RADIX=4 DIV_ITER_BITS=2 DIV_EARLY_OUT=y
// 用确定性随机向量与边界值对照软件参考实现。

#include "test_common.hpp"

#if defined(TEST_MUL_CORE)
#include "Vysyx_26030103_MULShiftAddCore.h"
using DUT = Vysyx_26030103_MULShiftAddCore;
#elif defined(TEST_DIV_CORE)
#include "Vysyx_26030103_DIVNonRestoringCore.h"
using DUT = Vysyx_26030103_DIVNonRestoringCore;
#else
#error "define TEST_MUL_CORE or TEST_DIV_CORE"
#endif

#include <cstdint>
#include <vector>
#include <verilated.h>

// 幅值乘法核的参考：64 位乘积的低 64 位。
static uint64_t reference_mul(uint32_t lhs, uint32_t rhs) {
  return static_cast<uint64_t>(lhs) * static_cast<uint64_t>(rhs);
}

struct DivResult {
  uint32_t quotient;
  uint32_t remainder;
};

// 幅值除法核的参考（除数非零）；除零按核约定：商全 1、余数为被除数。
static DivResult reference_div(uint32_t dividend, uint32_t divisor) {
  if (divisor == 0) {
    return {0xFFFFFFFFU, dividend};
  }
  return {dividend / divisor, dividend % divisor};
}

static std::vector<std::pair<uint32_t, uint32_t>> make_vectors() {
  std::vector<std::pair<uint32_t, uint32_t>> vectors = {
      {0U, 0U},           {0U, 0xFFFFFFFFU},  {1U, 1U},
      {0xFFFFFFFFU, 1U},  {0xFFFFFFFFU, 0xFFFFFFFFU}, {0x80000000U, 2U},
      {0x7FFFFFFFU, 3U},  {0x00010000U, 0x00010000U}, {0xF0000000U, 0x10U}};
  uint32_t lfsr = 0xC0FFEE01U;
  for (int index = 0; index < 60; ++index) {
    lfsr = lfsr * 1664525U + 1013904223U;
    const uint32_t a = lfsr;
    lfsr = lfsr * 1664525U + 1013904223U;
    vectors.emplace_back(a, lfsr);
  }
  return vectors;
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
#if defined(TEST_MUL_CORE)
  return run_test("shift-add MUL core (booth/split/early-out) matches reference",
                  [] {
#else
  return run_test(
      "non-restoring DIV core (radix4/iter2/early-out) matches reference", [] {
#endif
    DUT dut;
    dut.IO_Req_valid = 0;
#if defined(TEST_MUL_CORE)
    dut.IO_Req_bits_LHSMagnitude = 0;
    dut.IO_Req_bits_RHSMagnitude = 0;
#else
    dut.IO_Req_bits_DividendMagnitude = 0;
    dut.IO_Req_bits_DivisorMagnitude = 0;
#endif
    dut.IO_Resp_ready = 1;
    dut.IO_Flush = 0;
    reset(dut);

    for (const auto &[a, b] : make_vectors()) {
      dut.IO_Req_valid = 1;
#if defined(TEST_MUL_CORE)
      dut.IO_Req_bits_LHSMagnitude = a;
      dut.IO_Req_bits_RHSMagnitude = b;
#else
      dut.IO_Req_bits_DividendMagnitude = a;
      dut.IO_Req_bits_DivisorMagnitude = b;
#endif
      dut.eval();
      check(dut.IO_Req_ready, "MDU core refused a request");
      tick(dut);
      dut.IO_Req_valid = 0;

      bool got = false;
      for (int cycle = 0; cycle < 200 && !got; ++cycle) {
        dut.eval();
        if (dut.IO_Resp_valid) {
          got = true;
        } else {
          tick(dut);
        }
      }
      check(got, "MDU core never returned a result");
#if defined(TEST_MUL_CORE)
      const uint64_t product = dut.IO_Resp_bits_Product;
#else
      const uint32_t quotient = dut.IO_Resp_bits_Quotient;
      const uint32_t remainder = dut.IO_Resp_bits_Remainder;
#endif
      tick(dut); // 消费响应
      dut.eval();

#if defined(TEST_MUL_CORE)
      const uint64_t expected = reference_mul(a, b);
      check(product == expected,
            "wrong product for 0x" + std::to_string(a) + " * 0x" +
                std::to_string(b) + ": got 0x" + std::to_string(product) +
                ", expected 0x" + std::to_string(expected));
#else
      const DivResult expected = reference_div(a, b);
      check(quotient == expected.quotient && remainder == expected.remainder,
            "wrong division for 0x" + std::to_string(a) + " / 0x" +
                std::to_string(b) + ": got q=0x" + std::to_string(quotient) +
                " r=0x" + std::to_string(remainder) + ", expected q=0x" +
                std::to_string(expected.quotient) + " r=0x" +
                std::to_string(expected.remainder));
#endif
    }
    dut.final();
  });
}
