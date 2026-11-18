// 定向回归：ICache 在 redirect(kill) 后不得把旧取指的数据/响应交给新 PC。
//
// 覆盖：
//  1. 命中响应已就绪但尚未消费时 kill：响应必须消失，后续命中仍正确；
//  2. cache miss 的 AR 已展示但未握手时 kill：旧事务排空且不得安装，
//     新 PC 换到另一 cache line 后必须重新 refill 并返回自己的字；
//  3. kill 后新 PC 与旧 PC 在同一 cache line：旧 refill 的数据必须丢弃，
//     新 PC 按自己的块内偏移重新取指。

#include "Vysyx_26030103_ICacheReg.h"
#include "test_common.hpp"

#include <cstdint>
#include <string>
#include <vector>
#include <verilated.h>

using DUT = Vysyx_26030103_ICacheReg;

static constexpr uint32_t kLineA = 0x80000000U; // set 0, tag A
static constexpr uint32_t kLineB = 0x80000010U; // set 1, tag B
static constexpr uint32_t kLineC = 0x80000200U; // set 0, tag C（与 A 同组）
static constexpr uint32_t kDataA = 0xA0000000U;
static constexpr uint32_t kDataB = 0xB0000000U;
static constexpr uint32_t kDataC = 0xC0000000U;

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

// 受理一笔取指。调用前 fetch_valid 可为 0。
static void accept_fetch(DUT &dut, uint32_t addr) {
  dut.io_fetch_addr = addr;
  dut.io_fetch_valid = 1;
  dut.eval();
  check(dut.io_fetch_ready, "ICache rejected a fetch that should be accepted");
  tick(dut);
  dut.io_fetch_valid = 0;
  dut.eval();
}

// 等待下一笔 refill 的 AR，校验行地址，并接受握手。
static unsigned begin_refill(DUT &dut, uint32_t line_base) {
  wait_until(dut, [&] { return dut.io_axi_AR_ARVALID; },
             "ICache never issued the expected refill AR");
  check(dut.io_axi_AR_ARADDR == line_base,
        "refill AR address does not match the requested cache line");
  const unsigned beats = static_cast<unsigned>(dut.io_axi_AR_ARLEN) + 1U;
  dut.io_axi_AR_ARREADY = 1;
  tick(dut);
  dut.io_axi_AR_ARREADY = 0;
  dut.eval();
  return beats;
}

static void send_beat(DUT &dut, uint32_t data, bool last) {
  dut.io_axi_R_RDATA = data;
  dut.io_axi_R_RRESP = 0;
  dut.io_axi_R_RLAST = last;
  dut.io_axi_R_RVALID = 1;
  tick(dut);
  dut.io_axi_R_RVALID = 0;
  dut.io_axi_R_RLAST = 0;
  dut.eval();
}

// 完成一整行 refill，返回该行写入的数据字。
static std::vector<uint32_t> refill_line(DUT &dut, uint32_t line_base,
                                         uint32_t data_base) {
  const unsigned beats = begin_refill(dut, line_base);
  std::vector<uint32_t> words;
  for (unsigned beat = 0; beat < beats; ++beat) {
    const uint32_t word = data_base + beat;
    words.push_back(word);
    send_beat(dut, word, beat + 1U == beats);
  }
  return words;
}

// 默认配置的 ICacheReg 在整核 RTL 快照里未使用 resp_addr，firtool 会裁掉
// 该端口；本测试用“每个地址独一无二的数据字”校验响应归属。
static void consume_response(DUT &dut, uint32_t expected_addr,
                             uint32_t expected_data) {
  (void)expected_addr;
  wait_until(dut, [&] { return dut.io_resp_valid; },
             "ICache never produced the expected response");
  check(dut.io_resp_data == expected_data,
        "ICache response carried the wrong instruction word");
  check(!dut.io_resp_fault, "ICache reported a fault for a valid fetch");
  dut.io_resp_ready = 1;
  tick(dut);
  dut.io_resp_ready = 0;
  dut.eval();
}

static void kill_cycle(DUT &dut) {
  // 顶层 redirect 时 StageConnect 会同时接收并丢弃当前响应。
  dut.io_kill = 1;
  dut.io_resp_ready = 1;
  tick(dut);
  dut.io_kill = 0;
  dut.io_resp_ready = 0;
  dut.eval();
}

