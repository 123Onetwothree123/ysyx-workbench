#include "VPreciseTrapFlushHarness.h"
#include "test_common.hpp"

#include <cstdint>
#include <verilated.h>

using DUT = VPreciseTrapFlushHarness;

namespace {

void defaults(DUT &dut) {
  dut.io_in_valid = 0;
  dut.io_in_bits_Instruction = 0x00000013U;
  dut.io_in_bits_Retire = 1;
  dut.io_in_bits_Rd = 0;
  dut.io_in_bits_RegisterWrite = 0;
  dut.io_in_bits_WBSelect = 0;
  dut.io_in_bits_ALUResult = 0;
  dut.io_in_bits_LoadData = 0;
  dut.io_in_bits_snpc = 0x80000004U;
  dut.io_in_bits_NextPC = 0x80000004U;
  dut.io_in_bits_CSRReadData = 0;
  dut.io_in_bits_CSRStateMstatus = 0;
  dut.io_in_bits_CSRStateMtvec = 0;
  dut.io_in_bits_CSRStateMepc = 0;
  dut.io_in_bits_CSRStateMcause = 0;
  dut.io_in_bits_MemoryValid = 0;
  dut.io_in_bits_MemoryWrite = 0;
  dut.io_in_bits_WidthSelect = 2;
  dut.io_in_bits_LoadSigned = 0;
  dut.io_in_bits_StoreData = 0;
  dut.io_in_bits_pc = 0x80000000U;
  dut.io_in_bits_ExceptionValid = 0;
  dut.io_in_bits_ExceptionCause = 0;

  dut.io_out_ready = 1;
  dut.io_arready = 0;
  dut.io_rvalid = 0;
  dut.io_rdata = 0;
  dut.io_rresp = 0;
  dut.io_rlast = 0;
}

void drive_load(DUT &dut, std::uint32_t pc, std::uint32_t address) {
  dut.io_in_bits_Instruction = 0x00002003U;
  dut.io_in_bits_Retire = 1;
  dut.io_in_bits_Rd = 1;
  dut.io_in_bits_RegisterWrite = 1;
  dut.io_in_bits_WBSelect = 1;
  dut.io_in_bits_ALUResult = address;
  dut.io_in_bits_snpc = pc + 4U;
  dut.io_in_bits_NextPC = pc + 4U;
  dut.io_in_bits_MemoryValid = 1;
  dut.io_in_bits_MemoryWrite = 0;
  dut.io_in_bits_WidthSelect = 2;
  dut.io_in_bits_pc = pc;
  dut.io_in_valid = 1;
}

void drive_alu(DUT &dut, std::uint32_t pc) {
  dut.io_in_bits_Instruction = 0x00100093U;
  dut.io_in_bits_Retire = 1;
  dut.io_in_bits_Rd = 2;
  dut.io_in_bits_RegisterWrite = 1;
  dut.io_in_bits_WBSelect = 0;
  dut.io_in_bits_ALUResult = 1;
  dut.io_in_bits_snpc = pc + 4U;
  dut.io_in_bits_NextPC = pc + 4U;
  dut.io_in_bits_MemoryValid = 0;
  dut.io_in_bits_MemoryWrite = 0;
  dut.io_in_bits_pc = pc;
  dut.io_in_valid = 1;
}

void younger_instruction_must_be_killed_by_lsu_trap() {
  DUT dut;
  defaults(dut);
  reset(dut);

  constexpr std::uint32_t fault_pc = 0x80000100U;
  constexpr std::uint32_t younger_pc = 0x80000104U;

  // First place the older load in the StageConnect buffer.
  drive_load(dut, fault_pc, 0x02000000U);
  dut.eval();
  check(dut.io_in_ready, "empty EXU-to-LSU boundary did not accept load");
  tick(dut);

  // On the load's LSU acceptance cycle, queue the younger ALU instruction in
  // the same production boundary.  It is now older than the eventual trap
  // signal but younger than the faulting LSU operation.
  drive_alu(dut, younger_pc);
  dut.eval();
  check(dut.io_in_ready, "boundary did not accept the younger ALU instruction");
  tick(dut);
  dut.io_in_valid = 0;

  for (int cycle = 0; cycle < 20 && !dut.io_arvalid; ++cycle) {
    dut.eval();
    tick(dut);
  }
  dut.eval();
  check(dut.io_arvalid && dut.io_araddr == 0x02000000U,
        "faulting load did not issue the expected AXI read");
  dut.io_arready = 1;
  tick(dut);
  dut.io_arready = 0;

  for (int cycle = 0; cycle < 20 && !dut.io_rready; ++cycle) {
    dut.eval();
    tick(dut);
  }
  dut.eval();
  check(dut.io_rready, "LSU never became ready for AXI R response");
  dut.io_rvalid = 1;
  dut.io_rresp = 2; // SLVERR
  dut.io_rlast = 1;
  tick(dut);
  dut.io_rvalid = 0;
  dut.io_rlast = 0;

  dut.eval();
  check(dut.io_mem_trap_commit && dut.io_flush_exmem,
        "LSU did not assert the precise trap/EXMEM flush");
  check(dut.io_out_valid && dut.io_out_bits_pc == fault_pc &&
            !dut.io_out_bits_Retire,
        "faulting load did not reach the trap boundary");

  // Commit the trap.  No instruction younger than the fault may appear on the
  // architectural output after this edge.
  tick(dut);
  dut.eval();
  check(!(dut.io_out_valid && dut.io_out_bits_pc == younger_pc &&
          dut.io_out_bits_Retire),
        "younger ALU instruction escaped after the older load trap");
}

} // namespace

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  return run_test("LSU trap kills younger EXU-to-LSU item",
                  younger_instruction_must_be_killed_by_lsu_trap);
}
