#include "Vysyx_26030103_EXU.h"

#include <verilated.h>

#include <cstdint>
#include <functional>
#include <iomanip>
#include <iostream>
#include <memory>
#include <sstream>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace {

constexpr std::uint16_t kMstatus = 0x300;
constexpr std::uint16_t kMtvec = 0x305;
constexpr std::uint16_t kMepc = 0x341;
constexpr std::uint16_t kMcause = 0x342;
constexpr std::uint32_t kMachineTimerInterrupt = 0x80000007U;

std::string hex32(std::uint32_t value) {
  std::ostringstream stream;
  stream << "0x" << std::hex << std::setw(8) << std::setfill('0') << value;
  return stream.str();
}

[[noreturn]] void fail(const char *file, int line, const std::string &message) {
  std::ostringstream stream;
  stream << file << ':' << line << ": " << message;
  throw std::runtime_error(stream.str());
}

#define CHECK(condition, message)                                              \
  do {                                                                         \
    if (!(condition)) {                                                        \
      fail(__FILE__, __LINE__, (message));                                     \
    }                                                                          \
  } while (false)

#define CHECK_EQ(actual, expected, label)                                      \
  do {                                                                         \
    const std::uint32_t actual_value = static_cast<std::uint32_t>(actual);     \
    const std::uint32_t expected_value = static_cast<std::uint32_t>(expected); \
    if (actual_value != expected_value) {                                      \
      fail(__FILE__, __LINE__,                                                 \
           std::string(label) + ": expected " + hex32(expected_value) +        \
               ", got " + hex32(actual_value));                                \
    }                                                                          \
  } while (false)

class ExuTestbench {
public:
  ExuTestbench()
      : context_(std::make_unique<VerilatedContext>()),
        dut_(std::make_unique<Vysyx_26030103_EXU>(context_.get())) {
    driveIdle();
    reset();
  }

  ~ExuTestbench() { dut_->final(); }

  Vysyx_26030103_EXU &dut() { return *dut_; }

  void eval() { dut_->eval(); }

  void tick() {
    dut_->clock = 0;
    dut_->eval();
    context_->timeInc(1);
    dut_->clock = 1;
    dut_->eval();
    context_->timeInc(1);
    dut_->clock = 0;
    dut_->eval();
    context_->timeInc(1);
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
    dut_->io_MEMBusy = 0;
    dut_->io_PipelineBusy = 0;
    dut_->io_MemTrapCommit = 0;
    dut_->io_MemTrapCause = 0;
    dut_->io_MemTrapPC = 0;
  }

  void beginInstruction(std::uint32_t pc = 0x80000000U) {
    driveIdle();
    dut_->io_in_valid = 1;
    dut_->io_in_bits_pc = pc;
    dut_->io_in_bits_snpc = pc + 4U;
    dut_->io_in_bits_pred_target = pc + 4U;
  }

  void requireFire(const std::string &label) {
    dut_->eval();
    CHECK(dut_->io_in_ready, label + ": input was not ready");
    CHECK(dut_->io_out_valid, label + ": output was not valid");
    tick();
    driveIdle();
    dut_->eval();
  }

  void csrWrite(std::uint16_t address, std::uint32_t value) {
    beginInstruction();
    dut_->io_in_bits_IsCsrrw = 1;
    dut_->io_in_bits_CSRAddress = address;
    dut_->io_in_bits_Rs1 = 1;
    dut_->io_in_bits_Rs1Data = value;
    dut_->io_in_bits_WBSelect = 3;
    requireFire("CSR write " + hex32(address));
  }

  std::uint32_t csrRead(std::uint16_t address) {
    beginInstruction();
    dut_->io_in_bits_IsCsrrs = 1;
    dut_->io_in_bits_CSRAddress = address;
    dut_->io_in_bits_Rs1 = 0;
    dut_->io_in_bits_Rs1Data = 0;
    dut_->io_in_bits_WBSelect = 3;
    dut_->eval();
    CHECK(dut_->io_in_ready, "CSR read input was not ready");
    CHECK(dut_->io_out_valid, "CSR read output was not valid");
    const std::uint32_t value = dut_->io_out_bits_CSRReadData;
    tick();
    driveIdle();
    dut_->eval();
    return value;
  }

private:
  std::unique_ptr<VerilatedContext> context_;
  std::unique_ptr<Vysyx_26030103_EXU> dut_;
};

