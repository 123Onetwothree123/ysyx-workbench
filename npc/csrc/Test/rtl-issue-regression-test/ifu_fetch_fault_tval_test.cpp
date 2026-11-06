// 定向回归：C 模式下，32 位指令位于 ...ffe，其第二个字 ...1000 取指失败。
// IFU 必须保持 pc/sepc=...ffe，同时把实际故障取指地址 ...1000 通过
// pred_target 透传（异常指令不使用预测目标），供 EXU 写 stval/mtval。

#include "VIFUFetchFaultHarness.h"
#include "test_common.hpp"

#include <cstdint>
#include <verilated.h>

using DUT = VIFUFetchFaultHarness;

static void defaults(DUT &dut) {
  dut.io_FetchReady = 0;
  dut.io_RespData = 0;
  dut.io_RespValid = 0;
  dut.io_RespExceptionValid = 0;
  dut.io_RespExceptionCause = 0;
  dut.io_RespAccessFaultResp = 0;
  dut.io_RedirectTarget = 0;
  dut.io_Redirect = 0;
  dut.io_ExceptionTaken = 0;
  dut.io_ExceptionTarget = 0;
  dut.io_PredHit = 0;
  dut.io_PredTarget = 0;
  dut.io_OutReady = 0;
}

static void cross_word_second_half_fault_keeps_pc_and_fault_va() {
  DUT dut;
  defaults(dut);
  reset(dut);

  constexpr std::uint32_t instruction_pc = 0x80000ffeU;
  constexpr std::uint32_t first_word = 0x80000ffcU;
  constexpr std::uint32_t second_word = 0x80001000U;

  dut.eval();
  check(dut.io_FetchValid, "IFU did not start fetching");
  check(dut.io_FetchAddr == first_word,
        "IFU did not fetch the word containing the instruction start");
  check(dut.io_FetchPC == instruction_pc, "IFU lost the instruction PC");

  // 取回第一个字：高半字是 32 位指令的低 16 位（低两位=11）。
  dut.io_FetchReady = 1;
  tick(dut);
  dut.io_FetchReady = 0;
  dut.io_RespData = 0x0013U << 16;
  dut.io_RespValid = 1;
  dut.io_OutReady = 1;
  dut.eval();
  check(dut.io_RespReady, "IFU did not consume the first half-word");
  check(!dut.io_OutValid, "IFU emitted an incomplete 32-bit instruction");
  tick(dut);
  dut.io_RespValid = 0;
  dut.eval();

  // 第二个字位于下一页/下一个 cache line。
  check(dut.io_FetchValid, "IFU did not fetch the second word");
  check(dut.io_FetchAddr == second_word,
        "IFU fetched the wrong second word address");
  check(dut.io_FetchPC == instruction_pc,
        "IFU advanced the architectural PC before the instruction completed");

  dut.io_FetchReady = 1;
  tick(dut);
  dut.io_FetchReady = 0;
  dut.io_RespValid = 1;
  dut.io_RespExceptionValid = 1;
  dut.io_RespExceptionCause = 1;
  dut.io_RespData = 0;
  dut.io_OutReady = 1;
  dut.eval();

  check(dut.io_OutValid, "IFU did not deliver the fetch fault");
  check(dut.io_OutExceptionValid, "IFU fault response lost ExceptionValid");
  check(dut.io_OutExceptionCause == 1, "IFU fault response lost cause=1");
  check(dut.io_OutPC == instruction_pc,
        "fetch fault changed the instruction PC used for sepc");
  check(dut.io_OutPredTarget == second_word,
        "fetch fault did not carry the faulting fetch VA for stval");
  check(!dut.io_OutPredTaken,
        "fetch fault reused pred_taken for the fault VAddr carrier");
  check(dut.io_OutInstruction == 0x00000013U,
        "fetch fault instruction was not replaced by a NOP");
  check(dut.io_RespReady, "IFU did not accept the fault response");
  tick(dut);
  dut.io_RespValid = 0;
  dut.io_RespExceptionValid = 0;
  dut.io_OutReady = 0;
  dut.final();
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  int failures = 0;
  failures += run_test("C-mode cross-word fault keeps sepc PC and fault VA",
                       cross_word_second_half_fault_keeps_pc_and_fault_va);
  return failures == 0 ? 0 : 1;
}
