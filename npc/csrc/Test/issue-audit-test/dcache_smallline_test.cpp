// 审计：DCache 在最小行几何（BlockSizeLog2=2，1 word/行）下的基本功能。
//
// 背景：集成测试里观察到一次异常——在 4B 行配置下，首次 load 返回 0，
// 而内存模型里该地址是 0xAAAA0000（第二次读同一地址却返回了正确值）。
// 本测试用叶子 DCache（4B 行、寄存器阵列）直接验证：
//   miss 回填（1 拍）→ 数据正确；再读同址 → 命中且数据正确。

#include "Vysyx_26030103_DCacheReg.h"
#include "test_common.hpp"

#include <cstdint>
#include <verilated.h>

using DUT = Vysyx_26030103_DCacheReg;

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

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  return run_test("DCache 4-byte line basic miss/hit", [] {
    DUT dut;
    defaults(dut);
    reset(dut);

    // 必须落在默认 PMA（SoC，无 ChipLink）的可缓存区域内：PSRAM 0x80000000。
    constexpr uint32_t kAddr = 0x80000000U;
    constexpr uint32_t kWord = 0xAAAA0000U;

    // 首次访问：miss → 单拍回填。
    dut.io_resp_ready = 0;
    dut.io_req_valid = 1;
    dut.io_req_bits_addr = kAddr;
    dut.eval();
    check(dut.io_req_ready, "DCache did not accept the demand miss");
    tick(dut);
    dut.io_req_valid = 0;

    wait_until(dut, [&] { return dut.io_AXI_AR_ARVALID; },
               "DCache never issued the refill AR");
    check(dut.io_AXI_AR_ARADDR == kAddr,
          "4-byte line refill did not use the demand address as the line base");
    // 4B 行时 ARLEN 是编译期常量 0，Verilator 会把端口优化掉，不检查。
    dut.io_AXI_AR_ARREADY = 1;
    tick(dut);
    dut.io_AXI_AR_ARREADY = 0;

    dut.io_AXI_R_RDATA = kWord;
    dut.io_AXI_R_RRESP = 0;
    dut.io_AXI_R_RLAST = 1;
    dut.io_AXI_R_RVALID = 1;
    tick(dut);
    dut.io_AXI_R_RVALID = 0;
    dut.io_AXI_R_RLAST = 0;
    dut.eval();

    wait_until(dut, [&] { return dut.io_resp_valid; },
               "miss response never appeared");
    check(!dut.io_resp_bits_fault, "unexpected fault on the demand miss");
    check(dut.io_resp_bits_data == kWord,
          "first load returned 0x" + std::to_string(dut.io_resp_bits_data) +
              " instead of 0x" + std::to_string(kWord));
    dut.io_resp_ready = 1;
    tick(dut);
    dut.eval();

    // 第二次访问：命中，数据必须一致且不发起 AR。
    dut.io_req_valid = 1;
    dut.io_req_bits_addr = kAddr;
    dut.eval();
    check(dut.io_req_ready && dut.io_perf_hit && !dut.io_perf_miss,
          "second access to the same word was not a hit");
    tick(dut);
    dut.io_req_valid = 0;
    dut.eval();
    check(!dut.io_AXI_AR_ARVALID, "hit issued an unexpected AR");
    wait_until(dut, [&] { return dut.io_resp_valid; },
               "hit response never appeared");
    check(dut.io_resp_bits_data == kWord,
          "hit returned 0x" + std::to_string(dut.io_resp_bits_data) +
              " instead of 0x" + std::to_string(kWord));
    dut.final();
  });
}