void checkCauseZeroAfterTrap(ExuTestbench &tb, const std::string &label,
                             std::uint32_t pc) {
  CHECK(tb.dut().io_ExceptionTaken, label + ": trap was not taken");
  CHECK_EQ(tb.dut().io_ExceptionTarget, 0x00000100U, label + " trap target");
  CHECK(!tb.dut().io_Redirect, label + ": redirect leaked alongside trap");
  CHECK(!tb.dut().io_out_bits_RegisterWrite,
        label + ": register write was not suppressed");
  CHECK(!tb.dut().io_out_bits_Retire,
        label + ": faulting instruction was marked retired");
  CHECK(!tb.dut().io_BTBUpdateValid,
        label + ": branch predictor update was not suppressed");
  CHECK(!tb.dut().io_JalBTBUpdateValid,
        label + ": JAL predictor update was not suppressed");

  tb.tick();
  tb.driveIdle();
  tb.eval();
  CHECK_EQ(tb.csrRead(kMcause), 0U, label + " mcause");
  CHECK_EQ(tb.csrRead(kMepc), pc & ~3U, label + " mepc");
}

void testIrqWaitsForOlderMemory() {
  ExuTestbench tb;
  tb.csrWrite(kMtvec, 0x00000100U);
  tb.csrWrite(kMstatus, 1U << 3);

  tb.beginInstruction(0x80000040U);
  tb.dut().io_in_bits_RegisterWrite = 1;
  tb.dut().io_in_bits_Rd = 5;
  tb.dut().io_Interrupt = 1;
  tb.dut().io_MEMBusy = 1;
  tb.eval();

  CHECK(!tb.dut().io_in_ready,
        "IRQ-pending instruction bypassed an older busy MEM stage");
  CHECK(!tb.dut().io_out_valid,
        "blocked IRQ-pending instruction appeared at the EXU output");
  CHECK(!tb.dut().io_ExceptionTaken,
        "IRQ committed before the older memory operation completed");

  // Keep the request stable for a full blocked cycle, as a real Decoupled
  // producer does, then let the older memory instruction retire.
  tb.tick();
  tb.dut().io_MEMBusy = 0;
  tb.eval();
  CHECK(tb.dut().io_in_ready, "instruction did not resume after MEM drained");
  CHECK(tb.dut().io_out_valid, "resumed instruction did not reach EXU output");
  CHECK(tb.dut().io_ExceptionTaken, "pending IRQ was lost while MEM was busy");
  CHECK_EQ(tb.dut().io_ExceptionTarget, 0x00000100U, "direct IRQ target");
  CHECK(!tb.dut().io_out_bits_RegisterWrite,
        "interrupted instruction was allowed to write a register");
  CHECK(!tb.dut().io_out_bits_Retire,
        "interrupted instruction was marked retired");

  tb.tick();
  tb.driveIdle();
  tb.eval();
  CHECK_EQ(tb.csrRead(kMcause), kMachineTimerInterrupt, "IRQ mcause");
  CHECK_EQ(tb.csrRead(kMepc), 0x80000040U, "IRQ mepc");
}

void testIrqPreemptsCurrentMemoryAfterDrain() {
  for (const bool is_store : {false, true}) {
    const std::string label = is_store ? "store" : "load";
    ExuTestbench tb;
    tb.csrWrite(kMtvec, 0x00000100U);
    tb.csrWrite(kMstatus, 1U << 3);

    const std::uint32_t pc = is_store ? 0x80000068U : 0x80000060U;
    tb.beginInstruction(pc);
    tb.dut().io_in_bits_MemoryValid = 1;
    tb.dut().io_in_bits_MemoryWrite = is_store;
    tb.dut().io_in_bits_RegisterWrite = !is_store;
    tb.dut().io_in_bits_Rd = is_store ? 0 : 10;
    tb.dut().io_Interrupt = 1;
    tb.dut().io_MEMBusy = 1;
    tb.eval();

    CHECK(!tb.dut().io_in_ready,
          label + " bypassed busy MEM while an IRQ was pending");
    CHECK(!tb.dut().io_out_valid,
          label + " became visible while an older MEM operation was busy");
    CHECK(!tb.dut().io_ExceptionTaken,
          label + " committed IRQ before the older MEM operation drained");

    // Keep the current memory instruction stable. Once older MEM work drains,
    // the IRQ must commit before this load/store is allowed to execute.
    tb.tick();
    tb.dut().io_MEMBusy = 0;
    tb.eval();
    CHECK(tb.dut().io_in_ready && tb.dut().io_out_valid,
          label + " did not reach the post-MEM IRQ boundary");
    CHECK(tb.dut().io_ExceptionTaken,
          label + " was allowed past an already-pending IRQ");
    CHECK(tb.dut().io_PerfTrap, label + " IRQ entry was not counted as a trap");
    CHECK(!tb.dut().io_out_bits_MemoryValid,
          label + " MemoryValid leaked through an IRQ commit");
    CHECK(!tb.dut().io_out_bits_MemoryWrite,
          label + " MemoryWrite leaked through an IRQ commit");
    CHECK(!tb.dut().io_out_bits_RegisterWrite,
          label + " register write leaked through an IRQ commit");
    CHECK(!tb.dut().io_out_bits_Retire,
          label + " was marked retired despite IRQ preemption");

    tb.tick();
    tb.driveIdle();
    tb.eval();
    CHECK_EQ(tb.csrRead(kMcause), kMachineTimerInterrupt,
             label + " IRQ mcause");
    CHECK_EQ(tb.csrRead(kMepc), pc, label + " IRQ mepc");
  }
}

