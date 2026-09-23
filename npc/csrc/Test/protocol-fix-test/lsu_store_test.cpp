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
  dut.io_DataBus_R_RLAST = 0;
  dut.io_DataBus_R_RVALID = 0;
  dut.io_DCacheFlush = 0;
}

static void set_store(DUT &dut, uint32_t address, uint32_t data,
                      uint32_t pc) {
  dut.io_in_bits_Instruction = 0x00002023U;
  dut.io_in_bits_Retire = 1;
  dut.io_in_bits_Rd = 0;
  dut.io_in_bits_RegisterWrite = 0;
  dut.io_in_bits_WBSelect = 0;
  dut.io_in_bits_ALUResult = address;
  dut.io_in_bits_snpc = pc + 4;
  dut.io_in_bits_CSRReadData = 0;
  dut.io_in_bits_MemoryValid = 1;
  dut.io_in_bits_MemoryWrite = 1;
  dut.io_in_bits_WidthSelect = 2;
  dut.io_in_bits_LoadSigned = 0;
  dut.io_in_bits_StoreData = data;
  dut.io_in_bits_pc = pc;
}

static void enqueue_store(DUT &dut, uint32_t address, uint32_t data,
                          uint32_t pc) {
  set_store(dut, address, data, pc);
  dut.io_in_valid = 1;
  dut.eval();
  check(dut.io_in_ready,
        "configured write-buffer depth did not accept a consecutive store");
  tick(dut);
  dut.io_in_valid = 0;
}

static void complete_buffered_store(DUT &dut, uint32_t expected_address,
                                    uint32_t expected_data,
                                    uint32_t expected_pc, uint8_t bresp,
                                    uint32_t &random_state) {
  bool aw_seen = false;
  bool w_seen = false;
  bool aw_held = false;
  bool w_held = false;
  uint32_t held_awaddr = 0;
  uint32_t held_wdata = 0;

  for (int cycle = 0; cycle < 200 && !(aw_seen && w_seen); ++cycle) {
    random_state = random_state * 1664525U + 1013904223U;
    dut.io_DataBus_AW_AWREADY = (random_state >> 30) & 1U;
    dut.io_DataBus_W_WREADY = (random_state >> 29) & 1U;
    dut.eval();

    if (aw_held && !aw_seen) {
      check(dut.io_DataBus_AW_AWVALID,
            "AWVALID was withdrawn before AWREADY");
      check(dut.io_DataBus_AW_AWADDR == held_awaddr,
            "AWADDR changed while AWREADY was low");
    }
    if (dut.io_DataBus_AW_AWVALID) {
      check(dut.io_DataBus_AW_AWADDR == expected_address,
            "write buffer reordered or corrupted AWADDR");
      if (!aw_seen && !dut.io_DataBus_AW_AWREADY && !aw_held) {
        held_awaddr = dut.io_DataBus_AW_AWADDR;
        aw_held = true;
      }
    }
    if (w_held && !w_seen) {
      check(dut.io_DataBus_W_WVALID,
            "WVALID was withdrawn before WREADY");
      check(dut.io_DataBus_W_WDATA == held_wdata,
            "WDATA changed while WREADY was low");
    }
    if (dut.io_DataBus_W_WVALID) {
      check(dut.io_DataBus_W_WDATA == expected_data &&
                dut.io_DataBus_W_WSTRB == 0xf && dut.io_DataBus_W_WLAST,
            "write buffer reordered or corrupted W payload");
      if (!w_seen && !dut.io_DataBus_W_WREADY && !w_held) {
        held_wdata = dut.io_DataBus_W_WDATA;
        w_held = true;
      }
    }

    const bool aw_fire = dut.io_DataBus_AW_AWVALID &&
                         dut.io_DataBus_AW_AWREADY;
    const bool w_fire = dut.io_DataBus_W_WVALID &&
                        dut.io_DataBus_W_WREADY;
    tick(dut);
    aw_seen = aw_seen || aw_fire;
    w_seen = w_seen || w_fire;
  }
  check(aw_seen && w_seen,
        "write buffer did not finish AW/W under randomized backpressure");
  dut.io_DataBus_AW_AWREADY = 0;
  dut.io_DataBus_W_WREADY = 0;

  wait_until(dut, [&] { return dut.io_DataBus_B_BREADY; },
             "write buffer never became ready for BRESP");
  for (unsigned delay = 0; delay < (random_state & 3U); ++delay) {
    check(!dut.io_out_valid,
          "store retired before its own BRESP arrived");
    tick(dut);
  }
  dut.io_DataBus_B_BRESP = bresp;
  dut.io_DataBus_B_BVALID = 1;
  tick(dut);
  dut.io_DataBus_B_BVALID = 0;

  wait_until(dut, [&] { return dut.io_out_valid; },
             "store did not reach ordered retirement after BRESP");
  check(dut.io_out_bits_pc == expected_pc,
        "write-buffer retirement order did not match enqueue order");
  check(static_cast<bool>(dut.io_out_bits_Retire) == (bresp == 0),
        "store retirement marker did not match BRESP");
  check(static_cast<bool>(dut.io_MemTrapCommit) == (bresp != 0),
        "store fault was not committed at the failing queue head");
  if (bresp != 0) {
    check(dut.io_MemTrapCause == 7 && dut.io_MemTrapPC == expected_pc,
          "buffered failing store reported the wrong precise trap");
    check(dut.io_AccessFault && dut.io_AccessFaultResp == bresp,
          "buffered failing store lost its BRESP diagnostics");
  }
  tick(dut);
}

