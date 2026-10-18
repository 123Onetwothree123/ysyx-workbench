// 设计审计：用生产顶层（rtl/riscv32e_npc_SimTop.sv）按"窗口"统计逐拍
// perf 信号，确认"完全耦合流水线"的设计代价真实存在。
//
// 说明：in-order 核里依赖指令总是紧跟在生产者之后退休（间隔 1 拍），
// 停顿被吸收进生产者自身的延迟；因此用 retire 间隔看不出来，必须按
// 窗口统计 stall 信号（ICache 的缺失信号单列，不参与归因）。
//
// 程序（手工汇编）：
//   0x00 lui  x5,0x80000      ; 基址
//   0x04 lw   x6,0x100(x5)    ; DCache miss（填充）
//   0x08 lw   x7,0x100(x5)    ; DCache hit
//   0x0c add  x8,x7,x0        ; 窗口 A：命中 load-use
//   0x10 lw   x9,0x200(x5)    ; DCache miss（另一行）
//   0x14 add  x10,x9,x0       ; 窗口 B：缺失 load-use（应更长）
//   0x18 sw   x8,0x300(x5)    ; 写直达 store
//   0x1c add  x11,x0,x0       ; 窗口 C：store 之后独立指令等 BRESP
//   0x20 mul  x12,x5,x5       ; MUL（shift-add，约 33 拍）
//   0x24 add  x13,x0,x0       ; 窗口 D：MUL 在飞期间独立指令被阻塞
//   0x28 addi x14,x0,1
//   0x2c addi x15,x0,2        ; 窗口 E：独立 ALU 背靠背（对照，应 1 拍）
//   0x30 0x0000000B           ; custom-0 halt

#include "Vriscv32e_npc_SimTop.h"
#include "test_common.hpp"

#include <cstdint>
#include <map>
#include <string>
#include <vector>
#include <verilated.h>

using DUT = Vriscv32e_npc_SimTop;

static constexpr uint32_t kProgramBase = 0x80000000U;

static const uint32_t kProgram[] = {
    0x800002B7U, // lui  x5, 0x80000
    0x1002A303U, // lw   x6, 0x100(x5)
    0x1002A383U, // lw   x7, 0x100(x5)
    0x00038433U, // add  x8, x7, x0
    0x2002A483U, // lw   x9, 0x200(x5)
    0x00048533U, // add  x10, x9, x0
    0x3082A023U, // sw   x8, 0x300(x5)
    0x000005B3U, // add  x11, x0, x0
    0x02528633U, // mul  x12, x5, x5
    0x000006B3U, // add  x13, x0, x0
    0x00100713U, // addi x14, x0, 1
    0x00200793U, // addi x15, x0, 2
    0x0000000BU, // custom-0 halt
};

struct Sample {
  bool idu_loaduse;
  bool ifu_stall_pipeline;
  bool ifu_stall_axi;
  bool lsu_stall_write_b;
  bool exu_stall_lsu;
  bool mdu_active;
  bool mdu_wait;
};

static void edge(DUT &dut) {
  dut.clock = 1;
  dut.eval();
  dut.clock = 0;
  dut.eval();
}