void testPendingMduWaitsForOlderMemory() {
  ExuTestbench tb;
  tb.csrWrite(kMtvec, 0x00000100U);
  tb.csrWrite(kMstatus, 1U << 3);

  tb.beginInstruction(0x80000050U);
  tb.dut().io_in_bits_IsMDU = 1;
  tb.dut().io_in_bits_MDUOp = 0; // MUL
  tb.dut().io_in_bits_ALU_A = 7;
  tb.dut().io_in_bits_ALU_B = 9;
  tb.dut().io_in_bits_RegisterWrite = 1;
  tb.dut().io_in_bits_Rd = 6;
  tb.eval();
  CHECK(tb.dut().io_PerfMDUReq, "MUL request was not accepted by the MDU");
  CHECK(tb.dut().io_in_ready,
        "queued MUL request did not consume its EXU input");
  tb.tick();

  // The M request is now owned by EXU.  Present its younger successor while the
  // iterative multiplier finishes with an older memory operation and an
  // enabled interrupt both present.
  tb.beginInstruction(0x80000054U);
  tb.dut().io_Interrupt = 1;
  tb.dut().io_MEMBusy = 1;
  for (int cycle = 0; cycle < 40; ++cycle) {
    tb.tick();
  }
  tb.eval();

  CHECK(tb.dut().io_PerfMDUActive, "pending MUL was lost while MEM was busy");
  CHECK(!tb.dut().io_in_ready,
        "completed MUL bypassed an older busy MEM stage");
  CHECK(!tb.dut().io_out_valid,
        "completed MUL result became visible while MEM was busy");
  CHECK(!tb.dut().io_ExceptionTaken,
        "IRQ attached to pending MUL committed while MEM was busy");

  tb.dut().io_MEMBusy = 0;
  tb.eval();
  CHECK(!tb.dut().io_in_ready,
        "younger input bypassed the completed queue-head MUL");
  CHECK(tb.dut().io_out_valid,
        "completed MUL result was not available after MEM drained");
  CHECK_EQ(tb.dut().io_out_bits_ALUResult, 63U, "held MUL result");
  CHECK(!tb.dut().io_ExceptionTaken,
        "IRQ incorrectly preempted an already-issued completed MUL");
  CHECK(tb.dut().io_out_bits_RegisterWrite && tb.dut().io_out_bits_Retire,
        "completed MUL did not retire before the pending IRQ");

  tb.tick();
  tb.eval();
  CHECK(tb.dut().io_in_ready && tb.dut().io_ExceptionTaken,
        "pending IRQ was not accepted at the next instruction boundary");
  CHECK_EQ(tb.dut().io_ExceptionTarget, 0x00000100U, "pending-MDU IRQ target");
  CHECK(!tb.dut().io_out_bits_RegisterWrite && !tb.dut().io_out_bits_Retire,
        "IRQ-preempted younger instruction leaked side effects");

  tb.tick();
  tb.driveIdle();
  tb.eval();
  CHECK_EQ(tb.csrRead(kMcause), kMachineTimerInterrupt,
           "pending-MDU IRQ mcause");
  CHECK_EQ(tb.csrRead(kMepc), 0x80000054U, "pending-MDU IRQ mepc");
}