static void hit_response_dropped_by_kill() {
  DUT dut;
  defaults(dut);
  reset(dut);

  accept_fetch(dut, kLineA + 0U);
  const std::vector<uint32_t> line = refill_line(dut, kLineA, kDataA);
  consume_response(dut, kLineA, line[0]);

  // 命中响应已就绪但下游尚未消费，此时 redirect。
  accept_fetch(dut, kLineA + 4U);
  wait_until(dut, [&] { return dut.io_resp_valid; },
             "hit response did not appear");
  check(dut.io_resp_data == line[1], "hit response lost its data");
  kill_cycle(dut);
  check(!dut.io_resp_valid, "kill did not cancel the pending hit response");

  // 同一行仍然有效；新 PC 必须拿到自己的字。
  accept_fetch(dut, kLineA + 8U);
  consume_response(dut, kLineA + 8U, line[2]);
}

static void miss_redirect_to_other_line() {
  DUT dut;
  defaults(dut);
  reset(dut);

  // 旧 PC C：miss 的 AR 已对外展示但尚未握手时 redirect。
  accept_fetch(dut, kLineC + 0U);
  wait_until(dut, [&] { return dut.io_axi_AR_ARVALID; },
             "miss did not issue AR");
  check(dut.io_axi_AR_ARADDR == kLineC, "miss AR used the wrong line");
  tick(dut); // ARVALID 被反压一拍，ar_exposed=1
  kill_cycle(dut);

  // 旧事务仍必须完成协议；发送与目标行不同的垃圾数据，discard 不得安装。
  const unsigned beats = static_cast<unsigned>(dut.io_axi_AR_ARLEN) + 1U;
  dut.io_axi_AR_ARREADY = 1;
  tick(dut);
  dut.io_axi_AR_ARREADY = 0;
  for (unsigned beat = 0; beat < beats; ++beat) {
    send_beat(dut, 0xDEAD0000U + beat, beat + 1U == beats);
  }
  check(!dut.io_resp_valid, "discarded refill produced a response");

  // 新 PC 在另一行：必须重新 miss/refill，并返回 B 行自己的数据。
  accept_fetch(dut, kLineB + 0U);
  const std::vector<uint32_t> lineB = refill_line(dut, kLineB, kDataB);
  consume_response(dut, kLineB, lineB[0]);
}

static void miss_redirect_within_same_line() {
  DUT dut;
  defaults(dut);
  reset(dut);

  // 旧 PC 在行 C 偏移 4；AR 展示后背压，redirect 到同一行的偏移 8。
  accept_fetch(dut, kLineC + 4U);
  wait_until(dut, [&] { return dut.io_axi_AR_ARVALID; },
             "same-line miss did not issue AR");
  check(dut.io_axi_AR_ARADDR == kLineC, "same-line AR used the wrong line");
  tick(dut);
  kill_cycle(dut);

  const unsigned beats = static_cast<unsigned>(dut.io_axi_AR_ARLEN) + 1U;
  dut.io_axi_AR_ARREADY = 1;
  tick(dut);
  dut.io_axi_AR_ARREADY = 0;
  // 旧的同线数据必须被丢弃，不能因为新 PC 也在同一行而被 partial-hit 使用。
  for (unsigned beat = 0; beat < beats; ++beat) {
    send_beat(dut, 0xBAD00000U + beat, beat + 1U == beats);
  }
  check(!dut.io_resp_valid, "discarded same-line refill produced a response");

  accept_fetch(dut, kLineC + 8U);
  const std::vector<uint32_t> lineC = refill_line(dut, kLineC, kDataC);
  consume_response(dut, kLineC + 8U, lineC[2]);
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  int failures = 0;
  failures += run_test("ICache drops pending hit response on kill",
                       hit_response_dropped_by_kill);
  failures += run_test("ICache redirect to a different line", 
                       miss_redirect_to_other_line);
  failures += run_test("ICache redirect within the same line",
                       miss_redirect_within_same_line);
  return failures == 0 ? 0 : 1;
}
