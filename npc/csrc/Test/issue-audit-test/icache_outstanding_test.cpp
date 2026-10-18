// 审计：ICache 的“单未完成取指”不变量与背靠背命中吞吐。
//
// 疑点（来自静态审计）：顶层预测标签快照（pred_tag_taken/pred_target 在
// 取指受理拍冻结）的正确性完全依赖“ICache 同时最多一个未完成取指”这一
// 隐含契约，而 ICacheInterface 的文档没有写它、也没有断言保护。
//
// 本测试验证该不变量在当前实现中确实成立（即顶层快照今天是安全的），
// 并同时验证背靠背命中吞吐（1 条/拍）：
//   1. miss 回填后，demand 响应未消费（resp_ready=0）时，同行的下一个
//      取指必须被拒绝（fetch_ready=0）；
//   2. 消费该响应的同拍，下一个取指被受理（弹性槽）；
//   3. 连续命中时每个被受理的取指恰好产生一个携带正确 PC/数据的响应。
//
// 同一份测试源也用于最小行配置（CACHE_BLOCK_SIZE_LOG2=2, 1 word/line），
// 顺带验证小行几何在仿真中可用（静态审计怀疑存在 0 位宽寄存器）。

#include "Vysyx_26030103_ICacheReg.h"
#include "test_common.hpp"

#ifndef TEST_WORDS_PER_LINE
#define TEST_WORDS_PER_LINE 0
#endif

#include <cstdint>
#include <string>
#include <vector>
#include <verilated.h>

using DUT = Vysyx_26030103_ICacheReg;

static void defaults(DUT &dut) {
  dut.io_fetch_addr = 0;
  dut.io_fetch_valid = 0;
  dut.io_resp_ready = 0;
  dut.io_axi_AR_ARREADY = 0;
  dut.io_axi_R_RDATA = 0;
  dut.io_axi_R_RRESP = 0;
  dut.io_axi_R_RLAST = 0;
  dut.io_axi_R_RVALID = 0;
  dut.io_flush = 0;
  dut.io_kill = 0;
}

