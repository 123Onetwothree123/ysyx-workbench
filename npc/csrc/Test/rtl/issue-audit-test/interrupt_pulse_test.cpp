// 审计：EXU 中断输入没有锁存（没有 mip 语义）。
//
// 疑点（来自静态审计）：HasInterrupt = io.Interrupt && mstatus.MIE，纯电平
// 采样；一旦中断线在“提交窗口被阻塞”期间只拉高一拍，就会静默丢失。
// 本测试：
//   1. 在 MEMBusy=1 阻塞期间给出一拍中断脉冲，随后撤销并放开 MEM；
//      验证中断被丢弃（无 trap），指令正常退休；
//   2. 对照：中断电平保持到阻塞解除，验证 trap 正常提交。
//
// 说明：RISC-V 的中断模型要求平台把中断线保持到被响应（电平语义），
// 因此本行为对“合规的中断控制器”无害；风险在于脉冲型中断源。

#include "Vysyx_26030103_EXU.h"
#include "test_common.hpp"

#include <cstdint>
#include <iostream>
#include <stdexcept>
#include <string>
#include <verilated.h>

namespace {

constexpr uint16_t kMstatus = 0x300;
constexpr uint16_t kMtvec = 0x305;
constexpr uint32_t kMachineExternalInterrupt = 0x8000000bU;

class ExuTb {
public:
  ExuTb() : dut_(std::make_unique<Vysyx_26030103_EXU>()) {
    driveIdle();
    reset();
  }
  ~ExuTb() { dut_->final(); }

  Vysyx_26030103_EXU &dut() { return *dut_; }

  void eval() { dut_->eval(); }

  void tick() {
    dut_->clock = 0;
    dut_->eval();
    dut_->clock = 1;
    dut_->eval();
    dut_->clock = 0;
    dut_->eval();
  }

  void reset() {
    driveIdle();
    dut_->reset = 1;
    tick();
    tick();
    dut_->reset = 0;
    dut_->eval();
  }

  void driveIdle() {
    dut_->clock = 0;
    dut_->reset = 0;
    dut_->io_in_valid = 0;
    dut_->io_out_ready = 1;
    dut_->io_in_bits_Instruction = 0x00000013U;
    dut_->io_in_bits_pc = 0x80000000U;
    dut_->io_in_bits_snpc = 0x80000004U;
    dut_->io_in_bits_ALUCtrl = 15;
    dut_->io_in_bits_IsMDU = 0;
    dut_->io_in_bits_MDUOp = 0;
    dut_->io_in_bits_ALU_A = 0;
    dut_->io_in_bits_ALU_B = 0;
    dut_->io_in_bits_BranchA = 0;
    dut_->io_in_bits_BranchB = 0;
    dut_->io_in_bits_BranchFunct3 = 0;
    dut_->io_in_bits_IsBranch = 0;
    dut_->io_in_bits_IsJal = 0;
    dut_->io_in_bits_IsJalr = 0;
    dut_->io_in_bits_Immediate = 0;
    dut_->io_in_bits_Rd = 0;
    dut_->io_in_bits_RegisterWrite = 0;
    dut_->io_in_bits_WBSelect = 0;
    dut_->io_in_bits_MemoryValid = 0;
    dut_->io_in_bits_MemoryWrite = 0;
    dut_->io_in_bits_WidthSelect = 0;
    dut_->io_in_bits_LoadSigned = 0;
    dut_->io_in_bits_StoreData = 0;
    dut_->io_in_bits_IsCsrrw = 0;
    dut_->io_in_bits_IsCsrrs = 0;
    dut_->io_in_bits_IsCsrrc = 0;
    dut_->io_in_bits_IsEcall = 0;
    dut_->io_in_bits_IsEbreak = 0;
    dut_->io_in_bits_IsSimHalt = 0;
    dut_->io_in_bits_IsMret = 0;
    dut_->io_in_bits_IsFence = 0;
    dut_->io_in_bits_IsFenceI = 0;
    dut_->io_in_bits_CSRAddress = 0;
    dut_->io_in_bits_Rs1 = 0;
    dut_->io_in_bits_Rs1Data = 0;
    dut_->io_in_bits_ExceptionValid = 0;
    dut_->io_in_bits_ExceptionCause = 0;
    dut_->io_in_bits_pred_taken = 0;
    dut_->io_in_bits_pred_target = 0x80000004U;
    dut_->io_Interrupt = 0;
    dut_->io_IdleIrqPC = 0x80000000U;
    dut_->io_MEMBusy = 0;
    dut_->io_PipelineBusy = 0;
    dut_->io_MemTrapCommit = 0;
    dut_->io_MemTrapCause = 0;
    dut_->io_MemTrapPC = 0;
  }

