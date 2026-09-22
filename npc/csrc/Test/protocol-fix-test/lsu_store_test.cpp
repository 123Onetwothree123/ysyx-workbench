#include "Vysyx_26030103_LSU.h"
#include "test_common.hpp"
#include <cstdint>
#include <verilated.h>

using DUT = Vysyx_26030103_LSU;

static void defaults(DUT &dut) {
  dut.io_in_valid = 0;
  dut.io_in_bits_Instruction = 0x00000023U;
  dut.io_in_bits_Retire = 1;
  dut.io_in_bits_Rd = 0;
  dut.io_in_bits_RegisterWrite = 0;
  dut.io_in_bits_WBSelect = 0;
  dut.io_in_bits_ALUResult = 0;
  dut.io_in_bits_snpc = 0;
  dut.io_in_bits_CSRReadData = 0;
  dut.io_in_bits_MemoryValid = 0;
  dut.io_in_bits_MemoryWrite = 0;
  dut.io_in_bits_WidthSelect = 2;
  dut.io_in_bits_LoadSigned = 0;
  dut.io_in_bits_StoreData = 0;
  dut.io_in_bits_pc = 0;

  dut.io_DataBus_AW_AWREADY = 0;
  dut.io_DataBus_W_WREADY = 0;
  dut.io_DataBus_B_BRESP = 0;
  dut.io_DataBus_B_BVALID = 0;
  dut.io_DataBus_AR_ARREADY = 0;
  dut.io_DataBus_R_RDATA = 0;
  dut.io_DataBus_R_RRESP = 0;
  dut.io_DataBus_R_RVALID = 0;
  dut.io_DCacheFlush = 0;
}

static void begin_memory_op(DUT &dut, uint32_t address, bool write,
                            uint32_t data = 0, uint32_t pc = 0x80000100U) {
  dut.io_in_bits_ALUResult = address;
  dut.io_in_bits_MemoryValid = 1;
  dut.io_in_bits_MemoryWrite = write;
  dut.io_in_bits_WidthSelect = 2;
  dut.io_in_bits_LoadSigned = 0;
  dut.io_in_bits_StoreData = data;
  dut.io_in_bits_pc = pc;
  dut.io_in_valid = 1;
  wait_until(dut, [&] { return dut.io_in_ready; },
             "LSU never accepted memory operation");
  tick(dut);
  dut.io_in_valid = 0;
}

static uint32_t fill_and_load(DUT &dut, uint32_t address,
                              uint32_t requested_word) {
  begin_memory_op(dut, address, false);
  const uint32_t line_base = address & ~0xfU;
  for (uint32_t beat = 0; beat < 4; ++beat) {
    wait_until(dut, [&] { return dut.io_DataBus_AR_ARVALID; },
               "DCache refill never issued AR");
    check(dut.io_DataBus_AR_ARADDR == line_base + beat * 4,
          "DCache refill issued the wrong word address");
    dut.io_DataBus_AR_ARREADY = 1;
    tick(dut);
    dut.io_DataBus_AR_ARREADY = 0;

    wait_until(dut, [&] { return dut.io_DataBus_R_RREADY; },
               "DCache refill never became ready for R");
    dut.io_DataBus_R_RDATA = beat == ((address >> 2) & 3U)
                                 ? requested_word
                                 : 0x10000000U + beat;
    dut.io_DataBus_R_RRESP = 0;
    dut.io_DataBus_R_RVALID = 1;
    tick(dut);
    dut.io_DataBus_R_RVALID = 0;
  }

  wait_until(dut, [&] { return dut.io_out_valid; },
             "refilled load never retired");
  const uint32_t result = dut.io_out_bits_LoadData;
  tick(dut);
  return result;
}

static uint32_t load_hit(DUT &dut, uint32_t address) {
  begin_memory_op(dut, address, false);
  for (int cycle = 0; cycle < 20; ++cycle) {
    dut.eval();
    check(!dut.io_DataBus_AR_ARVALID,
          "expected DCache hit unexpectedly accessed AXI");
    if (dut.io_out_valid) {
      const uint32_t result = dut.io_out_bits_LoadData;
      tick(dut);
      return result;
    }
    tick(dut);
  }
  throw std::runtime_error("DCache hit load never retired");
}

static void store_with_response(DUT &dut, uint32_t address, uint32_t data,
                                uint8_t response, bool expect_fault,
                                uint32_t pc) {
  begin_memory_op(dut, address, true, data, pc);
  dut.io_DataBus_AW_AWREADY = 1;
  dut.io_DataBus_W_WREADY = 1;
  wait_until(dut,
             [&] {
               return dut.io_DataBus_AW_AWVALID &&
                      dut.io_DataBus_W_WVALID;
             },
             "store never issued AW/W");
  check(!dut.io_Complete && !dut.io_out_valid,
        "store retired before its write request completed");
  tick(dut);
  dut.io_DataBus_AW_AWREADY = 0;
  dut.io_DataBus_W_WREADY = 0;

  wait_until(dut, [&] { return dut.io_DataBus_B_BREADY; },
             "store never waited for B");
  for (int cycle = 0; cycle < 3; ++cycle) {
    check(!dut.io_Complete && !dut.io_out_valid,
          "store retired while BVALID was low");
    tick(dut);
  }

  dut.io_DataBus_B_BRESP = response;
  dut.io_DataBus_B_BVALID = 1;
  tick(dut);
  dut.io_DataBus_B_BVALID = 0;

  wait_until(dut, [&] { return dut.io_out_valid; },
             "store never reached retirement after B");
  check(static_cast<bool>(dut.io_MemTrapCommit) == expect_fault,
        "store fault commit did not match BRESP");
  check(static_cast<bool>(dut.io_out_bits_Retire) == !expect_fault,
        "store retirement marker did not match BRESP");
  if (expect_fault) {
    check(dut.io_MemTrapCause == 7, "failed store did not report cause 7");
    check(dut.io_MemTrapPC == pc, "failed store reported the wrong PC");
    check(dut.io_AccessFaultResp == response,
          "failed store lost its BRESP code");
  }
  tick(dut);
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  return run_test("LSU precise store BRESP and DCache commit", [] {
    DUT dut;
    defaults(dut);
    reset(dut);

    constexpr uint32_t address = 0x80000000U;
    constexpr uint32_t old_word = 0x11223344U;
    check(fill_and_load(dut, address, old_word) == old_word,
          "initial DCache refill returned the wrong word");

    store_with_response(dut, address, 0xdeadbeefU, 2, true, 0x80000120U);
    check(load_hit(dut, address) == old_word,
          "failed store modified the DCache mirror");

    store_with_response(dut, address, 0xaabbccddU, 0, false, 0x80000124U);
    check(load_hit(dut, address) == 0xaabbccddU,
          "successful store did not update the DCache mirror");
    dut.final();
  });
}
