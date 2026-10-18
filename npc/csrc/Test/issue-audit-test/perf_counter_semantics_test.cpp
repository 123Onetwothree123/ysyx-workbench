// 审计：perf 统计口径修正的回归（异常/mret 计入取指重定向事件）。
//
// 程序：设置 mtvec → ecall（同步异常重定向）→ mret 返回 → fence.i → halt；
// 处理程序把 mepc+=4 后 mret。期望（修正后）：
//   perf_ifu_stall_redirect = 3 个事件（ecall、mret、fence.i）；
//   perf_trap_count         = 1（仅 ecall）；
//   perf_ifu_fetch          >= 13（9 条主路径 + 4 条处理程序；冲刷拍丢弃
//                              的响应不计，修正前会偏大）。
// 修正前 ecall/mret 不进 ifu_stall_redirect（只有 fence.i → 1），本探针失败。
// 另用 a1==1 验证 mret 确实返回到 ecall 之后。

#include "Vriscv32e_npc_SimTop.h"
#include "test_common.hpp"

#include <cstdint>
#include <string>
#include <verilated.h>

using DUT = Vriscv32e_npc_SimTop;

static const uint32_t kProgram[] = {
    0x800002B7U, // lui   t0, 0x80000
    0x04028293U, // addi  t0, t0, 0x40   ; handler = 0x80000040
    0x30529073U, // csrrw x0, mtvec, t0
    0x00000013U, // nop
    0x00000073U, // ecall                ; → handler
    0x00100593U, // addi  a1, x0, 1      ; mret 返回点
    0x0000100FU, // fence.i
    0x00200613U, // addi  a2, x0, 2
    0x0000000BU, // custom-0 halt
    0x00000013U, // 填充（0x24..0x3c）
    0x00000013U,
    0x00000013U,
    0x00000013U,
    0x00000013U,
    0x00000013U,
    0x00000013U,
    0x34102373U, // csrrs t1, mepc, x0  ; handler（0x40）
    0x00430313U, // addi  t1, t1, 4
    0x34131073U, // csrrw x0, mepc, t1
    0x30200073U, // mret
};

static void edge(DUT &dut) {
  dut.clock = 1;
  dut.eval();
  dut.clock = 0;
  dut.eval();
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  return run_test("perf 口径：异常/mret 计入取指重定向事件", [] {
    DUT dut;
    dut.clock = 0;
    dut.reset = 1;
    dut.ram_init_valid = 0;
    dut.ram_init_index = 0;
    dut.ram_init_data = 0;
    dut.debug_gpr_raddr = 0;
    dut.eval();
    edge(dut);

    for (size_t index = 0; index < sizeof(kProgram) / sizeof(kProgram[0]);
         ++index) {
      dut.ram_init_valid = 1;
      dut.ram_init_index = static_cast<uint16_t>(index);
      dut.ram_init_data = kProgram[index];
      edge(dut);
    }
    dut.ram_init_valid = 0;
    for (int cycle = 0; cycle < 4; ++cycle) {
      edge(dut);
    }
    dut.reset = 0;
    dut.eval();

    int redirect_events = 0;
    int trap_events = 0;
    int fetch_events = 0;
    int idle_cycles = 0;
    for (long cycle = 0; cycle < 4000; ++cycle) {
      dut.eval();
      if (dut.perf_ifu_stall_redirect) {
        redirect_events++;
      }
      if (dut.perf_trap_count) {
        trap_events++;
      }
      if (dut.perf_ifu_fetch) {
        fetch_events++;
      }
      if (dut.perf_ifu_stall_idle) {
        idle_cycles++;
      }
      if (dut.trap_valid) {
        break;
      }
      edge(dut);
    }
    check(dut.trap_valid, "程序未到达 custom halt");

    dut.debug_gpr_raddr = 11; // a1：mret 返回点之后被写为 1
    dut.eval();
    const uint32_t a1 = dut.debug_gpr_rdata;

    std::cout << "[info] redirect=" << redirect_events
              << " trap=" << trap_events << " fetch=" << fetch_events
              << " ifu_idle=" << idle_cycles << " a1=" << a1 << "\n";
    check(a1 == 1, "mret 未正确返回到 ecall 之后（a1=" + std::to_string(a1) +
                       "）");
    check(redirect_events == 3,
          "取指重定向事件应为 3（ecall/mret/fence.i），实测 " +
              std::to_string(redirect_events));
    check(trap_events == 1,
          "trap_count 应为 1（仅 ecall），实测 " +
              std::to_string(trap_events));
    check(fetch_events >= 13,
          "取指交付应不少于 13 条，实测 " + std::to_string(fetch_events));
    dut.final();
  });
}