void testControlTransfersWaitForOlderMemory() {
  enum class Kind { Branch, Jal, Jalr };
  for (const Kind kind : {Kind::Branch, Kind::Jal, Kind::Jalr}) {
    const std::string label =
        kind == Kind::Branch ? "branch" : (kind == Kind::Jal ? "JAL" : "JALR");
    ExuTestbench tb;
    constexpr std::uint32_t pc = 0x80000100U;
    constexpr std::uint32_t target = 0x80000120U;
    tb.beginInstruction(pc);
    tb.dut().io_in_bits_pred_taken = 1;
    tb.dut().io_in_bits_pred_target = target;

    if (kind == Kind::Branch) {
      tb.dut().io_in_bits_IsBranch = 1;
      tb.dut().io_in_bits_BranchFunct3 = 0; // BEQ
      tb.dut().io_in_bits_BranchA = 7;
      tb.dut().io_in_bits_BranchB = 7;
      tb.dut().io_in_bits_Immediate = target - pc;
    } else if (kind == Kind::Jal) {
      tb.dut().io_in_bits_IsJal = 1;
      tb.dut().io_in_bits_ALUCtrl = 0; // ADD
      tb.dut().io_in_bits_ALU_A = pc;
      tb.dut().io_in_bits_ALU_B = target - pc;
      tb.dut().io_in_bits_Rd = 1;
      tb.dut().io_in_bits_RegisterWrite = 1;
    } else {
      tb.dut().io_in_bits_IsJalr = 1;
      tb.dut().io_in_bits_ALUCtrl = 0; // ADD
      tb.dut().io_in_bits_ALU_A = target;
      tb.dut().io_in_bits_ALU_B = 0;
      tb.dut().io_in_bits_Rd = 0;
      tb.dut().io_in_bits_Rs1 = 1;
    }

    tb.dut().io_MEMBusy = 1;
    tb.eval();
    CHECK(!tb.dut().io_in_ready && !tb.dut().io_out_valid,
          label + " did not wait for the older MEM operation");
    CHECK(!tb.dut().io_BTBUpdateValid,
          label + " updated the branch BTB before older MEM completed");
    CHECK(!tb.dut().io_JalBTBUpdateValid,
          label + " updated the JAL BTB before older MEM completed");
    CHECK(!tb.dut().io_RASPushValid && !tb.dut().io_RASPopValid,
          label + " updated the RAS before older MEM completed");

    tb.tick();
    tb.dut().io_MEMBusy = 0;
    tb.eval();
    CHECK(tb.dut().io_in_ready && tb.dut().io_out_valid,
          label + " did not resume after older MEM completed");
    if (kind == Kind::Branch) {
      CHECK(tb.dut().io_BTBUpdateValid,
            "branch did not train the BTB after MEM drained");
    } else {
      CHECK(tb.dut().io_JalBTBUpdateValid,
            label + " did not train the JAL BTB after MEM drained");
    }
    tb.tick();
  }
}

void testControlTransfersDoNotWaitForOlderNonMemory() {
  enum class Kind { Branch, Jal, Jalr };
  for (const Kind kind : {Kind::Branch, Kind::Jal, Kind::Jalr}) {
    const std::string label =
        kind == Kind::Branch ? "branch" : (kind == Kind::Jal ? "JAL" : "JALR");
    ExuTestbench tb;
    constexpr std::uint32_t pc = 0x80000180U;
    constexpr std::uint32_t target = 0x800001a0U;
    tb.beginInstruction(pc);
    tb.dut().io_PipelineBusy = 1;
    tb.dut().io_MEMBusy = 0;
    tb.dut().io_in_bits_pred_taken = 1;
    tb.dut().io_in_bits_pred_target = target;

    if (kind == Kind::Branch) {
      tb.dut().io_in_bits_IsBranch = 1;
      tb.dut().io_in_bits_BranchFunct3 = 0; // BEQ
      tb.dut().io_in_bits_BranchA = 9;
      tb.dut().io_in_bits_BranchB = 9;
      tb.dut().io_in_bits_Immediate = target - pc;
    } else if (kind == Kind::Jal) {
      tb.dut().io_in_bits_IsJal = 1;
      tb.dut().io_in_bits_ALUCtrl = 0;
      tb.dut().io_in_bits_ALU_A = pc;
      tb.dut().io_in_bits_ALU_B = target - pc;
      tb.dut().io_in_bits_Rd = 1;
      tb.dut().io_in_bits_RegisterWrite = 1;
    } else {
      tb.dut().io_in_bits_IsJalr = 1;
      tb.dut().io_in_bits_ALUCtrl = 0;
      tb.dut().io_in_bits_ALU_A = target;
      tb.dut().io_in_bits_ALU_B = 0;
      tb.dut().io_in_bits_Rs1 = 1;
    }

    tb.eval();
    CHECK(tb.dut().io_in_ready && tb.dut().io_out_valid,
          label + " stalled behind an older non-memory instruction");
    if (kind == Kind::Branch) {
      CHECK(tb.dut().io_BTBUpdateValid,
            "branch did not train while only PipelineBusy was asserted");
    } else {
      CHECK(tb.dut().io_JalBTBUpdateValid,
            label + " did not train while only PipelineBusy was asserted");
    }
    tb.tick();
  }
}

