// 审计：BTB 多路（BTBWays>1）的表项共存与轮转替换。
//
// 背景：现有测试（btb_flush_test）只覆盖 BTBWays=1；Kconfig 允许 BTB_WAYS>1，
// 多路的“首空槽填充 + 组满后 repl_ptr 轮转替换 + 同 tag 覆盖不新建”路径
// 在仓库里没有任何执行证据。
//
// 本测试用 BTB_BITS=2 / BTB_WAYS=2 的 RTL 验证：
//   1. 同组（index 相同、tag 不同）的两个 PC 可以同时命中；
//   2. 组满后第三个 PC 替换 repl_ptr 指向的受害者（轮转），被替换者不再命中；
//   3. 再次写入已存在的 tag 只更新 target，不新建、不驱逐其他表项；
//   4. flush 清空全部路。
//
// 由 Makefile 用 ways=2 的 RTL 快照与 -DBTB_TWO_WAY 编译。

#include "Vysyx_26030103_BTB.h"
#include "test_common.hpp"

#include <cstdint>
#include <verilated.h>

using DUT = Vysyx_26030103_BTB;

#ifndef BTB_TWO_WAY
#error "BTB_TWO_WAY must be defined"
#endif

static void defaults(DUT &dut) {
  dut.io_lookup_pc = 0;
  dut.io_update_valid = 0;
  dut.io_update_pc = 0;
  dut.io_update_target = 0;
  dut.io_flush = 0;
}

// BTB 的 lookup/update 输入是 PC[31:2]（低位恒 0 不参与）。
static uint32_t pc_field(uint32_t pc) { return pc >> 2; }

static void update(DUT &dut, uint32_t pc, uint32_t target) {
  dut.io_update_pc = pc_field(pc);
  dut.io_update_target = target;
  dut.io_update_valid = 1;
  tick(dut);
  dut.io_update_valid = 0;
  dut.eval();
}

static bool hit(DUT &dut, uint32_t pc) {
  dut.io_lookup_pc = pc_field(pc);
  dut.eval();
  return dut.io_hit;
}

static uint32_t target_of(DUT &dut, uint32_t pc) {
  dut.io_lookup_pc = pc_field(pc);
  dut.eval();
  return dut.io_target;
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  return run_test("BTB two-way coexistence, round-robin victim, flush", [] {
    DUT dut;
    defaults(dut);
    reset(dut);

    // BTB_BITS=2：index = pc[3:2]；选同一 index、不同 tag 的三个 PC。
    constexpr uint32_t kA = 0x80000010U; // index 0
    constexpr uint32_t kB = 0x80000050U; // index 0（tag 不同）
    constexpr uint32_t kC = 0x80000090U; // index 0（tag 不同）

    update(dut, kA, 0x80001000U);
    check(hit(dut, kA), "A did not hit after its update");
    check(!hit(dut, kB), "B hit before being trained");

    // 1) 两路共存：A、B 同组不同 tag，都应命中。
    update(dut, kB, 0x80002000U);
    check(hit(dut, kA) && hit(dut, kB),
          "two-way BTB did not hold two same-set entries simultaneously");
    check(target_of(dut, kA) == 0x80001000U &&
              target_of(dut, kB) == 0x80002000U,
          "two-way BTB returned the wrong targets");

    // 2) 组满后写入 C：应替换 repl_ptr 指向的受害者（第一次替换从 way0 开始）。
    update(dut, kC, 0x80003000U);
    check(hit(dut, kC), "C did not hit after its update");
    check(hit(dut, kB), "C's allocation evicted B instead of the round-robin victim");
    check(!hit(dut, kA), "C's allocation did not evict the round-robin victim A");

    // 3) 再次训练 B：只更新 target，不驱逐 C。
    update(dut, kB, 0x80002500U);
    check(hit(dut, kB) && target_of(dut, kB) == 0x80002500U,
          "re-training an existing tag did not refresh its target");
    check(hit(dut, kC), "re-training an existing tag evicted another way");

    // 4) flush 清空所有路。
    dut.io_flush = 1;
    tick(dut);
    dut.io_flush = 0;
    dut.eval();
    check(!hit(dut, kA) && !hit(dut, kB) && !hit(dut, kC),
          "BTB flush left a stale entry behind");
    dut.final();
  });
}