static void check_write_buffer_depth_and_precision() {
  DUT dut;
  defaults(dut);
  reset(dut);

  constexpr uint32_t base = 0x80001000U;
  constexpr uint32_t pc = 0x80000200U;
  for (uint32_t index = 0; index < 4; ++index) {
    enqueue_store(dut, base + index * 4U, 0xa5000000U + index,
                  pc + index * 4U);
  }

  // WBUF_DEPTH=4 counts the active queue head plus three pending stores.
  // A fifth store must be backpressured until one of those four retires.
  set_store(dut, base + 16U, 0xa5000004U, pc + 16U);
  dut.io_in_valid = 1;
  dut.eval();
  check(!dut.io_in_ready,
        "write buffer accepted more stores than its configured depth");
  dut.io_in_valid = 0;

  uint32_t random_state = 0x31415926U;
  complete_buffered_store(dut, base, 0xa5000000U, pc, 0, random_state);

  // As the next pending head enters the active slot, enqueue the formerly
  // blocked fifth store in the same cycle.  Queue count must remain stable and
  // neither item may be lost or reordered.
  enqueue_store(dut, base + 16U, 0xa5000004U, pc + 16U);
  for (uint32_t index = 1; index < 5; ++index) {
    complete_buffered_store(dut, base + index * 4U,
                            0xa5000000U + index, pc + index * 4U, 0,
                            random_state);
  }
  dut.final();

  DUT fault_dut;
  defaults(fault_dut);
  reset(fault_dut);
  for (uint32_t index = 0; index < 3; ++index) {
    enqueue_store(fault_dut, base + index * 4U, 0xb6000000U + index,
                  pc + index * 4U);
  }
  fault_dut.io_in_bits_MemoryValid = 0;
  fault_dut.io_in_bits_MemoryWrite = 0;
  fault_dut.io_in_bits_ALUResult = 0x12345678U;
  fault_dut.io_in_bits_pc = pc + 12U;
  fault_dut.io_in_valid = 1;
  fault_dut.eval();
  check(!fault_dut.io_in_ready && !fault_dut.io_out_valid,
        "younger non-store was allowed to pass unresolved stores");
  fault_dut.io_in_valid = 0;

  random_state = 0x27182818U;
  complete_buffered_store(fault_dut, base, 0xb6000000U, pc, 0,
                          random_state);
  complete_buffered_store(fault_dut, base + 4U, 0xb6000001U, pc + 4U, 2,
                          random_state);

  // The third store was younger than the failing second store and had never
  // reached AXI.  It must be discarded, not issued after the trap.
  for (int cycle = 0; cycle < 12; ++cycle) {
    fault_dut.eval();
    check(!fault_dut.io_DataBus_AW_AWVALID &&
              !fault_dut.io_DataBus_W_WVALID,
          "younger buffered store escaped after an older BRESP failure");
    tick(fault_dut);
  }
  fault_dut.final();
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
  wait_until(dut, [&] { return dut.io_DataBus_AR_ARVALID; },
             "DCache refill never issued AR");
  check(dut.io_DataBus_AR_ARADDR == line_base,
        "DCache refill did not start at the line base");
  check(dut.io_DataBus_AR_ARLEN == 3,
        "DCache refill did not use one four-beat burst");
  dut.io_DataBus_AR_ARREADY = 1;
  tick(dut);
  dut.io_DataBus_AR_ARREADY = 0;

  bool retired = false;
  uint32_t result = 0;
  for (uint32_t beat = 0; beat < 4; ++beat) {
    wait_until(dut, [&] { return dut.io_DataBus_R_RREADY; },
               "DCache refill never became ready for R");
    dut.io_DataBus_R_RDATA = beat == ((address >> 2) & 3U)
                                 ? requested_word
                                 : 0x10000000U + beat;
    dut.io_DataBus_R_RRESP = 0;
    dut.io_DataBus_R_RLAST = beat == 3;
    dut.io_DataBus_R_RVALID = 1;
    tick(dut);
    dut.eval();
    if (dut.io_out_valid) {
      retired = true;
      result = dut.io_out_bits_LoadData;
    }
    dut.io_DataBus_R_RVALID = 0;
    dut.io_DataBus_R_RLAST = 0;
  }

  if (!retired) {
    wait_until(dut, [&] { return dut.io_out_valid; },
               "refilled load never retired");
    result = dut.io_out_bits_LoadData;
  }
  if (dut.io_out_valid) {
    tick(dut);
  }
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

static uint32_t load_while_flushing(DUT &dut, uint32_t address,
                                    uint32_t requested_word) {
  begin_memory_op(dut, address, false);
  wait_until(dut, [&] { return dut.io_DataBus_AR_ARVALID; },
             "flush-overlap load never issued its first AR");

  // Keep AR stalled while flushing.  Once DCache has accepted the demand,
  // LSU has no cancellation path and must keep waiting for its one response.
  dut.io_DCacheFlush = 1;
  tick(dut);
  dut.io_DCacheFlush = 0;

  const uint32_t line_base = address & ~0xfU;
  wait_until(dut, [&] { return dut.io_DataBus_AR_ARVALID; },
             "flush-overlap refill stopped issuing AR");
  check(dut.io_DataBus_AR_ARADDR == line_base &&
            dut.io_DataBus_AR_ARLEN == 3,
        "flush-overlap refill did not preserve its line burst");
  dut.io_DataBus_AR_ARREADY = 1;
  tick(dut);
  dut.io_DataBus_AR_ARREADY = 0;

  for (uint32_t beat = 0; beat < 4; ++beat) {
    wait_until(dut, [&] { return dut.io_DataBus_R_RREADY; },
               "flush-overlap refill stopped waiting for R");
    dut.io_DataBus_R_RDATA = beat == ((address >> 2) & 3U)
                                 ? requested_word
                                 : 0x20000000U + beat;
    dut.io_DataBus_R_RRESP = 0;
    dut.io_DataBus_R_RLAST = beat == 3;
    dut.io_DataBus_R_RVALID = 1;
    tick(dut);
    dut.io_DataBus_R_RVALID = 0;
    dut.io_DataBus_R_RLAST = 0;
  }

  wait_until(dut, [&] { return dut.io_out_valid; },
             "LSU hung after DCache flush overlapped an accepted load");
  const uint32_t result = dut.io_out_bits_LoadData;
  check(dut.io_out_bits_Retire,
        "successful flush-overlap load did not retire");
  tick(dut);
  return result;
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
    check(dut.io_AccessFault,
          "failed store did not emit a precise AXI fault event");
    check(dut.io_AccessFaultResp == response,
          "failed store lost its BRESP code");
  }
  tick(dut);
  check(!dut.io_AccessFault,
        "store AXI fault event was not a single-cycle pulse");
}

