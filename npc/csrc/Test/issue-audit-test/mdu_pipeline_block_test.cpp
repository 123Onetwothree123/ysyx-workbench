// 设计审计：MDU 占用 EXU 唯一输出槽，导致在飞期间整条流水线无法前进。
//
// 机制（EXU.scala）：io.in.ready 的非 MDU 分支要求 !PendingMDU，注释说明
// "待处理的MDU结果会占用本周期唯一的输出槽位"。因此只要有一笔 MDU 在飞
// （MUL shift-add 延迟 33 拍、DIV 同理），任何年轻指令——包括与结果完全
// 无关的 ALU 指令——都不能进入 EXU。
//
// 本测试量化该阻塞：发射一条 MUL 后立即给一条独立的 ADD，统计 ADD 被
// 阻塞的拍数（应约等于 MUL 延迟），并确认 MUL 结果发出后 ADD 才能进入。
// 这是设计取舍（单一输出槽 + 顺序退休），不是功能缺陷；记录以支撑
// "是否让独立指令越过 MDU" 的设计决策。

#include "Vysyx_26030103_EXU.h"
#include "test_common.hpp"

#include <cstdint>
#include <string>
#include <verilated.h>

using DUT = Vysyx_26030103_EXU;

static void defaults(DUT &dut) {
  dut.io_in_valid = 0;
  dut.io_out_ready = 1;
  dut.io_in_bits_Instruction = 0x00000013U;
  dut.io_in_bits_pc = 0x80000000U;
  dut.io_in_bits_snpc = 0x80000004U;
  dut.io_in_bits_ALUCtrl = 15;
  dut.io_in_bits_IsMDU = 0;
  dut.io_in_bits_MDUOp = 0;
  dut.io_in_bits_ALU_A = 0;
  dut.io_in_bits_ALU_B = 0;
  dut.io_in_bits_BranchA = 0;
  dut.io_in_bits_BranchB = 0;
  dut.io_in_bits_BranchFunct3 = 0;
  dut.io_in_bits_IsBranch = 0;
  dut.io_in_bits_IsJal = 0;
  dut.io_in_bits_IsJalr = 0;
  dut.io_in_bits_Immediate = 0;
  dut.io_in_bits_Rd = 0;
  dut.io_in_bits_RegisterWrite = 0;
  dut.io_in_bits_WBSelect = 0;
  dut.io_in_bits_MemoryValid = 0;
  dut.io_in_bits_MemoryWrite = 0;
  dut.io_in_bits_WidthSelect = 0;
  dut.io_in_bits_LoadSigned = 0;
  dut.io_in_bits_StoreData = 0;
  dut.io_in_bits_IsCsrrw = 0;
  dut.io_in_bits_IsCsrrs = 0;
  dut.io_in_bits_IsCsrrc = 0;
  dut.io_in_bits_IsEcall = 0;
  dut.io_in_bits_IsEbreak = 0;
  dut.io_in_bits_IsSimHalt = 0;
  dut.io_in_bits_IsMret = 0;
  dut.io_in_bits_IsFence = 0;
  dut.io_in_bits_IsFenceI = 0;
  dut.io_in_bits_CSRAddress = 0;
  dut.io_in_bits_Rs1 = 0;
  dut.io_in_bits_Rs1Data = 0;
  dut.io_in_bits_ExceptionValid = 0;
  dut.io_in_bits_ExceptionCause = 0;
  dut.io_in_bits_pred_taken = 0;
  dut.io_in_bits_pred_target = 0x80000004U;
  dut.io_Interrupt = 0;
  dut.io_IdleIrqPC = 0x80000000U;
  dut.io_MEMBusy = 0;
  dut.io_PipelineBusy = 0;
  dut.io_MemTrapCommit = 0;
  dut.io_MemTrapCause = 0;
  dut.io_MemTrapPC = 0;
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  return run_test("in-flight MDU blocks independent younger instructions", [] {
    DUT dut;
    defaults(dut);
    reset(dut);

    // 1) 发射一条 MUL（MDUOp=0），rd=x5。
    dut.io_in_valid = 1;
    dut.io_in_bits_Instruction = 0x025282B3U; // mul x5, x5, x5 形状（编码不重要）
    dut.io_in_bits_IsMDU = 1;
    dut.io_in_bits_MDUOp = 0; // MUL
    dut.io_in_bits_ALU_A = 7;
    dut.io_in_bits_ALU_B = 9;
    dut.io_in_bits_Rd = 5;
    dut.io_in_bits_RegisterWrite = 1;
    dut.eval();
    check(dut.io_in_ready, "EXU did not accept the MUL");
    tick(dut);
    check(dut.io_PerfMDUActive, "MUL was not marked as in flight");
    dut.eval();

    // 2) 立即给一条与 MUL 结果无关的 ADD（x1+x2 -> x6）。
    dut.io_in_bits_IsMDU = 0;
    dut.io_in_bits_MDUOp = 0;
    dut.io_in_bits_ALUCtrl = 0; // ADD
    dut.io_in_bits_ALU_A = 1;
    dut.io_in_bits_ALU_B = 2;
    dut.io_in_bits_Rd = 6;
    dut.io_in_bits_RegisterWrite = 1;
    dut.eval();

    int blocked_cycles = 0;
    bool mul_result_seen = false;
    for (int cycle = 0; cycle < 128; ++cycle) {
      dut.eval();
      if (dut.io_out_valid && !mul_result_seen) {
        mul_result_seen = true;
        // MUL 结果（7*9=63）先出；消费它。
        check(dut.io_out_bits_ALUResult == 63U,
              "MUL result is wrong: got " + std::to_string(dut.io_out_bits_ALUResult));
        tick(dut);
        continue;
      }
      if (!mul_result_seen) {
        if (!dut.io_in_ready) {
          blocked_cycles++;
        }
      } else {
        // MUL 已退休：ADD 现在必须能进入。
        check(dut.io_in_ready,
              "independent ADD still blocked after the MUL retired");
        break;
      }
      tick(dut);
    }

    check(mul_result_seen, "MUL never produced a result");
#ifdef TEST_FAST_MDU
    // 快配置（booth/iter=4/split=2/early-out）：实测阻塞仅 3 拍——结构性
    // 阻塞仍在（>0），但代价比生产配置（plain/iter=1，实测 32 拍）小一个
    // 数量级。阈值放宽到 >=1 只断言"确实存在阻塞"。
    check(blocked_cycles >= 1,
#else
    check(blocked_cycles >= 16,
#endif
          "expected the in-flight MDU to block the pipeline for many cycles, "
          "observed " + std::to_string(blocked_cycles));
#ifdef TEST_FAST_MDU
    std::cout << "[info] 快配置（booth/iter=4/early-out）：独立 ADD 在 MUL "
              << "在飞期间被阻塞 " << blocked_cycles << " 拍\n";
#else
    std::cout << "[info] 生产配置（plain/iter=1）：独立 ADD 在 MUL 在飞期间"
              << "被阻塞 " << blocked_cycles << " 拍（MUL 延迟约 33 拍）\n";
#endif
    dut.final();
  });
}
