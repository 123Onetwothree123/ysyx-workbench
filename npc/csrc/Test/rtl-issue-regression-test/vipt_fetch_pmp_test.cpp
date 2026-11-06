// 定向回归：MMU_CACHE_MODE=vipt 且处于 Bare/M bypass 时，PMP 拒绝的取指
// 必须仍然经过 IFUMMU 检查并产生 instruction access fault(cause=1)。
//
// 旧连接直接把 immuBypass 当作 fetch_paddr_valid，ICache 会在 IFUMMU
// 看到 fetchValid 之前就受理 VA 请求；PMP 拒绝因此完全失效。本测试用
// 与修复后顶层相同的 ICache/IFUMMU 连接验证：
//   * pmpFault=1 时不得返回指令，必须 fault，cause=1，faultVaddr 为取指 VA；
//   * pmpFault=0 时正常受理且不产生 fault。

#include "VViptFetchPmpHarness.h"
#include "test_common.hpp"

#include <cstdint>
#include <verilated.h>

using DUT = VViptFetchPmpHarness;

static void defaults(DUT &dut) {
  dut.io_fetchValid = 0;
  dut.io_fetchAddr = 0;
  dut.io_bypass = 1;
  dut.io_pmpFault = 0;
  dut.io_kill = 0;
  dut.io_respReady = 0;
  dut.io_axiARReady = 0;
}

static void bypass_pmp_denied_fetch_faults() {
  DUT dut;
  defaults(dut);
  reset(dut);

  constexpr std::uint32_t fetch_va = 0x80001000U;
  dut.io_fetchValid = 1;
  dut.io_fetchAddr = fetch_va;
  dut.io_pmpFault = 1;
  dut.eval();
  check(dut.io_fetchReady, "VIPT ICache rejected the VA request before PMP");
  tick(dut);
  dut.io_fetchValid = 0;
  dut.eval();

  check(dut.io_fault,
        "PMP-denied VIPT bypass fetch did not raise an instruction fault");
  check(dut.io_faultCause == 1,
        "PMP-denied VIPT bypass fetch used the wrong fault cause");
  check(dut.io_faultVaddr == fetch_va,
        "PMP-denied VIPT bypass fetch reported the wrong fault VAddr");
  check(!dut.io_respValid,
        "PMP-denied VIPT bypass fetch produced a cache response");
  check(!dut.io_axiARValid,
        "PMP-denied VIPT bypass fetch issued an AXI refill");
  dut.final();
}

static void bypass_pmp_allowed_fetch_proceeds() {
  DUT dut;
  defaults(dut);
  reset(dut);

  constexpr std::uint32_t fetch_va = 0x80002000U;
  dut.io_fetchValid = 1;
  dut.io_fetchAddr = fetch_va;
  dut.io_pmpFault = 0;
  for (int cycle = 0; cycle < 4; ++cycle) {
    dut.eval();
    check(!dut.io_fault,
          "allowed VIPT bypass fetch unexpectedly raised a fault");
    check(!dut.io_respFault,
          "allowed VIPT bypass fetch reported a cache access fault");
    tick(dut);
  }
  dut.final();
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  int failures = 0;
  failures += run_test("VIPT bypass fetch obeys PMP denial",
                       bypass_pmp_denied_fetch_faults);
  failures += run_test("VIPT bypass fetch still proceeds when PMP allows",
                       bypass_pmp_allowed_fetch_proceeds);
  return failures == 0 ? 0 : 1;
}