void testPreciseEffectsWaitForOlderPipelineInstruction() {
  {
    ExuTestbench tb;
    tb.csrWrite(kMtvec, 0x00000100U);
    tb.csrWrite(kMstatus, 1U << 3);
    tb.beginInstruction(0x800001c0U);
    tb.dut().io_Interrupt = 1;
    tb.dut().io_PipelineBusy = 1;
    tb.eval();
    CHECK(!tb.dut().io_in_ready && !tb.dut().io_ExceptionTaken,
          "IRQ bypassed an older non-memory pipeline instruction");
    tb.tick();
    tb.dut().io_PipelineBusy = 0;
    tb.eval();
    CHECK(tb.dut().io_in_ready && tb.dut().io_ExceptionTaken,
          "IRQ did not resume after the older pipeline instruction drained");
  }

  {
    ExuTestbench tb;
    tb.beginInstruction(0x800001d0U);
    tb.dut().io_in_bits_IsCsrrw = 1;
    tb.dut().io_in_bits_CSRAddress = kMstatus;
    tb.dut().io_in_bits_Rs1 = 1;
    tb.dut().io_in_bits_Rs1Data = 1U << 3;
    tb.dut().io_in_bits_WBSelect = 3;
    tb.dut().io_PipelineBusy = 1;
    tb.eval();
    CHECK(!tb.dut().io_in_ready && !tb.dut().io_out_valid,
          "CSR write bypassed an older non-memory pipeline instruction");
    tb.tick();
    tb.dut().io_PipelineBusy = 0;
    tb.eval();
    CHECK(tb.dut().io_in_ready && tb.dut().io_out_valid,
          "CSR write did not resume after the pipeline drained");
    tb.tick();
    tb.driveIdle();
    tb.eval();
    CHECK_EQ(tb.csrRead(kMstatus), 1U << 3,
             "delayed CSR write committed the wrong value");
  }

  {
    ExuTestbench tb;
    tb.csrWrite(kMtvec, 0x00000100U);
    tb.beginInstruction(0x800001e0U);
    tb.dut().io_in_bits_ExceptionValid = 1;
    tb.dut().io_in_bits_ExceptionCause = 2;
    tb.dut().io_PipelineBusy = 1;
    tb.eval();
    CHECK(!tb.dut().io_in_ready && !tb.dut().io_ExceptionTaken,
          "synchronous exception bypassed an older pipeline instruction");
    tb.tick();
    tb.dut().io_PipelineBusy = 0;
    tb.eval();
    CHECK(tb.dut().io_in_ready && tb.dut().io_ExceptionTaken,
          "synchronous exception did not resume after the pipeline drained");
    CHECK_EQ(tb.dut().io_TrapCause, 2U, "delayed exception cause");
  }
}

