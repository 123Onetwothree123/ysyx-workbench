// 审计：DCache 在回填（refill）进行中的交互路径——hit-under-refill、
// 未返回字拒绝、其他 set 完整命中、以及 flush 穿越回填。
//
// 背景：非旁路 SRAM（sram_nobypass）此前在仓库里没有任何测试；响应槽修复
// 后只覆盖了"已安装行的背靠背命中"。本测试把寄存器版 dcache_test 的关键
// 场景搬到三种存储介质上（reg / sram 旁路 / sram 非旁路）：
//   1. 关键字早返回（early restart）后，demand 响应未消费时不得放行新请求；
//   2. 消费 demand 响应后，已返回的字可以 hit-under-refill，未返回的字必须拒绝；
//   3. 其他 set 的完整命中在回填期间放行；
//   4. 回填途中 flush：原 demand 仍收到响应，但该行不得安装。
//
// 编译宏与 dcache_response_slot_test 相同（TEST_STORAGE_{REG,SRAM,NOBYPASS}）。

#include "test_common.hpp"
#if defined(TEST_STORAGE_REG)
#include "Vysyx_26030103_DCacheReg.h"
using DUT = Vysyx_26030103_DCacheReg;
#else
#include "Vysyx_26030103_DCacheSRAM.h"
using DUT = Vysyx_26030103_DCacheSRAM;
#endif

#include <array>
#include <cstdint>
#include <string>
#include <verilated.h>

static void defaults(DUT &dut) {
  dut.io_req_valid = 0;
  dut.io_req_bits_addr = 0;
  dut.io_req_bits_WidthSelect = 2;
  dut.io_req_bits_signed = 0;
  dut.io_resp_ready = 1;
  dut.io_StoreValid = 0;
  dut.io_StoreResp = 0;
  dut.io_StoreAddr = 0;
  dut.io_StoreData = 0;
  dut.io_StoreStrb = 0;
  dut.io_AXI_AR_ARREADY = 0;
  dut.io_AXI_R_RDATA = 0;
  dut.io_AXI_R_RRESP = 0;
  dut.io_AXI_R_RLAST = 0;
  dut.io_AXI_R_RVALID = 0;
  dut.io_flush = 0;
}

using Words = std::array<uint32_t, 4>;

// 发起一次 miss；响应保持未消费（resp_ready=0），由调用方显式消费。
static unsigned start_miss(DUT &dut, uint32_t address) {
  dut.io_resp_ready = 0;
  dut.io_req_valid = 1;
  dut.io_req_bits_addr = address;
  dut.eval();
  check(dut.io_req_ready, "DCache did not accept the demand miss");
  tick(dut);
  dut.io_req_valid = 0;

  wait_until(dut, [&] { return dut.io_AXI_AR_ARVALID; },
             "DCache never issued the refill AR");
  const unsigned beats = static_cast<unsigned>(dut.io_AXI_AR_ARLEN) + 1U;
  dut.io_AXI_AR_ARREADY = 1;
  tick(dut);
  dut.io_AXI_AR_ARREADY = 0;
  return beats;
}

static void send_beat(DUT &dut, uint32_t data, bool last) {
  dut.io_AXI_R_RDATA = data;
  dut.io_AXI_R_RRESP = 0;
  dut.io_AXI_R_RLAST = last;
  dut.io_AXI_R_RVALID = 1;
  tick(dut);
  dut.io_AXI_R_RVALID = 0;
  dut.io_AXI_R_RLAST = 0;
  dut.eval();
}

// 完整回填一行（demand 响应交给调用方处理）。
static void finish_refill(DUT &dut, const Words &words, unsigned from_beat) {
  for (unsigned beat = from_beat; beat < words.size(); ++beat) {
    send_beat(dut, words[beat], beat + 1 == words.size());
  }
}

