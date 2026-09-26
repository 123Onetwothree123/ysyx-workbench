#include "Vysyx_26030103_EXU.h"
#include "test_common.hpp"

#include <array>
#include <cstdint>
#include <string_view>
#include <verilated.h>

using DUT = Vysyx_26030103_EXU;

namespace {

constexpr std::uint16_t kMstatus = 0x300;

void defaults(DUT &dut) {
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
  dut.io_in_bits_AccessFaultResp = 0;
  dut.io_in_bits_pred_taken = 0;
  dut.io_in_bits_pred_target = 0x80000004U;
  dut.io_Interrupt = 0;
  dut.io_MEMBusy = 0;
  dut.io_PipelineBusy = 0;
  dut.io_MemTrapCommit = 0;
  dut.io_MemTrapCause = 0;
  dut.io_MemTrapPC = 0;
}

void begin_instruction(DUT &dut, std::uint32_t pc) {
  defaults(dut);
  dut.io_in_valid = 1;
  dut.io_in_bits_pc = pc;
  dut.io_in_bits_snpc = pc + 4U;
  dut.io_in_bits_pred_target = pc + 4U;
}

void csr_write(DUT &dut, std::uint16_t address, std::uint32_t value) {
  begin_instruction(dut, 0x80000000U);
  dut.io_in_bits_IsCsrrw = 1;
  dut.io_in_bits_CSRAddress = address;
  dut.io_in_bits_Rs1 = 1;
  dut.io_in_bits_Rs1Data = value;
  dut.io_in_bits_WBSelect = 3;
  dut.eval();
  check(dut.io_in_ready && dut.io_out_valid,
        "CSR setup instruction did not fire");
  tick(dut);
  defaults(dut);
}

void drive_mdu(DUT &dut, std::uint32_t pc, std::uint32_t lhs, std::uint32_t rhs,
               std::uint8_t rd, std::uint8_t operation) {
  begin_instruction(dut, pc);
  dut.io_in_bits_Instruction = 0x02000033U;
  dut.io_in_bits_IsMDU = 1;
  dut.io_in_bits_MDUOp = operation;
  dut.io_in_bits_ALU_A = lhs;
  dut.io_in_bits_ALU_B = rhs;
  dut.io_in_bits_Rd = rd;
  dut.io_in_bits_RegisterWrite = 1;
  dut.io_in_bits_WBSelect = 0;
}

void alu_irq_waits_for_older_memory_control() {
  DUT dut;
  defaults(dut);
  reset(dut);
  csr_write(dut, kMstatus, 1U << 3);

  begin_instruction(dut, 0x80000020U);
  dut.io_in_bits_ALUCtrl = 0;
  dut.io_in_bits_ALU_A = 1;
  dut.io_in_bits_ALU_B = 2;
  dut.io_in_bits_Rd = 5;
  dut.io_in_bits_RegisterWrite = 1;
  dut.io_MEMBusy = 1;
  dut.io_Interrupt = 1;
  dut.eval();
  check(!dut.io_in_ready && !dut.io_ExceptionTaken && !dut.io_TrapCommit,
        "normal ALU control did not wait for the older MEM instruction");

  dut.io_MEMBusy = 0;
  dut.eval();
  check(dut.io_in_ready && dut.io_ExceptionTaken && dut.io_TrapCommit,
        "qualified IRQ was not taken after the older MEM instruction drained");
  check(!dut.io_out_bits_RegisterWrite && !dut.io_out_bits_Retire,
        "IRQ-preempted ALU instruction leaked architectural side effects");
  dut.final();
}

void irq_must_wait_for_older_memory() {
  DUT dut;
  defaults(dut);
  reset(dut);
  csr_write(dut, kMstatus, 1U << 3); // MIE=1

  // Model an older instruction still resident in MEM, then present a younger
  // M instruction at EXU while a qualified level IRQ is pending.
  drive_mdu(dut, 0x80000040U, 7, 9, 6, 0);
  dut.io_MEMBusy = 1;
  dut.io_Interrupt = 1;
  dut.eval();

  check(!dut.io_ExceptionTaken,
        "IRQ committed while an older memory instruction was still busy");
  check(!dut.io_TrapCommit, "TrapCommit bypassed the older memory instruction");
  check(!dut.io_in_ready, "younger MDU input was consumed before the older "
                          "memory instruction drained");
  check(!dut.io_PerfMDUReq,
        "MDU request started even though the pending IRQ must wait for MEM");

  // The held input must make progress as soon as the older memory instruction
  // drains: accept the IRQ at this M-instruction boundary without launching the
  // multiply or leaking the interrupted instruction's side effects.
  dut.io_MEMBusy = 0;
  dut.eval();
  check(dut.io_in_ready && dut.io_ExceptionTaken && dut.io_TrapCommit,
        "held MDU instruction did not accept the IRQ after MEM drained");
  check(!dut.io_PerfMDUReq,
        "interrupted MDU instruction incorrectly launched after MEM drained");
  check(!dut.io_out_bits_RegisterWrite && !dut.io_out_bits_Retire,
        "IRQ-preempted MDU instruction leaked architectural side effects");
  dut.final();
}

void pipelined_mul_must_accept_back_to_back_requests() {
  DUT dut;
  defaults(dut);
  reset(dut);

  // This test project elaborates Wallace MUL with MUL_PIPELINE=2.  Its core has
  // an initiation interval of one, so the system-level MDU must preserve that
  // capability if the public pipeline option is to mean pipelined throughput.
  const auto issue = [&](std::uint32_t pc, std::uint32_t lhs, std::uint32_t rhs,
                         std::uint8_t rd, std::uint8_t operation) {
    drive_mdu(dut, pc, lhs, rhs, rd, operation);
    dut.eval();
    check(dut.io_in_ready && dut.io_PerfMDUReq,
          "MUL_PIPELINE=2 rejected a consecutive MUL request");
    tick(dut);
  };
  issue(0x80000100U, 3, 5, 10, 0);           // MUL   -> 15
  issue(0x80000104U, 0xfffffffeU, 3, 11, 1); // MULH  -> ffffffff
  issue(0x80000108U, 0xffffffffU, 2, 12, 3); // MULHU -> 1

  // Fill the three-slot MUL pipeline, then hold its first result to verify that
  // product and instruction metadata remain aligned under backpressure.
  dut.io_in_valid = 0;
  dut.io_out_ready = 0;
  wait_until(
      dut, [&] { return dut.io_out_valid; },
      "pipelined MUL never produced its first response", 16);
  check(!dut.io_PerfMDUWait,
        "ready MDU result under downstream backpressure was still counted as "
        "MDU computation wait");
  const std::uint32_t held_result = dut.io_out_bits_ALUResult;
  const std::uint32_t held_pc = dut.io_out_bits_pc;
  const std::uint8_t held_rd = dut.io_out_bits_Rd;
  for (int cycle = 0; cycle < 3; ++cycle) {
    dut.eval();
    check(dut.io_out_valid && dut.io_out_bits_ALUResult == held_result &&
              dut.io_out_bits_pc == held_pc && dut.io_out_bits_Rd == held_rd,
          "pipelined MUL response changed under backpressure");
    tick(dut);
  }

  struct Expected {
    std::uint32_t result;
    std::uint32_t pc;
    std::uint8_t rd;
  };
  constexpr std::array<Expected, 4> expected = {
      Expected{15U, 0x80000100U, 10},
      Expected{0xffffffffU, 0x80000104U, 11},
      Expected{1U, 0x80000108U, 12},
      Expected{81U, 0x8000010cU, 13},
  };

  // Consume the full FIFO head and accept a replacement request on the same
  // cycle.  This exercises head==tail overwrite ordering in both metadata
  // queues without inserting a throughput bubble.
  drive_mdu(dut, 0x8000010cU, 9, 9, 13, 0);
  dut.eval();
  check(dut.io_out_valid && dut.io_in_ready && dut.io_PerfMDUReq,
        "full MUL pipeline could not pop and push on the same cycle");

  std::array<Expected, 4> observed{};
  unsigned responses = 0;
  observed[responses++] = {
      static_cast<std::uint32_t>(dut.io_out_bits_ALUResult),
      static_cast<std::uint32_t>(dut.io_out_bits_pc),
      static_cast<std::uint8_t>(dut.io_out_bits_Rd),
  };
  tick(dut);
  defaults(dut);
  for (int cycle = 0; cycle < 20 && responses < observed.size(); ++cycle) {
    dut.eval();
    if (dut.io_out_valid) {
      check(dut.io_out_bits_Retire && dut.io_out_bits_RegisterWrite,
            "successful pipelined MUL lost retirement side effects");
      observed[responses++] = {
          static_cast<std::uint32_t>(dut.io_out_bits_ALUResult),
          static_cast<std::uint32_t>(dut.io_out_bits_pc),
          static_cast<std::uint8_t>(dut.io_out_bits_Rd),
      };
    }
    tick(dut);
  }
  check(responses == expected.size(),
        "pipelined MUL did not return every accepted request");
  for (std::size_t index = 0; index < expected.size(); ++index) {
    check(observed[index].result == expected[index].result &&
              observed[index].pc == expected[index].pc &&
              observed[index].rd == expected[index].rd,
          "pipelined MUL result/sign/high-half metadata was reordered");
  }
  dut.final();
}

void mixed_mdu_requests_preserve_program_order() {
  DUT dut;
  defaults(dut);
  reset(dut);

  const auto issue = [&](std::uint32_t pc, std::uint32_t lhs, std::uint32_t rhs,
                         std::uint8_t rd, std::uint8_t operation) {
    drive_mdu(dut, pc, lhs, rhs, rd, operation);
    dut.eval();
    check(dut.io_in_ready && dut.io_PerfMDUReq,
          "mixed MDU owner FIFO rejected an available execution unit");
    tick(dut);
  };
  issue(0x80000200U, 100, 5, 20, 5); // DIVU -> 20, deliberately slow
  issue(0x80000204U, 3, 7, 21, 0);   // MUL  -> 21
  issue(0x80000208U, 4, 8, 22, 0);   // MUL  -> 32
  issue(0x8000020cU, 5, 9, 23, 0);   // MUL  -> 45
  defaults(dut);

  constexpr std::array<std::uint32_t, 4> expected = {20, 21, 32, 45};
  std::array<std::uint32_t, 4> observed{};
  unsigned responses = 0;
  for (int cycle = 0; cycle < 64 && responses < observed.size(); ++cycle) {
    dut.eval();
    if (dut.io_out_valid) {
      observed[responses++] = dut.io_out_bits_ALUResult;
    }
    tick(dut);
  }
  check(responses == expected.size(),
        "mixed MDU stream did not return every response");
  check(observed == expected,
        "MUL result bypassed an older DIV or mixed responses were reordered");
  dut.final();
}

void hidden_mdu_write_mask_survives_duplicates_and_flushes() {
  DUT dut;
  defaults(dut);
  reset(dut);

  drive_mdu(dut, 0x80000300U, 2, 3, 5, 0);
  dut.eval();
  check(dut.io_in_ready, "first duplicate-rd MUL was not accepted");
  tick(dut);
  drive_mdu(dut, 0x80000304U, 4, 5, 5, 0);
  dut.eval();
  check(dut.io_in_ready, "second duplicate-rd MUL was not accepted");
  tick(dut);
  dut.io_in_valid = 0;
  dut.io_out_ready = 0;
  dut.eval();
  check((dut.io_HazardMDUHiddenWrites & (1U << 5)) != 0,
        "younger duplicate MDU destination was absent from hazard mask");
  wait_until(
      dut, [&] { return dut.io_out_valid; },
      "duplicate-rd MDU stream never produced its head", 16);
  check((dut.io_HazardMDUHiddenWrites & (1U << 5)) != 0,
        "ready queue head incorrectly hid the younger same-rd writer");

  dut.io_MemTrapCommit = 1;
  dut.io_MemTrapCause = 5;
  dut.io_MemTrapPC = 0x80000080U;
  dut.eval();
  check(!dut.io_out_valid,
        "MemTrap did not suppress an in-flight MDU response");
  tick(dut);
  defaults(dut);
  dut.eval();
  check(dut.io_HazardMDUHiddenWrites == 0,
        "MemTrap did not clear queued MDU hazard metadata");
  for (int cycle = 0; cycle < 8; ++cycle) {
    check(!dut.io_out_valid,
          "flushed MDU response leaked after the memory trap");
    tick(dut);
  }
  dut.final();
}

void pending_mdu_perf_uses_queue_head_instruction() {
  enum class YoungerKind { Alu, Memory, Csr, Branch };
  for (const auto kind : {YoungerKind::Alu, YoungerKind::Memory,
                          YoungerKind::Csr, YoungerKind::Branch}) {
    DUT dut;
    defaults(dut);
    reset(dut);

    // DIVU is deliberately long-lived, leaving a younger input visible while
    // the queue-head metadata remains the active EXU instruction.
    drive_mdu(dut, 0x80000400U, 100, 7, 9, 5);
    dut.eval();
    check(dut.io_in_ready && dut.io_PerfMDUReq,
          "long-latency MDU request was not accepted");
    tick(dut);

    begin_instruction(dut, 0x80000404U);
    dut.io_out_ready = 0;
    switch (kind) {
    case YoungerKind::Alu:
      dut.io_in_bits_ALUCtrl = 0;
      dut.io_in_bits_ALU_A = 1;
      dut.io_in_bits_ALU_B = 2;
      break;
    case YoungerKind::Memory:
      dut.io_in_bits_MemoryValid = 1;
      break;
    case YoungerKind::Csr:
      dut.io_in_bits_IsCsrrw = 1;
      dut.io_in_bits_CSRAddress = kMstatus;
      dut.io_in_bits_Rs1 = 1;
      break;
    case YoungerKind::Branch:
      dut.io_in_bits_IsBranch = 1;
      dut.io_in_bits_BranchFunct3 = 0;
      break;
    }
    dut.eval();

    check(dut.io_PerfExecutionActive && dut.io_PerfMDUActive,
          "pending MDU was not reported active");
    check(dut.io_PerfMDUWait,
          "long-latency pending MDU was not classified as an MDU wait");
    check(!dut.io_PerfALUOp && !dut.io_PerfMemOp && !dut.io_PerfCSROp &&
              !dut.io_PerfBranchOp && !dut.io_PerfJalOp &&
              !dut.io_PerfJalrOp,
          "younger blocked instruction polluted pending-MDU perf classes");
    check(dut.io_PerfMDUOp == 5,
          "pending-MDU perf opcode came from the younger input");
    check(dut.io_PerfEventKind == 0,
          "unretired pending MDU emitted a completion event");
    dut.final();
  }
}

} // namespace

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  const bool functional_only =
      argc == 2 && std::string_view(argv[1]) == "--functional-only";
  int failures = 0;
  failures += run_test("ALU IRQ/MEM ordering positive control",
                       alu_irq_waits_for_older_memory_control);
  failures +=
      run_test("MDU IRQ waits for older MEM", irq_must_wait_for_older_memory);
  if (!functional_only) {
    failures += run_test("MUL_PIPELINE accepts back-to-back requests",
                         pipelined_mul_must_accept_back_to_back_requests);
    failures += run_test("mixed MUL/DIV responses preserve request order",
                         mixed_mdu_requests_preserve_program_order);
    failures += run_test("MDU hidden-rd scoreboard and flush",
                         hidden_mdu_write_mask_survives_duplicates_and_flushes);
    failures += run_test("pending MDU perf uses queue-head instruction",
                         pending_mdu_perf_uses_queue_head_instruction);
  }
  return failures == 0 ? 0 : 1;
}