void testRasHintsForX1AndX5() {
  constexpr std::uint32_t pc = 0x80000200U;
  constexpr std::uint32_t target = 0x80000300U;

  const auto check_jal = [&](std::uint32_t rd, bool push,
                             std::uint32_t expected_kind,
                             const std::string &label) {
    ExuTestbench tb;
    tb.beginInstruction(pc);
    tb.dut().io_in_bits_IsJal = 1;
    tb.dut().io_in_bits_ALUCtrl = 0;
    tb.dut().io_in_bits_ALU_A = pc;
    tb.dut().io_in_bits_ALU_B = target - pc;
    tb.dut().io_in_bits_Rd = rd;
    tb.dut().io_in_bits_pred_taken = 1;
    tb.dut().io_in_bits_pred_target = target;
    tb.eval();
    CHECK(tb.dut().io_in_ready && tb.dut().io_out_valid,
          label + " did not fire");
    CHECK(static_cast<bool>(tb.dut().io_RASPushValid) == push,
          label + " produced the wrong RAS push hint");
    CHECK(!tb.dut().io_RASPopValid,
          label + " unexpectedly produced a RAS pop hint");
    CHECK(tb.dut().io_JalBTBUpdateValid,
          label + " did not train the static JAL BTB");
    CHECK_EQ(tb.dut().io_JalBTBUpdateKind, expected_kind,
             label + " JAL BTB kind");
  };

  const auto check_jalr = [&](std::uint32_t rd, std::uint32_t rs1, bool push,
                              bool pop, bool ret_btb,
                              const std::string &label) {
    ExuTestbench tb;
    tb.beginInstruction(pc);
    tb.dut().io_in_bits_IsJalr = 1;
    tb.dut().io_in_bits_ALUCtrl = 0;
    tb.dut().io_in_bits_ALU_A = target;
    tb.dut().io_in_bits_ALU_B = 0;
    tb.dut().io_in_bits_Rd = rd;
    tb.dut().io_in_bits_Rs1 = rs1;
    tb.dut().io_in_bits_pred_taken = 1;
    tb.dut().io_in_bits_pred_target = target;
    tb.eval();
    CHECK(tb.dut().io_in_ready && tb.dut().io_out_valid,
          label + " did not fire");
    CHECK(static_cast<bool>(tb.dut().io_RASPushValid) == push,
          label + " produced the wrong RAS push hint");
    CHECK(static_cast<bool>(tb.dut().io_RASPopValid) == pop,
          label + " produced the wrong RAS pop hint");
    CHECK(static_cast<bool>(tb.dut().io_JalBTBUpdateValid) == ret_btb,
          label + " produced the wrong return-BTB update");
    if (ret_btb) {
      CHECK_EQ(tb.dut().io_JalBTBUpdateKind, 2U, label + " return-BTB kind");
    }
  };

  check_jal(1, true, 1, "JAL x1");
  check_jal(5, true, 1, "JAL x5");
  check_jal(2, false, 0, "JAL non-link");
  check_jalr(0, 1, false, true, true, "JALR pop x1");
  check_jalr(0, 5, false, true, true, "JALR pop x5");
  check_jalr(1, 2, true, false, false, "JALR push x1");
  check_jalr(5, 5, true, false, false, "JALR same-link x5");
  check_jalr(1, 5, true, true, true, "JALR coroutine x5-to-x1");
  check_jalr(5, 1, true, true, true, "JALR coroutine x1-to-x5");
  check_jalr(2, 3, false, false, false, "JALR no hint");
}

void testPerfTrapExcludesMret() {
  ExuTestbench tb;
  tb.csrWrite(kMtvec, 0x00000100U);

  tb.beginInstruction(0x80000400U);
  tb.dut().io_in_bits_IsEcall = 1;
  tb.eval();
  CHECK(tb.dut().io_ExceptionTaken, "ECALL did not enter the trap handler");
  CHECK(tb.dut().io_PerfTrap, "ECALL trap entry was not counted");
  tb.tick();

  tb.driveIdle();
  tb.beginInstruction(0x00000100U);
  tb.dut().io_in_bits_IsMret = 1;
  tb.eval();
  CHECK(tb.dut().io_ExceptionTaken, "MRET did not redirect to mepc");
  CHECK(!tb.dut().io_PerfTrap, "MRET was incorrectly counted as a new trap");
  tb.tick();
}

void testBreakpointAndSimulationHaltAreDistinct() {
  ExuTestbench tb;
  tb.csrWrite(kMtvec, 0x00000100U);

  constexpr std::uint32_t breakpoint_pc = 0x80000300U;
  tb.beginInstruction(breakpoint_pc);
  tb.dut().io_in_bits_IsEbreak = 1;
  tb.eval();
  CHECK(tb.dut().io_in_ready && tb.dut().io_out_valid,
        "EBREAK did not reach the EXU commit point");
  CHECK(tb.dut().io_ExceptionTaken, "architectural EBREAK did not enter mtvec");
  CHECK_EQ(tb.dut().io_ExceptionTarget, 0x00000100U, "EBREAK trap target");
  CHECK(!tb.dut().io_SimHaltValid,
        "architectural EBREAK leaked into the simulator halt channel");
  CHECK(!tb.dut().io_out_bits_Retire,
        "architectural EBREAK was incorrectly marked retired");
  tb.tick();
  tb.driveIdle();
  tb.eval();
  CHECK_EQ(tb.csrRead(kMcause), 3U, "EBREAK mcause");
  CHECK_EQ(tb.csrRead(kMepc), breakpoint_pc, "EBREAK mepc");

  constexpr std::uint32_t halt_pc = 0x80000340U;
  tb.beginInstruction(halt_pc);
  tb.dut().io_in_bits_IsSimHalt = 1;
  tb.eval();
  CHECK(tb.dut().io_in_ready && tb.dut().io_out_valid,
        "custom halt did not reach the EXU commit point");
  CHECK(tb.dut().io_SimHaltValid,
        "custom halt did not produce a simulator halt commit");
  CHECK_EQ(tb.dut().io_SimHaltPC, halt_pc, "custom halt PC");
  CHECK(!tb.dut().io_ExceptionTaken,
        "custom halt incorrectly entered the architectural trap handler");
  CHECK(!tb.dut().io_out_bits_Retire,
        "custom halt was incorrectly marked as an ISA retirement");
  tb.tick();
}