static void check_misaligned_is_not_axi_fault(DUT &dut) {
  constexpr uint32_t pc = 0x80000128U;
  dut.io_in_bits_WidthSelect = 2;
  begin_memory_op(dut, 0x80000002U, false, 0, pc);

  for (int cycle = 0; cycle < 20 && !dut.io_out_valid; ++cycle) {
    dut.eval();
    check(!dut.io_DataBus_AR_ARVALID,
          "misaligned load incorrectly issued an AXI read");
    check(!dut.io_AccessFault,
          "misaligned load was misreported as an AXI access fault");
    tick(dut);
  }
  dut.eval();
  check(dut.io_out_valid, "misaligned load never reached trap commit");
  check(dut.io_MemTrapCommit,
        "misaligned load did not produce a precise memory trap");
  check(dut.io_MemTrapCause == 4,
        "misaligned load did not report cause 4");
  check(dut.io_MemTrapPC == pc, "misaligned load reported the wrong PC");
  check(!dut.io_AccessFault,
        "misaligned load produced a fake RESP=0 AXI fault");
  check(!dut.io_out_bits_Retire,
        "misaligned load was incorrectly marked retired");
  tick(dut);
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  return run_test("LSU precise store BRESP and flush-safe DCache", [] {
    check_write_buffer_depth_and_precision();

    DUT dut;
    defaults(dut);
    reset(dut);

    check(load_while_flushing(dut, 0x80000104U, 0x55667788U) ==
              0x55667788U,
          "flush-overlap load returned the wrong word");

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

    check_misaligned_is_not_axi_fault(dut);
    dut.final();
  });
}
