// 审计：DCache 非旁路 SRAM（sram_nobypass）的响应槽在“流水化命中”下
// 会丢响应/串数据。
//
// 疑点（来自静态审计）：非旁路分支在 SRAM 数据到达拍无条件执行
//   ResponseValid := true.B
// 而源码更靠后的 io.resp.fire 分支执行 ResponseValid := false.B
// （Chisel 后者优先）。旁路分支用 when(!io.resp.ready) 保护了自己，
// 非旁路分支没有保护：当消费者在同一拍消费旧响应时，新响应被清掉。
//
// 触发条件：消费者保持 req.valid=1、resp.ready=1（“弹旧推新”是
// DCacheInterface 自己承诺的弹性槽语义），非旁路模式下
// ResponseSlotReady = !ResponseOccupied || io.resp.ready 会在
// “响应尚未可见（SramRespPending）”时也放行新请求，导致槽被超额订阅。
//
// 本测试对同一份测试源编译三种存储介质：
//   -DTEST_STORAGE_REG        寄存器阵列（对照，应通过）
//   -DTEST_STORAGE_SRAM       同步 SRAM 旁路（对照，应通过）
//   -DTEST_STORAGE_NOBYPASS   同步 SRAM 非旁路（预期暴露缺陷）

#include "test_common.hpp"
#if defined(TEST_STORAGE_REG)
#include "Vysyx_26030103_DCacheReg.h"
using DUT = Vysyx_26030103_DCacheReg;
#else
#include "Vysyx_26030103_DCacheSRAM.h"
using DUT = Vysyx_26030103_DCacheSRAM;
#endif

#include <cstdint>
#include <string>
#include <vector>
#include <verilated.h>

#if defined(TEST_STORAGE_REG)
static const char *kStorageName = "reg";
#elif defined(TEST_STORAGE_SRAM)
static const char *kStorageName = "sram(bypass)";
#elif defined(TEST_STORAGE_NOBYPASS)
static const char *kStorageName = "sram_nobypass";
#else
#error "define TEST_STORAGE_{REG,SRAM,NOBYPASS}"
#endif

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

// 触发一次 miss 并完成整行回填。
static void miss_and_refill(DUT &dut, uint32_t addr,
                            const std::vector<uint32_t> &words) {
  dut.io_resp_ready = 0;
  dut.io_req_valid = 1;
  dut.io_req_bits_addr = addr;
  dut.eval();
  check(dut.io_req_ready, "DCache did not accept the demand miss");
  tick(dut);
  dut.io_req_valid = 0;

  wait_until(dut, [&] { return dut.io_AXI_AR_ARVALID; },
             "DCache never issued the refill AR");
  const unsigned beats = static_cast<unsigned>(dut.io_AXI_AR_ARLEN) + 1U;
  check(beats == words.size(), "refill burst length mismatch");
  dut.io_AXI_AR_ARREADY = 1;
  tick(dut);
  dut.io_AXI_AR_ARREADY = 0;

  for (unsigned beat = 0; beat < beats; ++beat) {
    dut.io_AXI_R_RDATA = words[beat];
    dut.io_AXI_R_RRESP = 0;
    dut.io_AXI_R_RLAST = (beat + 1 == beats);
    dut.io_AXI_R_RVALID = 1;
    tick(dut);
    dut.io_AXI_R_RVALID = 0;
    dut.io_AXI_R_RLAST = 0;
  }
  // 消费 demand 响应，回到空闲。
  wait_until(dut, [&] { return dut.io_resp_valid; },
             "refill never produced the demand response");
  dut.io_resp_ready = 1;
  tick(dut);
  dut.eval();
  check(dut.io_req_ready, "DCache did not return to idle after the refill");
}