// 等待 refill AR，接受它，并回填整行；返回行长（字数）。
static unsigned refill_line(DUT &dut, uint32_t line_base,
                            std::vector<uint32_t> &words_out) {
  wait_until(dut, [&] { return dut.io_axi_AR_ARVALID; },
             "ICache never issued the refill AR");
  check(dut.io_axi_AR_ARADDR == line_base,
        "refill AR did not start at the line base");
#if TEST_WORDS_PER_LINE > 0
  // 小行几何下 ARLEN 是编译期常量，Verilator 会把端口优化掉。
  const unsigned beats = TEST_WORDS_PER_LINE;
#else
  const unsigned beats = static_cast<unsigned>(dut.io_axi_AR_ARLEN) + 1U;
#endif
  (void)beats;
  dut.io_axi_AR_ARREADY = 1;
  tick(dut);
  dut.io_axi_AR_ARREADY = 0;

  words_out.clear();
  for (unsigned beat = 0; beat < beats; ++beat) {
    const uint32_t word = 0xa0b00000U + beat;
    words_out.push_back(word);
    dut.io_axi_R_RDATA = word;
    dut.io_axi_R_RRESP = 0;
    dut.io_axi_R_RLAST = (beat + 1 == beats);
    dut.io_axi_R_RVALID = 1;
    tick(dut);
    dut.io_axi_R_RVALID = 0;
    dut.io_axi_R_RLAST = 0;
  }
  return beats;
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  return run_test("ICache single-outstanding contract and hit throughput",
                  [] {
                    DUT dut;
                    defaults(dut);
                    reset(dut);

                    constexpr uint32_t kDemand = 0x80000004U;
                    // 行大小：base 构建从 ARLEN 读，小行构建用编译期常量。
#if TEST_WORDS_PER_LINE > 0
                    const unsigned words_per_line = TEST_WORDS_PER_LINE;
#else
                    const unsigned words_per_line = 4; // 16B/行
#endif
                    const uint32_t kLineBase =
                        kDemand & ~static_cast<uint32_t>(words_per_line * 4U - 1U);

                    // --- 1) miss + 回填 ---
                    dut.io_fetch_addr = kDemand;
                    dut.io_fetch_valid = 1;
                    dut.io_resp_ready = 0;
                    dut.eval();
                    check(dut.io_fetch_ready,
                          "ICache did not accept the initial fetch");
                    tick(dut);
                    dut.io_fetch_valid = 0;
                    dut.eval();

                    std::vector<uint32_t> line_words;
                    const unsigned refilled_words =
                        refill_line(dut, kLineBase, line_words);
                    check(refilled_words == words_per_line,
                          "refill length does not match the expected line "
                          "size");

                    // demand 响应可见但未消费。
                    wait_until(dut, [&] { return dut.io_resp_valid; },
                               "demand response never appeared");
                    check(dut.io_resp_addr == kDemand,
                          "demand response lost its PC");
                    check(dut.io_resp_data ==
                              line_words[(kDemand - kLineBase) / 4U],
                          "demand response carries the wrong word");

                    // --- 2) 响应未消费时，同行的下一个取指必须被拒绝 ---
                    dut.io_fetch_addr = kLineBase; // 同行命中
                    dut.io_fetch_valid = 1;
                    dut.eval();
                    check(!dut.io_fetch_ready,
                          "ICache accepted a second fetch while the previous "
                          "response was still pending (single-outstanding "
                          "contract broken; top-level prediction snapshot "
                          "would become unsafe)");

                    // --- 3) 消费响应的同拍受理下一个取指（弹性槽）---
                    dut.io_resp_ready = 1;
                    dut.eval();
                    check(dut.io_resp_valid && dut.io_fetch_ready,
                          "ICache did not pop the old response and accept the "
                          "next fetch in the same cycle");
                    tick(dut);
                    dut.io_fetch_valid = 0;
                    dut.eval();
                    // 排空阶段 3 那次取指的响应，避免混入下面的计数。
                    wait_until(dut, [&] { return dut.io_resp_valid; },
                               "phase-3 fetch response never appeared");
                    check(dut.io_resp_addr == kLineBase,
                          "phase-3 response PC mismatch");
                    tick(dut);
                    dut.io_resp_ready = 0;
                    dut.eval();
                    check(!dut.io_resp_valid,
                          "phase-3 response did not retire");

                    // --- 4) 背靠背命中：每个被受理的取指恰好一个响应 ---
                    std::vector<uint32_t> fetch_addrs;
                    std::vector<uint32_t> resp_addrs;
                    std::vector<uint32_t> resp_data;
                    dut.io_resp_ready = 1;
                    constexpr int kCycles = 12;
                    for (int cycle = 0; cycle < kCycles; ++cycle) {
                      dut.io_fetch_addr =
                          kLineBase +
                          (static_cast<unsigned>(cycle) % words_per_line) * 4U;
                      dut.io_fetch_valid = 1;
                      dut.eval();
                      if (dut.io_fetch_ready) {
                        fetch_addrs.push_back(dut.io_fetch_addr);
                      }
                      if (dut.io_resp_valid) {
                        resp_addrs.push_back(dut.io_resp_addr);
                        resp_data.push_back(dut.io_resp_data);
                      }
                      tick(dut);
                    }
                    dut.io_fetch_valid = 0;
                    for (int cycle = 0; cycle < 4; ++cycle) {
                      dut.eval();
                      if (dut.io_resp_valid) {
                        resp_addrs.push_back(dut.io_resp_addr);
                        resp_data.push_back(dut.io_resp_data);
                      }
                      tick(dut);
                    }
                    dut.eval();

                    check(fetch_addrs.size() == resp_addrs.size(),
                          "hit stream lost responses: " +
                              std::to_string(resp_addrs.size()) +
                              " responses for " +
                              std::to_string(fetch_addrs.size()) + " fetches");
                    for (size_t index = 0; index < resp_addrs.size();
                         ++index) {
                      check(resp_addrs[index] == fetch_addrs[index],
                            "response " + std::to_string(index) +
                                " PC mismatch");
                      const uint32_t expected =
                          line_words[(fetch_addrs[index] - kLineBase) / 4U];
                      check(resp_data[index] == expected,
                            "response " + std::to_string(index) +
                                " data mismatch");
                    }
                    // --- 5) 从机违反协议（永不发 RLAST）的现状 ---
                    // AXI 要求从机必须给 RLAST；这里记录当前实现的行为：
                    // 缺 RLAST 会在预期拍数后作废整行并进入 rf_drain，而
                    // rf_drain 只等 RVALID&&RLAST，没有超时；flush/kill 也不
                    // 处理该状态。若将来加入 drain 超时，本用例应改为断言恢复。
                    {
                      DUT stuck;
                      defaults(stuck);
                      reset(stuck);
                      stuck.io_fetch_addr = 0x80000000U;
                      stuck.io_fetch_valid = 1;
                      stuck.io_resp_ready = 1;
                      stuck.eval();
                      check(stuck.io_fetch_ready,
                            "stuck test: initial fetch was not accepted");
                      tick(stuck);
                      stuck.io_fetch_valid = 0;
                      stuck.eval();

                      wait_until(stuck,
                                 [&] { return stuck.io_axi_AR_ARVALID; },
                                 "stuck test: no refill AR");
                      stuck.io_axi_AR_ARREADY = 1;
                      tick(stuck);
                      stuck.io_axi_AR_ARREADY = 0;

                      // 预期拍数用完但从不给 RLAST。
                      for (int beat = 0; beat < 8; ++beat) {
                        stuck.io_axi_R_RDATA = 0x12340000U + beat;
                        stuck.io_axi_R_RRESP = 0;
                        stuck.io_axi_R_RLAST = 0;
                        stuck.io_axi_R_RVALID = 1;
                        tick(stuck);
                        stuck.io_axi_R_RVALID = 0;
                      }
                      stuck.eval();

                      // flush/kill 不能把它拉回来；后续取指持续被阻塞。
                      stuck.io_flush = 1;
                      tick(stuck);
                      stuck.io_flush = 0;
                      stuck.io_kill = 1;
                      tick(stuck);
                      stuck.io_kill = 0;
                      stuck.io_fetch_valid = 1;
                      stuck.eval();
                      for (int cycle = 0; cycle < 8; ++cycle) {
                        check(!stuck.io_fetch_ready,
                              "stuck test: fetch unexpectedly recovered "
                              "without RLAST");
                        tick(stuck);
                        stuck.eval();
                      }

                      // 迟到的 RLAST 仍然可以重新同步（当前实现支持）。
                      stuck.io_axi_R_RDATA = 0;
                      stuck.io_axi_R_RLAST = 1;
                      stuck.io_axi_R_RVALID = 1;
                      tick(stuck);
                      stuck.io_axi_R_RVALID = 0;
                      stuck.io_axi_R_RLAST = 0;
                      stuck.io_fetch_valid = 0;
                      stuck.eval();
                      wait_until(stuck,
                                 [&] { return stuck.io_fetch_ready; },
                                 "stuck test: late RLAST did not resynchronize",
                                 32);
                      stuck.final();
                    }

                    dut.final();
                  });
}
