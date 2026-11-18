// 审计：生产配置（sram+sram、32B/64sets）下 ICacheSRAM 的行为。
// 此前审计快照集里没有任何一份包含该实现（ICacheSRAM 从未被仿真覆盖），
// 本探针用 rtl-prod 快照补齐：缺失/8 拍回填/关键词数据/反压稳定/命中
// 不发总线/非关键词错误不安装整行/关键词错误交付故障。

#include "Vysyx_26030103_ICacheSRAM.h"
#include "test_common.hpp"

#include <cstdint>
#include <verilated.h>

using DUT = Vysyx_26030103_ICacheSRAM;

static constexpr std::uint32_t kBase = 0x80000000U;
static constexpr std::uint32_t kKeyword = kBase + 8U; // 行内 word2
static constexpr std::uint32_t kKeywordData = 0x80000002U;

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

// 发起一次取指并完成整行回填；error_beat 若 >=0 则给该拍 RRESP=SLVERR。
static void refill_line(DUT &dut, std::uint32_t address, int error_beat) {
  dut.io_fetch_addr = address;
  dut.io_fetch_valid = 1;
  dut.io_resp_ready = 0;
  dut.eval();
  check(dut.io_fetch_ready, "ICacheSRAM 未受理取指");
  tick(dut);
  dut.io_fetch_valid = 0;
  dut.eval();

  wait_until(dut, [&] { return dut.io_axi_AR_ARVALID; },
             "ICacheSRAM 未发起回填 AR");
  check(dut.io_axi_AR_ARLEN == 7, "32B 行应为 8 拍突发");
  dut.io_axi_AR_ARREADY = 1;
  tick(dut);
  dut.io_axi_AR_ARREADY = 0;

  for (int beat = 0; beat < 8; ++beat) {
    dut.io_axi_R_RDATA = kBase + static_cast<std::uint32_t>(beat);
    dut.io_axi_R_RRESP = (beat == error_beat) ? 2 : 0;
    dut.io_axi_R_RLAST = (beat == 7);
    dut.io_axi_R_RVALID = 1;
    tick(dut);
    dut.io_axi_R_RVALID = 0;
    dut.io_axi_R_RLAST = 0;
    dut.eval();
  }
}

static void consume_response(DUT &dut) {
  dut.io_resp_ready = 1;
  tick(dut);
  dut.io_resp_ready = 0;
  dut.eval();
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  return run_test("ICacheSRAM 生产配置：缺失/回填/命中/反压/错误归属", [] {
    DUT dut;
    defaults(dut);
    reset(dut);

    // 1) 缺失 + 8 拍回填：关键词数据正确、反压期间稳定。
    refill_line(dut, kKeyword, -1);
    wait_until(dut, [&] { return dut.io_resp_valid; },
               "回填后没有响应");
    check(!dut.io_resp_fault, "正常回填不应报故障");
    check(dut.io_resp_data == kKeywordData, "关键词数据错误");
    for (int cycle = 0; cycle < 3; ++cycle) {
      tick(dut);
      dut.eval();
      check(dut.io_resp_valid && dut.io_resp_data == kKeywordData,
            "反压期间响应数据不稳定");
    }
    consume_response(dut);

    // 2) 命中：不发总线事务，数据来自阵列。
    dut.io_fetch_addr = kKeyword;
    dut.io_fetch_valid = 1;
    dut.eval();
    check(dut.io_fetch_ready && dut.io_perf_hit, "同词再取未命中");
    check(!dut.io_axi_AR_ARVALID, "命中不应发起总线事务");
    tick(dut);
    dut.io_fetch_valid = 0;
    dut.eval();
    wait_until(dut, [&] { return dut.io_resp_valid; }, "命中没有响应");
    check(dut.io_resp_data == kKeywordData && !dut.io_resp_fault,
          "命中数据错误");
    consume_response(dut);

    // 3) 非关键词拍出错：关键词数据仍交付，但整行不得安装——
    //    再取同行另一个词必须重新发起 AR。
    refill_line(dut, kBase + 0x20U + 4U, 1);
    wait_until(dut, [&] { return dut.io_resp_valid; },
               "带行错误的回填没有响应");
    consume_response(dut);
    dut.io_fetch_addr = kBase + 0x20U + 12U; // 同行、非关键词、未安装
    dut.io_fetch_valid = 1;
    dut.eval();
    check(dut.io_fetch_ready, "带行错误后的取指未被受理");
    check(!dut.io_perf_hit, "出错的行被错误地安装为命中");
    tick(dut);
    dut.io_fetch_valid = 0;
    dut.eval();
    wait_until(dut, [&] { return dut.io_axi_AR_ARVALID; },
               "未安装的行没有重新发起 AR");
    dut.io_axi_AR_ARREADY = 1;
    tick(dut);
    dut.io_axi_AR_ARREADY = 0;
    for (int beat = 0; beat < 8; ++beat) {
      dut.io_axi_R_RDATA = 0x90000000U + static_cast<std::uint32_t>(beat);
      dut.io_axi_R_RRESP = 0;
      dut.io_axi_R_RLAST = (beat == 7);
      dut.io_axi_R_RVALID = 1;
      tick(dut);
      dut.io_axi_R_RVALID = 0;
      dut.io_axi_R_RLAST = 0;
      dut.eval();
    }
    wait_until(dut, [&] { return dut.io_resp_valid; },
               "重试回填没有响应");
    check(dut.io_resp_data == 0x90000003U, "重试关键词数据错误");
    consume_response(dut);

    // 4) 关键词拍本身出错：交付故障响应（NOP 载荷），且行不安装。
    refill_line(dut, kBase + 0x40U + 16U, 4); // 关键词 = 行内 word4
    wait_until(dut, [&] { return dut.io_resp_valid; },
               "关键词错误的回填没有响应");
    check(dut.io_resp_fault, "关键词拍错误未交付故障响应");
    check(dut.io_resp_data == 0x00000013U, "故障响应应替换为 NOP 载荷");
    consume_response(dut);

    dut.final();
  });
}