// 同拍"命中 store 写镜像 + 同线 load 请求"的契约检查。
// 源码注释承诺：同拍成功 store 会在时钟沿才写入阵列，因此必须反压该
// load 一拍，避免响应锁存 store 之前的旧值。三种存储介质必须一致遵守，
// 且冲突解除后的响应必须是 store 之后的新值。
static void test_store_conflict_same_cycle(DUT &dut, uint32_t line_base,
                                           const std::vector<uint32_t> &words) {
  miss_and_refill(dut, line_base + 4U, words);
  const uint32_t kStoreAddr = line_base + 4U;
  constexpr uint32_t kStoreData = 0xDEADBEEFU;
  const uint32_t kOldWord = words[1]; // 冲突拍之前该字的旧值

  // 冲突拍：同线 store 命中（写镜像）+ load 请求。响应先不消费，
  // 保证随后出现的响应必然属于冲突拍受理的这个请求。
  dut.io_resp_ready = 0;
  dut.io_req_valid = 1;
  dut.io_req_bits_addr = kStoreAddr;
  dut.io_req_bits_WidthSelect = 2;
  dut.io_req_bits_signed = 0;
  dut.io_StoreValid = 1;
  dut.io_StoreResp = 0;
  dut.io_StoreAddr = kStoreAddr;
  dut.io_StoreData = kStoreData;
  dut.io_StoreStrb = 0xF;
  dut.eval();
  const bool accepted_in_conflict = dut.io_req_ready;
  tick(dut);
  dut.io_StoreValid = 0;
  dut.io_StoreStrb = 0;
  dut.eval();
  dut.io_req_valid = 0;

  uint32_t first_resp = 0;
  bool got_first = false;
  if (accepted_in_conflict) {
    wait_until(dut, [&] { return dut.io_resp_valid; },
               "冲突拍受理的 load 没有响应");
    first_resp = dut.io_resp_bits_data;
    got_first = true;
  }
  dut.io_resp_ready = 1;
  tick(dut);
  dut.eval();

  // 冲突解除后的新请求：必须返回 store 之后的新值。
  dut.io_req_valid = 1;
  dut.io_req_bits_addr = kStoreAddr;
  dut.eval();
  check(dut.io_req_ready, "冲突解除后 load 未被接受");
  tick(dut);
  dut.io_req_valid = 0;
  wait_until(dut, [&] { return dut.io_resp_valid; },
             "冲突解除后的 load 没有响应");
  const uint32_t after_resp = dut.io_resp_bits_data;
  dut.io_resp_ready = 1;
  tick(dut);
  dut.eval();

  std::cout << "[info] " << kStorageName
            << ": 冲突拍受理=" << accepted_in_conflict << " 冲突拍响应=0x"
            << std::hex << first_resp << "（旧字=0x" << kOldWord
            << "） 冲突后响应=0x" << after_resp << std::dec << "\n";
  check(!accepted_in_conflict,
        "同拍 store 写镜像与同线 load 必须反压一拍（源码注释承诺）");
  if (got_first) {
    check(first_resp == kStoreData,
          "冲突拍受理的 load 返回了 store 之前的旧值 0x" +
              std::to_string(first_resp));
  }
  check(after_resp == kStoreData,
        "冲突解除后的 load 必须看到 store 之后的新值，得到 0x" +
            std::to_string(after_resp));
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  const std::string name =
      std::string("DCache pipelined hits keep one response per request [") +
      kStorageName + "]";
  return run_test(name.c_str(), [] {
    DUT dut;
    defaults(dut);
    reset(dut);

    constexpr uint32_t kLineBase = 0x80000000U;
    const std::vector<uint32_t> words = {0x11111111U, 0x22222222U, 0x33333333U,
                                         0x44444444U};
    miss_and_refill(dut, kLineBase + 4U, words);

    // 流水化命中：valid=1、ready=1 持续驱动，请求在行内循环。
    // 接口承诺“弹出旧响应的同拍可以受理新请求”，所以这里的时序是合法的；
    // DUT 的 req.ready 是唯一权威。
    constexpr int kRequestCycles = 10;
    std::vector<uint32_t> requests;
    std::vector<uint32_t> responses;
    for (int cycle = 0; cycle < kRequestCycles; ++cycle) {
      dut.io_req_bits_addr = kLineBase + (static_cast<unsigned>(cycle) % 4U) * 4U;
      dut.io_req_valid = 1;
      dut.io_resp_ready = 1;
      dut.eval();
      if (dut.io_req_ready) {
        requests.push_back(dut.io_req_bits_addr);
      }
      if (dut.io_resp_valid) {
        check(!dut.io_resp_bits_fault, "unexpected fault response");
        responses.push_back(dut.io_resp_bits_data);
      }
      tick(dut);
    }
    // 排空剩余响应。
    dut.io_req_valid = 0;
    for (int cycle = 0; cycle < 8; ++cycle) {
      dut.eval();
      if (dut.io_resp_valid) {
        responses.push_back(dut.io_resp_bits_data);
      }
      tick(dut);
    }
    dut.eval();

    check(!requests.empty(), "no request was ever accepted");
    check(requests.size() == responses.size(),
          "response count " + std::to_string(responses.size()) +
              " != request count " + std::to_string(requests.size()));
    for (size_t index = 0; index < responses.size(); ++index) {
      const uint32_t expected =
          words[(requests[index] - kLineBase) / 4U];
      check(responses[index] == expected,
            "response " + std::to_string(index) + " carries 0x" +
                std::to_string(responses[index]) + " but request 0x" +
                std::to_string(requests[index]) + " expects 0x" +
                std::to_string(expected));
    }
    test_store_conflict_same_cycle(dut, kLineBase + 0x40U, words);

    dut.final();
  });
}
