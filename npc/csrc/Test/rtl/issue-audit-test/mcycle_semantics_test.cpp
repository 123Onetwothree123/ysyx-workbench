// 审计：mcycle 在“写入那拍”不自增是否构成偏差。
//
// 疑点（来自静态审计）：mcycle 每个时钟沿 +1，但写使能优先，因此写入拍
// 少计一次。规范要求 mcycle 统计时钟周期数；本测试验证写入后的可观测
// 语义是否与“写入后从写入值继续计数”一致：
//   - 复位后 N 拍（无写）读出应为 N；
//   - 写入 0x100 后，下一拍读出应为 0x101（写入拍本身不算增量，
//     但此后每拍 +1），即写入口令值在写入点生效、计数不中断；
//   - 高位半字访问（SelectHigh）与低位独立。

#include "Vysyx_26030103_mcycle.h"
#include "test_common.hpp"

#include <cstdint>
#include <verilated.h>

using DUT = Vysyx_26030103_mcycle;

static void clk_tick(DUT &dut) {
  dut.io_clk = 0;
  dut.eval();
  dut.io_clk = 1;
  dut.eval();
  dut.io_clk = 0;
  dut.eval();
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  return run_test("mcycle write/read semantics", [] {
    DUT dut;
    dut.io_clk = 0;
    dut.io_rst = 1;
    dut.io_wen = 0;
    dut.io_SelectHigh = 0;
    dut.io_wdata = 0;
    dut.eval();
    clk_tick(dut);
    clk_tick(dut);
    dut.io_rst = 0;
    dut.eval();

    // 复位后 10 拍，无写入：低半字应为 10。
    for (int cycle = 0; cycle < 10; ++cycle) {
      clk_tick(dut);
    }
    dut.eval();
    check(dut.io_rdata == 10U,
          "mcycle low half did not count free-running cycles: got " +
              std::to_string(dut.io_rdata));

    // 写入 0x100（低半字），随后一拍读出：应为 0x101。
    dut.io_wen = 1;
    dut.io_wdata = 0x100U;
    clk_tick(dut);
    dut.io_wen = 0;
    dut.eval();
    check(dut.io_rdata == 0x100U,
          "mcycle did not take the written value at the write edge: got 0x" +
              std::to_string(dut.io_rdata));

    clk_tick(dut);
    dut.eval();
    check(dut.io_rdata == 0x101U,
          "mcycle did not continue counting after the write: got 0x" +
              std::to_string(dut.io_rdata));

    // 高位半字：写入 0x2，再读高半字应为 0x2。
    dut.io_SelectHigh = 1;
    dut.io_wen = 1;
    dut.io_wdata = 0x2U;
    clk_tick(dut);
    dut.io_wen = 0;
    dut.eval();
    check(dut.io_rdata == 0x2U,
          "mcycle high half write did not take effect: got 0x" +
              std::to_string(dut.io_rdata));
    dut.final();
  });
}