static void drain_to_idle(DUT &dut) {
  for (int cycle = 0; cycle < 16; ++cycle) {
    dut.io_resp_ready = 1;
    dut.eval();
    if (dut.io_req_ready && !dut.io_resp_valid && !dut.io_axi_active) {
      return;
    }
    tick(dut);
  }
  check(false, "DCache did not drain back to idle");
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  return run_test("DCache refill interaction (early restart / hit-under-refill)",
                  [] {
    DUT dut;
    defaults(dut);
    reset(dut);

    // --- 0) 先安装另一条 set 的行 L0，供“其他 set 完整命中”使用 ---
    constexpr uint32_t kLine0 = 0x80000000U; // set A
    constexpr uint32_t kLine1 = 0x80000040U; // set B（16B 行、32 set：index 差 4）
    const Words words0 = {0x01010101U, 0x01010102U, 0x01010103U, 0x01010104U};
    const Words words1 = {0x02020201U, 0x02020202U, 0x02020203U, 0x02020204U};

    unsigned beats = start_miss(dut, kLine0);
    check(beats == words0.size(), "unexpected line size");
    finish_refill(dut, words0, 0);
    wait_until(dut, [&] { return dut.io_resp_valid; },
               "L0 demand response never appeared");
    check(dut.io_resp_bits_data == words0[0],
          "L0 demand response returned the wrong word");
    dut.io_resp_ready = 1;
    tick(dut);
    drain_to_idle(dut);

    // --- 1) L1 miss：关键字（beat 0）早返回，demand 响应保持未消费 ---
    beats = start_miss(dut, kLine1);
    check(beats == words1.size(), "unexpected line size");
    send_beat(dut, words1[0], false);
    dut.eval();
    check(dut.io_resp_valid && dut.io_resp_bits_data == words1[0],
          "early-restart demand response is missing or wrong");
    check(!dut.io_resp_bits_fault, "unexpected fault on the critical word");

    // demand 未消费：任何新请求都不得被受理（单响应槽）。
    dut.io_req_bits_addr = kLine1; // 已返回的关键字
    dut.io_req_valid = 1;
    dut.eval();
    check(!dut.io_req_ready,
          "DCache accepted a request while the demand response was pending");

    // --- 2) 消费 demand 响应 ---
    dut.io_resp_ready = 1;
    tick(dut);
    dut.io_req_valid = 0;
    dut.eval();

    // 2a) 已返回的字（关键字）允许 hit-under-refill。
    //     非旁路模式会晚一拍受理：它必须等 demand 响应真正被取走（fire）
    //     才能释放响应槽；寄存器/旁路模式可在消费同拍受理。两者都合法，
    //     这里按 Decoupled 语义等待 ready。
    dut.io_req_bits_addr = kLine1;
    dut.io_req_valid = 1;
    dut.io_resp_ready = 1; // demand 响应若仍在，先消费它
    wait_until(dut, [&] { return dut.io_req_ready; },
               "returned word was not accepted as a hit-under-refill");
    check(dut.io_perf_hit && !dut.io_perf_miss,
          "returned word was not counted as a hit-under-refill");
    check(!dut.io_AXI_AR_ARVALID,
          "hit-under-refill issued an unexpected second AR");
    tick(dut);
    dut.io_req_valid = 0;
    dut.eval();
    wait_until(dut, [&] { return dut.io_resp_valid; },
               "hit-under-refill response never appeared");
    check(dut.io_resp_bits_data == words1[0],
          "hit-under-refill returned the wrong word");

    // 2b) 未返回的字必须被拒绝。
    dut.io_req_bits_addr = kLine1 + 4U;
    dut.io_req_valid = 1;
    dut.eval();
    check(!dut.io_req_ready,
          "unreturned word was accepted during refill");
    dut.io_req_valid = 0;

    // 2c) 其他 set 的完整命中在回填期间放行。
    dut.io_req_bits_addr = kLine0;
    dut.io_req_valid = 1;
    dut.eval();
    check(dut.io_req_ready && dut.io_perf_hit,
          "independent full hit was blocked behind the refill");
    tick(dut);
    dut.io_req_valid = 0;
    dut.eval();
    wait_until(dut, [&] { return dut.io_resp_valid; },
               "full-hit-under-refill response never appeared");
    check(dut.io_resp_bits_data == words0[0],
          "full-hit-under-refill returned the wrong word");

    // --- 3) 完成回填，L1 转为正常命中 ---
    finish_refill(dut, words1, 1);
    drain_to_idle(dut);
    dut.io_req_bits_addr = kLine1 + 8U;
    dut.io_req_valid = 1;
    dut.eval();
    check(dut.io_req_ready && dut.io_perf_hit,
          "L1 did not become a normal hit after the refill completed");
    tick(dut);
    dut.io_req_valid = 0;
    dut.eval();
    wait_until(dut, [&] { return dut.io_resp_valid; },
               "post-refill hit response never appeared");
    check(dut.io_resp_bits_data == words1[2],
          "post-refill hit returned the wrong word");
    drain_to_idle(dut);

    // --- 4) flush 穿越回填：demand 仍收到响应，行不安装 ---
    constexpr uint32_t kLine2 = 0x80000080U;
    const Words words2 = {0x03030301U, 0x03030302U, 0x03030303U, 0x03030304U};
    dut.io_resp_ready = 0;
    start_miss(dut, kLine2);
    send_beat(dut, words2[0], false);
    dut.eval();
    check(dut.io_resp_valid, "flush case: demand response missing");

    dut.io_flush = 1;
    tick(dut);
    dut.io_flush = 0;
    dut.eval();
    check(dut.io_resp_valid && dut.io_resp_bits_data == words2[0],
          "flush destroyed the accepted demand response");

    finish_refill(dut, words2, 1);
    dut.io_resp_ready = 1;
    drain_to_idle(dut);

    // 行不得安装：再次访问必须重新发起回填（miss + AR）。
    dut.io_req_bits_addr = kLine2;
    dut.io_req_valid = 1;
    dut.eval();
    check(dut.io_req_ready && dut.io_perf_miss && !dut.io_perf_hit,
          "flush-crossing refill installed a cache line");
    tick(dut);
    dut.io_req_valid = 0;
    dut.eval();
    check(dut.io_AXI_AR_ARVALID,
          "flushed line was served without a fresh refill");
    dut.final();
  });
}
