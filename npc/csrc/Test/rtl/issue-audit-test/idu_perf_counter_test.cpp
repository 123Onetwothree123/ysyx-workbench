// 审计：IDU 性能计数器的分类口径。
//
// 疑点（来自静态审计）：
//   1. MDU 造成的停顿（生产者 regWrite、非访存、值未就绪）会被计入
//      perf_stall_raw_alu，因为 raw_alu = isRAW && !raw_loaduse；
//   2. raw_loaduse 只看“命中且是访存”，不看 fwd_ready，因此一个“已就绪
//      的 load”与隐藏 MDU 写掩码同时存在时，停顿会被误记为 load-use。
//
// 本测试用叶子 IDU 驱动四路冒险端口，验证上述分类行为是否真实存在。

#include "Vysyx_26030103_IDU.h"
#include "test_common.hpp"
#include <verilated.h>

using DUT = Vysyx_26030103_IDU;

// add x10, x5, x0  -> 使用 rs1=5
static constexpr uint32_t kUsesRs1_5 = 0x00028533U;
// add x10, x0, x6  -> 使用 rs2=6
static constexpr uint32_t kUsesRs2_6 = 0x00600533U;

static void defaults(DUT &dut) {
  dut.io_in_valid = 1;
  dut.io_in_bits_Instruction = kUsesRs1_5;
  dut.io_in_bits_pc = 0x80000000U;
  dut.io_in_bits_ExceptionValid = 0;
  dut.io_in_bits_ExceptionCause = 0;
  dut.io_in_bits_pred_taken = 0;
  dut.io_in_bits_pred_target = 0x80000004U;
  dut.io_out_ready = 1;
  dut.io_ReadDATA1 = 0x11111111U;
  dut.io_ReadDATA2 = 0x22222222U;

  dut.io_ex_valid = 0;
  dut.io_ex_rd = 0;
  dut.io_ex_regWrite = 0;
  dut.io_ex_memop = 0;
  dut.io_ex_fwd_ready = 0;
  dut.io_ex_fwd_data = 0;
  dut.io_ex_mdu_hidden_writes = 0;

  dut.io_me_valid = 0;
  dut.io_me_rd = 0;
  dut.io_me_regWrite = 0;
  dut.io_me_memop = 0;
  dut.io_me_fwd_ready = 0;
  dut.io_me_fwd_data = 0;

  dut.io_me2_valid = 0;
  dut.io_me2_rd = 0;
  dut.io_me2_regWrite = 0;
  dut.io_me2_memop = 0;
  dut.io_me2_fwd_ready = 0;
  dut.io_me2_fwd_data = 0;

  dut.io_wb_valid = 0;
  dut.io_wb_rd = 0;
  dut.io_wb_regWrite = 0;
  dut.io_wb_fwd_data = 0;
}

static void clear_hazards(DUT &dut) {
  dut.io_ex_valid = 0;
  dut.io_ex_regWrite = 0;
  dut.io_ex_memop = 0;
  dut.io_ex_fwd_ready = 0;
  dut.io_ex_mdu_hidden_writes = 0;
  dut.io_me_valid = 0;
  dut.io_me_regWrite = 0;
  dut.io_me_memop = 0;
  dut.io_me_fwd_ready = 0;
  dut.io_me2_valid = 0;
  dut.io_me2_regWrite = 0;
  dut.io_me2_memop = 0;
  dut.io_me2_fwd_ready = 0;
  dut.io_wb_valid = 0;
  dut.io_wb_regWrite = 0;
}