void testTakenBranchMisalignment() {
  ExuTestbench tb;
  tb.csrWrite(kMtvec, 0x00000100U);
  tb.beginInstruction(0x80000000U);
  tb.dut().io_in_bits_IsBranch = 1;
  tb.dut().io_in_bits_BranchFunct3 = 0; // BEQ
  tb.dut().io_in_bits_BranchA = 0x55;
  tb.dut().io_in_bits_BranchB = 0x55;
  tb.dut().io_in_bits_Immediate = 2;
  tb.eval();
  CHECK(tb.dut().io_in_ready && tb.dut().io_out_valid,
        "taken branch did not reach the EXU commit point");
  checkCauseZeroAfterTrap(tb, "misaligned taken branch", 0x80000000U);
}

void testNotTakenBranchDoesNotTrap() {
  ExuTestbench tb;
  tb.csrWrite(kMtvec, 0x00000100U);
  tb.beginInstruction(0x80000000U);
  tb.dut().io_in_bits_IsBranch = 1;
  tb.dut().io_in_bits_BranchFunct3 = 0; // BEQ
  tb.dut().io_in_bits_BranchA = 1;
  tb.dut().io_in_bits_BranchB = 2;
  tb.dut().io_in_bits_Immediate = 2; // Untaken target may be misaligned.
  tb.dut().io_in_bits_pred_taken = 0;
  tb.dut().io_in_bits_pred_target = 0x80000004U;
  tb.eval();

  CHECK(tb.dut().io_in_ready && tb.dut().io_out_valid,
        "not-taken branch did not fire");
  CHECK(!tb.dut().io_ExceptionTaken,
        "not-taken branch incorrectly raised address-misaligned");
  CHECK(!tb.dut().io_Redirect,
        "correctly predicted not-taken branch redirected");
  CHECK(tb.dut().io_BTBUpdateValid,
        "committed not-taken branch did not train the BTB");
  CHECK(tb.dut().io_out_bits_Retire,
        "successful not-taken branch was not marked retired");
  tb.tick();
}

void testJalMisalignment() {
  ExuTestbench tb;
  tb.csrWrite(kMtvec, 0x00000100U);
  tb.beginInstruction(0x80000020U);
  tb.dut().io_in_bits_IsJal = 1;
  tb.dut().io_in_bits_ALUCtrl = 0; // ADD
  tb.dut().io_in_bits_ALU_A = 0x80000020U;
  tb.dut().io_in_bits_ALU_B = 2;
  tb.dut().io_in_bits_RegisterWrite = 1;
  tb.dut().io_in_bits_Rd = 1;
  tb.eval();
  CHECK(tb.dut().io_in_ready && tb.dut().io_out_valid,
        "JAL did not reach the EXU commit point");
  checkCauseZeroAfterTrap(tb, "misaligned JAL", 0x80000020U);
}

void testJalrMisalignment() {
  ExuTestbench tb;
  tb.csrWrite(kMtvec, 0x00000100U);
  tb.beginInstruction(0x80000030U);
  tb.dut().io_in_bits_IsJalr = 1;
  tb.dut().io_in_bits_ALUCtrl = 0; // ADD
  tb.dut().io_in_bits_ALU_A = 0x80001002U;
  tb.dut().io_in_bits_ALU_B = 0;
  tb.dut().io_in_bits_RegisterWrite = 1;
  tb.dut().io_in_bits_Rd = 1;
  tb.eval();
  CHECK(tb.dut().io_in_ready && tb.dut().io_out_valid,
        "JALR did not reach the EXU commit point");
  checkCauseZeroAfterTrap(tb, "misaligned JALR", 0x80000030U);
}