  void beginAdd(uint32_t pc = 0x80000040U) {
    driveIdle();
    dut_->io_in_valid = 1;
    dut_->io_in_bits_pc = pc;
    dut_->io_in_bits_snpc = pc + 4U;
    dut_->io_in_bits_pred_target = pc + 4U;
    dut_->io_in_bits_ALUCtrl = 0; // ADD
    dut_->io_in_bits_ALU_A = 1;
    dut_->io_in_bits_ALU_B = 2;
    dut_->io_in_bits_Rd = 5;
    dut_->io_in_bits_RegisterWrite = 1;
    dut_->io_in_bits_WBSelect = 0;
  }

  void csrWrite(uint16_t address, uint32_t value) {
    beginAdd();
    dut_->io_in_bits_IsCsrrw = 1;
    dut_->io_in_bits_CSRAddress = address;
    dut_->io_in_bits_Rs1 = 1;
    dut_->io_in_bits_Rs1Data = value;
    dut_->io_in_bits_WBSelect = 3;
    dut_->eval();
    check(dut_->io_in_ready && dut_->io_out_valid, "CSR write did not fire");
    tick();
    driveIdle();
    dut_->eval();
  }

  uint32_t csrRead(uint16_t address) {
    beginAdd();
    dut_->io_in_bits_IsCsrrs = 1;
    dut_->io_in_bits_CSRAddress = address;
    dut_->io_in_bits_WBSelect = 3;
    dut_->eval();
    check(dut_->io_in_ready && dut_->io_out_valid, "CSR read did not fire");
    const uint32_t value = dut_->io_out_bits_CSRReadData;
    tick();
    driveIdle();
    dut_->eval();
    return value;
  }

private:
  std::unique_ptr<Vysyx_26030103_EXU> dut_;
};

} // namespace

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  return run_test("EXU interrupt pulse handling", [] {
    ExuTb tb;
    tb.csrWrite(kMtvec, 0x00000100U);
    tb.csrWrite(kMstatus, 1U << 3); // MIE=1

    // --- 1) 阻塞窗口内的一拍脉冲：锁存后必须在解除阻塞时提交 ---
    tb.beginAdd(0x80000040U);
    tb.dut().io_MEMBusy = 1;
    tb.dut().io_Interrupt = 1;
    tb.eval();
    check(!tb.dut().io_ExceptionTaken,
          "IRQ committed before the older memory operation completed");
    tb.tick(); // 整个阻塞周期里中断线只维持了这一拍
    tb.dut().io_Interrupt = 0;
    tb.dut().io_MEMBusy = 0;
    tb.eval();

    // 锁存（mip 语义）后，脉冲消失不影响 pending：阻塞解除即提交。
    check(tb.dut().io_ExceptionTaken,
          "one-cycle IRQ pulse was lost after the block cleared");
    check(tb.dut().io_ExceptionTarget == 0x00000100U,
          "latched pulse IRQ target mismatch");
    check(!tb.dut().io_out_bits_RegisterWrite,
          "interrupted instruction was allowed to write a register");
    tb.tick(); // 提交
    tb.driveIdle();
    tb.eval();
    check(tb.csrRead(0x342) == kMachineExternalInterrupt,
          "latched pulse IRQ mcause");
    check(tb.csrRead(0x341) == 0x80000040U, "latched pulse IRQ mepc");

    // 提交后锁存清除且 MIE=0：不得出现第二次 trap。
    for (int cycle = 0; cycle < 4; ++cycle) {
      tb.tick();
      tb.eval();
      check(!tb.dut().io_ExceptionTaken && !tb.dut().io_TrapCommit,
            "latched IRQ fired twice after its commit");
    }

    // --- 2) 对照：中断电平保持到阻塞解除，trap 正常提交 ---
    // 阶段 1 的 trap 已把 MIE 清零，这里重新使能。
    tb.csrWrite(kMstatus, 1U << 3);
    tb.beginAdd(0x80000050U);
    tb.dut().io_MEMBusy = 1;
    tb.dut().io_Interrupt = 1;
    tb.eval();
    check(!tb.dut().io_ExceptionTaken,
          "IRQ committed before the older memory operation completed");
    tb.tick();
    tb.dut().io_MEMBusy = 0;
    tb.eval();
    check(tb.dut().io_ExceptionTaken,
          "level-held IRQ was not committed after the block cleared");
    check(tb.dut().io_ExceptionTarget == 0x00000100U, "IRQ target mismatch");
    check(!tb.dut().io_out_bits_RegisterWrite,
          "interrupted instruction was allowed to write a register");
    // 时钟沿前必须保持中断电平：trap 的 CSR 更新发生在提交的那一拍。
    tb.tick();
    tb.dut().io_Interrupt = 0;
    tb.driveIdle();
    tb.eval();
    check(tb.csrRead(0x342) == kMachineExternalInterrupt, "IRQ mcause");
    check(tb.csrRead(0x341) == 0x80000050U, "IRQ mepc");
  });
}
