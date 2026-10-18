// 审计：压缩树乘法核在非默认 GPC 配置下的正确性。
//
// 疑点（来自静态审计）：MULCompressionCore 的 FinalSum 只取两行
//   if (Rows.length == 1) Rows.head else (Rows(0) +& Rows(1))
// 而 ColumnsToRows 会生成任意多行。若压缩调度模型与数据通路不一致、
// 或某个合法 GPC 配置下压缩不到两行，结果会被静默丢弃（无 require）。
//
// Kconfig 允许 MUL_COMPRESSOR_INPUTS ∈ [3,64] 与任意输出偏移，因此
// “4:3 压缩器”是合法配置。本测试：
//   1. 在配置的 RTL 上例化对应的 MUL 核（wallace/dadda）；
//   2. 用确定性伪随机向量与 64 位参考乘法对比；
//   3. 覆盖 0、1、全 1、以及大量随机幅值。
//
// 由 Makefile 用不同的 RTL 快照与宏编译：
//   -DGPC_DEFAULT   默认 3:2 压缩器（对照）
//   -DGPC_ALT       4:3 压缩器（MUL_COMPRESSOR_INPUTS=4, OUTPUTS=3, OFFSETS=0,1,2）

#include "test_common.hpp"

#if defined(GPC_DEFAULT) || defined(GPC_ALT)
#include "Vysyx_26030103_MULWallaceCore.h"
using DUT = Vysyx_26030103_MULWallaceCore;
static const char *kGpcName =
#if defined(GPC_ALT)
    "wallace 4:3@[0,1,2]";
#else
    "wallace 3:2@[0,1]";
#endif
#else
#error "define GPC_DEFAULT or GPC_ALT"
#endif

#include <cstdint>
#include <vector>
#include <verilated.h>

// 纯组合的参考乘法（64 位低半）。
static uint64_t reference_product(uint32_t lhs, uint32_t rhs) {
  return static_cast<uint64_t>(lhs) * static_cast<uint64_t>(rhs);
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  const std::string name =
      std::string("compression-tree MUL core matches the reference [") +
      kGpcName + "]";
  return run_test(name.c_str(), [] {
    DUT dut;
    dut.IO_Req_valid = 0;
    dut.IO_Req_bits_LHSMagnitude = 0;
    dut.IO_Req_bits_RHSMagnitude = 0;
    dut.IO_Resp_ready = 1;
    dut.IO_Flush = 0;
    reset(dut);

    // 向量集：边界值 + 确定性 LFSR 随机值。
    std::vector<std::pair<uint32_t, uint32_t>> vectors = {
        {0U, 0U},       {0U, 0xffffffffU}, {1U, 1U},
        {0xffffffffU, 0xffffffffU},        {0x80000000U, 2U},
        {0x7fffffffU, 0x7fffffffU},        {0x0000ffffU, 0x00010001U}};
    uint32_t lfsr = 0x12345678U;
    for (int index = 0; index < 60; ++index) {
      lfsr = lfsr * 1664525U + 1013904223U;
      const uint32_t lhs = lfsr;
      lfsr = lfsr * 1664525U + 1013904223U;
      vectors.emplace_back(lhs, lfsr);
    }

    // 流水核：可以背靠背送入，但这里逐条发送并等待结果，
    // 便于定位不一致的向量。
    for (const auto &[lhs, rhs] : vectors) {
      dut.IO_Req_valid = 1;
      dut.IO_Req_bits_LHSMagnitude = lhs;
      dut.IO_Req_bits_RHSMagnitude = rhs;
      dut.eval();
      check(dut.IO_Req_ready, "MUL core refused a request");
      tick(dut);
      dut.IO_Req_valid = 0;

      bool got = false;
      uint64_t product = 0;
      for (int cycle = 0; cycle < 64 && !got; ++cycle) {
        dut.eval();
        if (dut.IO_Resp_valid) {
          product = dut.IO_Resp_bits_Product;
          got = true;
        } else {
          tick(dut);
        }
      }
      check(got, "MUL core never returned a result");
      tick(dut); // 消费响应
      dut.eval();
      const uint64_t expected = reference_product(lhs, rhs);
      check(product == expected,
            "wrong product for 0x" + std::to_string(lhs) + " * 0x" +
                std::to_string(rhs) + ": got 0x" + std::to_string(product) +
                ", expected 0x" + std::to_string(expected));
    }
    dut.final();
  });
}