static Sample sample(const DUT &dut) {
  return Sample{dut.perf_idu_stall_raw_loaduse != 0,
                dut.perf_ifu_stall_pipeline != 0,
                dut.perf_ifu_stall_axi != 0,
                dut.perf_lsu_stall_write_b != 0,
                dut.perf_exu_stall_lsu != 0,
                dut.perf_mdu_active != 0,
                dut.perf_mdu_wait != 0};
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  return run_test("full-core windowed stall attribution", [] {
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

    // 采集：每拍一个 Sample，并记录 retire 周期（debug_commit 为提交后一拍）。
    std::vector<Sample> samples;
    std::map<uint32_t, long> retire;
    long cycle = 0;
    for (; cycle < 4000; ++cycle) {
      dut.eval();
      samples.push_back(sample(dut));
      if (dut.debug_commit) {
        retire[dut.debug_pc] = cycle;
      }
      if (dut.trap_valid) {
        break;
      }
      edge(dut);
    }
    check(dut.trap_valid, "program did not reach the custom halt");

    auto at = [&](uint32_t pc) -> long {
      check(retire.count(pc) != 0,
            "instruction at 0x" + std::to_string(pc) + " never retired");
      return retire[pc];
    };
    auto window = [&](uint32_t from, uint32_t to) -> std::pair<long, long> {
      return {at(from), at(to)};
    };
    auto count = [&](const Sample &s, int which) -> int {
      switch (which) {
      case 0: return s.idu_loaduse;
      case 1: return s.ifu_stall_pipeline;
      case 2: return s.ifu_stall_axi;
      case 3: return s.lsu_stall_write_b;
      case 4: return s.exu_stall_lsu;
      case 5: return s.mdu_active;
      case 6: return s.mdu_wait;
      }
      return 0;
    };
    auto window_count = [&](uint32_t from, uint32_t to, int which) -> long {
      const auto [start, end] = window(from, to);
      long total = 0;
      for (long c = start; c < end && c < static_cast<long>(samples.size());
           ++c) {
        total += count(samples[c], which);
      }
      return total;
    };
    auto window_len = [&](uint32_t from, uint32_t to) -> long {
      const auto [start, end] = window(from, to);
      return end - start;
    };

    // 停顿发生在“生产者自己的窗口”里：消费者在 IDU 等数据期间，生产者
    // 正在 LSU 中执行；消费者总是紧跟在生产者之后退休（间隔 1 拍）。
    //
    // 窗口 A：命中 load-use —— lw x7 的执行窗口（0x04 -> 0x08）
    const long a_len = window_len(0x80000004U, 0x80000008U);
    const long a_loaduse = window_count(0x80000004U, 0x80000008U, 0);
    const long a_freeze = window_count(0x80000004U, 0x80000008U, 1);

    // 窗口 B：缺失 load-use —— lw x9 的执行窗口（0x0c -> 0x10）
    const long b_len = window_len(0x8000000CU, 0x80000010U);
    const long b_loaduse = window_count(0x8000000CU, 0x80000010U, 0);
    const long b_freeze = window_count(0x8000000CU, 0x80000010U, 1);

    // 窗口 C：store —— store 自己的窗口（0x14 -> 0x18）等 BRESP；
    // 其后的独立 add 窗口（0x18 -> 0x1c）被 EXU 阻塞。
    const long c_len = window_len(0x80000014U, 0x80000018U);
    const long c_wait_b = window_count(0x80000014U, 0x80000018U, 3);
    const long c_add_len = window_len(0x80000018U, 0x8000001CU);
    const long c_exu_stall = window_count(0x80000018U, 0x8000001CU, 4);

    // 窗口 D：MUL —— MUL 自己的窗口（0x1c -> 0x20）
    const long d_len = window_len(0x8000001CU, 0x80000020U);
    const long d_mdu_active = window_count(0x8000001CU, 0x80000020U, 5);
    const long d_mdu_wait = window_count(0x8000001CU, 0x80000020U, 6);

    // 窗口 E：独立 ALU 背靠背（0x28 -> 0x2c）
    const long e_len = window_len(0x80000028U, 0x8000002CU);

    std::cout << "[info] 命中 load-use 窗口：长度=" << a_len
              << " loaduse=" << a_loaduse << " 取指冻结=" << a_freeze << "\n";
    std::cout << "[info] 缺失 load-use 窗口：长度=" << b_len
              << " loaduse=" << b_loaduse << " 取指冻结=" << b_freeze << "\n";
    std::cout << "[info] store 窗口：长度=" << c_len << " 等BRESP=" << c_wait_b
              << "；其后的独立 add 窗口长度=" << c_add_len
              << " EXU阻塞=" << c_exu_stall << "\n";
    std::cout << "[info] MUL 窗口：长度=" << d_len
              << " mdu_active=" << d_mdu_active << " mdu_wait=" << d_mdu_wait
              << "\n";
    std::cout << "[info] 独立 ALU 背靠背：长度=" << e_len << "\n";

    // 确认各机制真实存在
    // 命中 load-use 实测不停顿：load 的数据在消费者到达冒险检查前已可转发，
    // 代价被 load 自身的流水延迟吸收（窗口长度 >=3）。
    check(a_loaduse == 0 && a_freeze == 0,
          "hit load-use unexpectedly stalled (loaduse=" +
              std::to_string(a_loaduse) + " freeze=" + std::to_string(a_freeze) +
              ")");
    check(a_len >= 3,
          "hit load window should be at least the load pipeline latency, got " +
              std::to_string(a_len));
    check(b_loaduse >= 1, "miss load-use did not stall the IDU");
    check(b_loaduse >= 1, "miss load-use did not stall the IDU");
    check(b_len > a_len,
          "miss load-use should take longer than hit load-use: miss=" +
              std::to_string(b_len) + " hit=" + std::to_string(a_len));
    check(b_freeze >= 1,
          "front-end was not frozen during the miss load-use stall");
    check(c_wait_b >= 1,
          "LSU never waited for BRESP behind the store (write_b cycles=" +
              std::to_string(c_wait_b) + ")");
    check(c_len >= 10,
          "store round trip should cost >=10 cycles, got " +
              std::to_string(c_len));
    check(c_wait_b >= 5,
          "store BRESP wait should be >=5 cycles, got " +
              std::to_string(c_wait_b));
    // 其后的独立 add 自己只花 1 拍（它在 store 的窗口里等待，不单独计停顿）。
    check(c_add_len == 1,
          "independent add behind the store should retire one cycle after it, "
          "got " + std::to_string(c_add_len));
    check(d_mdu_active >= 20,
          "MUL did not stay active for its full latency (active cycles=" +
              std::to_string(d_mdu_active) + ")");
    check(d_mdu_wait >= 20,
          "MUL wait cycles not attributed (wait cycles=" +
              std::to_string(d_mdu_wait) + ")");
    check(e_len == 1,
          "independent ALU back-to-back should be one cycle, got " +
              std::to_string(e_len));
    dut.final();
  });
}