static bool no_stall_counters(DUT &dut) {
  return !dut.io_perf_stall_raw && !dut.io_perf_stall_raw_loaduse &&
         !dut.io_perf_stall_raw_alu;
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  return run_test("IDU stall counters classify MDU and hidden-write stalls",
                  [] {
                    DUT dut;
                    defaults(dut);
                    dut.eval();

                    // --- 1) EX 级 MDU 风格生产者：写回、非访存、值未就绪 ---
                    // 真实系统里 ALU 生产者的 fwd_ready 恒为 1，所以“非访存
                    // 且未就绪”只可能来自 MDU（队首结果未回）。
                    clear_hazards(dut);
                    dut.io_ex_valid = 1;
                    dut.io_ex_regWrite = 1;
                    dut.io_ex_rd = 5;
                    dut.io_ex_memop = 0;
                    dut.io_ex_fwd_ready = 0;
                    dut.eval();
                    check(!dut.io_in_ready, "EX hazard did not stall the IDU");
                    check(dut.io_perf_stall_raw,
                          "EX hazard was not counted as any RAW stall");
                    // raw_alu 是“非 load-use 的 RAW 停顿”桶；MDU 队首未就绪
                    // 正属于这一类（ALU 生产者恒就绪，不会产生停顿）。
                    check(dut.io_perf_stall_raw_alu,
                          "MDU-style EX stall was not counted as raw_alu");
                    check(!dut.io_perf_stall_raw_loaduse,
                          "MDU-style EX stall was misclassified as load-use");

                    // 对照：同一条生产者变为就绪后必须零停顿。
                    dut.io_ex_fwd_ready = 1;
                    dut.eval();
                    check(dut.io_in_ready,
                          "ready EX producer still stalled the IDU");
                    check(no_stall_counters(dut),
                          "ready EX producer polluted stall counters");

                    // --- 2) 隐藏 MDU 写掩码单独造成停顿 ---
                    clear_hazards(dut);
                    dut.io_ex_mdu_hidden_writes = 1U << 5;
                    dut.eval();
                    check(!dut.io_in_ready,
                          "hidden MDU write did not stall its consumer");
                    check(dut.io_perf_stall_raw_alu,
                          "hidden MDU write stall was not counted as raw_alu");
                    check(!dut.io_perf_stall_raw_loaduse,
                          "hidden MDU write stall was misclassified");

                    // --- 3) “已就绪的 load” + 隐藏 MDU 写：不得误报为 load-use ---
                    // me_fwd_ready=1 表示该 load 的数据已经可以转发，它不是
                    // 真正的停顿原因；真正的阻塞来自隐藏 MDU 写掩码。修复后
                    // 该停顿应计入非 load-use 桶（raw_alu）。
                    clear_hazards(dut);
                    dut.io_ex_mdu_hidden_writes = 1U << 5;
                    dut.io_me_valid = 1;
                    dut.io_me_regWrite = 1;
                    dut.io_me_rd = 5;
                    dut.io_me_memop = 1;
                    dut.io_me_fwd_ready = 1;
                    dut.io_me_fwd_data = 0x33333333U;
                    dut.eval();
                    check(!dut.io_in_ready,
                          "hidden MDU write did not stall its consumer");
                    check(dut.io_perf_stall_raw,
                          "hidden MDU write stall was not counted");
                    check(!dut.io_perf_stall_raw_loaduse,
                          "ready-load + hidden-write stall was misattributed "
                          "to load-use");
                    check(dut.io_perf_stall_raw_alu,
                          "non-load stall did not land in the raw_alu bucket");

                    // --- 4) 真正的 load-use（对照，应计入 loaduse）---
                    clear_hazards(dut);
                    dut.io_me2_valid = 1;
                    dut.io_me2_regWrite = 1;
                    dut.io_me2_rd = 5;
                    dut.io_me2_memop = 1;
                    dut.io_me2_fwd_ready = 0;
                    dut.eval();
                    check(!dut.io_in_ready, "load-use did not stall the IDU");
                    check(dut.io_perf_stall_raw_loaduse,
                          "true load-use was not counted as load-use");
                    check(!dut.io_perf_stall_raw_alu,
                          "true load-use leaked into raw_alu");

                    dut.final();
                  });
}