void testMepcAlignment() {
  ExuTestbench tb;
  tb.csrWrite(kMepc, 0x1234567BU);
  CHECK_EQ(tb.csrRead(kMepc), 0x12345678U, "mepc WARL alignment");

  tb.beginInstruction();
  tb.dut().io_in_bits_IsMret = 1;
  tb.eval();
  CHECK(tb.dut().io_ExceptionTaken, "MRET did not redirect");
  CHECK_EQ(tb.dut().io_ExceptionTarget, 0x12345678U, "MRET aligned target");
  tb.tick();
}

void testMtvecWarlAndDirectMode() {
  ExuTestbench tb;

  tb.csrWrite(kMtvec, 0x00000203U);
  CHECK_EQ(tb.csrRead(kMtvec), 0x00000200U,
           "mtvec reserved MODE must normalize to Direct");

  tb.csrWrite(kMstatus, 1U << 3);
  tb.beginInstruction(0x80000080U);
  tb.dut().io_Interrupt = 1;
  tb.eval();
  CHECK(tb.dut().io_ExceptionTaken, "direct-mode IRQ was not taken");
  CHECK_EQ(tb.dut().io_ExceptionTarget, 0x00000200U, "mtvec Direct IRQ target");
  tb.tick();
}

void testMtvecVectoredMode() {
  ExuTestbench tb;
  tb.csrWrite(kMtvec, 0x00000201U);
  CHECK_EQ(tb.csrRead(kMtvec), 0x00000201U, "mtvec Vectored readback");

  tb.csrWrite(kMstatus, 1U << 3);
  tb.beginInstruction(0x80000090U);
  tb.dut().io_Interrupt = 1;
  tb.eval();
  CHECK(tb.dut().io_ExceptionTaken, "vectored IRQ was not taken");
  CHECK_EQ(tb.dut().io_ExceptionTarget, 0x0000021CU,
           "mtvec Vectored machine-timer target");
  tb.tick();

  // Reset only the architectural state needed for a synchronous exception.
  // Vectored mode must not add a cause offset for exceptions.
  tb.driveIdle();
  tb.csrWrite(kMtvec, 0x00000201U);
  tb.beginInstruction(0x800000A0U);
  tb.dut().io_in_bits_IsEcall = 1;
  tb.eval();
  CHECK(tb.dut().io_ExceptionTaken, "ECALL was not taken");
  CHECK_EQ(tb.dut().io_ExceptionTarget, 0x00000200U,
           "synchronous trap target in mtvec Vectored mode");
  tb.tick();
}

} // namespace

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);

  const std::vector<std::pair<std::string, std::function<void()>>> tests = {
      {"IRQ waits for older MEM", testIrqWaitsForOlderMemory},
      {"IRQ preempts current memory", testIrqPreemptsCurrentMemoryAfterDrain},
      {"pending MDU waits for older MEM", testPendingMduWaitsForOlderMemory},
      {"control transfers wait for older MEM",
       testControlTransfersWaitForOlderMemory},
      {"control transfers bypass older non-memory",
       testControlTransfersDoNotWaitForOlderNonMemory},
      {"precise effects wait for older pipeline instruction",
       testPreciseEffectsWaitForOlderPipelineInstruction},
      {"RAS hints for x1/x5", testRasHintsForX1AndX5},
      {"PerfTrap excludes MRET", testPerfTrapExcludesMret},
      {"EBREAK and simulation halt are distinct",
       testBreakpointAndSimulationHaltAreDistinct},
      {"taken branch misalignment", testTakenBranchMisalignment},
      {"not-taken branch does not trap", testNotTakenBranchDoesNotTrap},
      {"JAL misalignment", testJalMisalignment},
      {"JALR misalignment", testJalrMisalignment},
      {"mepc alignment", testMepcAlignment},
      {"mtvec WARL/direct", testMtvecWarlAndDirectMode},
      {"mtvec vectored", testMtvecVectoredMode},
  };

  std::size_t passed = 0;
  for (const auto &[name, test] : tests) {
    try {
      test();
      ++passed;
      std::cout << "[PASS] " << name << '\n';
    } catch (const std::exception &error) {
      std::cerr << "[FAIL] " << name << ": " << error.what() << '\n';
      return 1;
    }
  }

  std::cout << "All " << passed << " EXU trap/CSR tests passed.\n";
  return 0;
}
