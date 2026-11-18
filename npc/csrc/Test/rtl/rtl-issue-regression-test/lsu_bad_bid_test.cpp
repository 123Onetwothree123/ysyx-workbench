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
  dut.io_in_bits_NextPC = 0;
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
  dut.io_in_bits_pc = 0;
  dut.io_DataBus_AW_AWREADY = 0;
  dut.io_DataBus_W_WREADY = 0;
  dut.io_DataBus_B_BID = 0;
  dut.io_DataBus_B_BRESP = 0;
  dut.io_DataBus_B_BVALID = 0;
  dut.io_DataBus_AR_ARREADY = 0;
  dut.io_DataBus_R_RDATA = 0;
  dut.io_DataBus_R_RRESP = 0;
  dut.io_DataBus_R_RLAST = 0;
  dut.io_DataBus_R_RVALID = 0;
  dut.io_DCacheFlush = 0;
}

static void issue_store_to_b_response(DUT &dut) {
  dut.io_in_bits_Instruction = 0x00002023U;
  dut.io_in_bits_ALUResult = 0x80001000U;
  dut.io_in_bits_snpc = 0x80000204U;
  dut.io_in_bits_NextPC = 0x80000204U;
  dut.io_in_bits_MemoryValid = 1;
  dut.io_in_bits_MemoryWrite = 1;
  dut.io_in_bits_WidthSelect = 2;
  dut.io_in_bits_StoreData = 0xdeadbeefU;
  dut.io_in_bits_pc = 0x80000200U;
  dut.io_in_valid = 1;
  dut.eval();
  check(dut.io_in_ready, "LSU did not accept the store");
  tick(dut);
  dut.io_in_valid = 0;

  dut.io_DataBus_AW_AWREADY = 1;
  dut.io_DataBus_W_WREADY = 1;
  wait_until(
      dut, [&] { return dut.io_DataBus_AW_AWVALID && dut.io_DataBus_W_WVALID; },
      "store never issued AW/W");
  tick(dut);
  dut.io_DataBus_AW_AWREADY = 0;
  dut.io_DataBus_W_WREADY = 0;

  wait_until(
      dut, [&] { return dut.io_DataBus_B_BREADY; },
      "store never entered B-response state");
}

static void matching_bid_completes_store_control() {
  DUT dut;
  defaults(dut);
  reset(dut);
  issue_store_to_b_response(dut);

  dut.io_DataBus_B_BID = 0;
  dut.io_DataBus_B_BRESP = 0;
  dut.io_DataBus_B_BVALID = 1;
  tick(dut);
  dut.io_DataBus_B_BVALID = 0;

  wait_until(
      dut, [&] { return dut.io_out_valid; },
      "matching BID did not complete the store");
  check(dut.io_out_bits_Retire && !dut.io_MemTrapCommit,
        "matching OKAY response did not retire the store normally");
  dut.final();
}

static void wrong_bid_must_not_complete_store() {
  DUT dut;
  defaults(dut);
  reset(dut);
  issue_store_to_b_response(dut);

  // The LSU issued AWID=0.  A response carrying BID=1 is not this store's
  // completion and must neither retire it nor turn it into a memory trap.
  dut.io_DataBus_B_BID = 1;
  dut.io_DataBus_B_BRESP = 2;
  dut.io_DataBus_B_BVALID = 1;
  dut.eval();
  check(dut.io_DataBus_B_BREADY,
        "test did not present the bad BID in the LSU B-response state");
  tick(dut);
  dut.io_DataBus_B_BVALID = 0;

  for (int cycle = 0; cycle < 4; ++cycle) {
    dut.eval();
    check(!dut.io_Complete,
          "store transaction completed after a response with the wrong BID");
    check(!dut.io_out_valid,
          "store reached retirement after a response with the wrong BID");
    check(!dut.io_MemTrapCommit,
          "wrong BID was converted into a precise architectural trap");
    check(dut.io_DataBus_B_BREADY,
          "LSU stopped accepting responses after discarding a wrong BID");
    check(!dut.io_DataBus_AW_AWVALID && !dut.io_DataBus_W_WVALID,
          "wrong BID caused the store request to be issued a second time");
    tick(dut);
  }

  // A later matching response must still complete exactly the original store;
  // the discarded SLVERR from BID 1 must not contaminate its status.
  dut.io_DataBus_B_BID = 0;
  dut.io_DataBus_B_BRESP = 0;
  dut.io_DataBus_B_BVALID = 1;
  tick(dut);
  dut.io_DataBus_B_BVALID = 0;
  wait_until(
      dut, [&] { return dut.io_out_valid; },
      "store did not recover after a later matching BID");
  check(dut.io_out_bits_Retire && !dut.io_MemTrapCommit && !dut.io_AccessFault,
        "discarded wrong-BID SLVERR contaminated the matching response");
  tick(dut);
  dut.eval();
  check(!dut.io_out_valid, "store retired more than once after wrong BID");
  dut.final();
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  int failures = 0;
  failures += run_test("LSU matching BID positive control",
                       matching_bid_completes_store_control);
  failures += run_test("LSU ignores a B response with the wrong BID",
                       wrong_bid_must_not_complete_store);
  return failures == 0 ? 0 : 1;
}
