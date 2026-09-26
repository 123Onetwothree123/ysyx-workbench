#include "VLSUAccessFaultBackpressureHarness.h"
#include "test_common.hpp"

#include <cstdint>
#include <string>
#include <verilated.h>

using DUT = VLSUAccessFaultBackpressureHarness;

static constexpr std::uint32_t kLoadAddress = 0x02000000U;
static constexpr std::uint32_t kLoadPC = 0x80000100U;
static constexpr std::uint8_t kSlaveError = 2U;

static void defaults(DUT &dut) {
  dut.io_in_valid = 0;
  dut.io_in_bits_Instruction = 0x00002003U;
  dut.io_in_bits_Retire = 1;
  dut.io_in_bits_Rd = 1;
  dut.io_in_bits_RegisterWrite = 1;
  dut.io_in_bits_WBSelect = 1;
  dut.io_in_bits_ALUResult = kLoadAddress;
  dut.io_in_bits_LoadData = 0;
  dut.io_in_bits_snpc = kLoadPC + 4U;
  dut.io_in_bits_NextPC = kLoadPC + 4U;
  dut.io_in_bits_CSRReadData = 0;
  dut.io_in_bits_CSRStateMstatus = 0;
  dut.io_in_bits_CSRStateMtvec = 0;
  dut.io_in_bits_CSRStateMepc = 0;
  dut.io_in_bits_CSRStateMcause = 0;
  dut.io_in_bits_MemoryValid = 1;
  dut.io_in_bits_MemoryWrite = 0;
  dut.io_in_bits_WidthSelect = 2;
  dut.io_in_bits_LoadSigned = 0;
  dut.io_in_bits_StoreData = 0;
  dut.io_in_bits_pc = kLoadPC;
  dut.io_in_bits_ExceptionValid = 0;
  dut.io_in_bits_ExceptionCause = 0;

  dut.io_out_ready = 0;
  dut.io_DataBus_AW_AWREADY = 0;
  dut.io_DataBus_W_WREADY = 0;
  dut.io_DataBus_B_BID = 0;
  dut.io_DataBus_B_BRESP = 0;
  dut.io_DataBus_B_BVALID = 0;
  dut.io_DataBus_AR_ARREADY = 0;
  dut.io_DataBus_R_RID = 0;
  dut.io_DataBus_R_RDATA = 0;
  dut.io_DataBus_R_RRESP = 0;
  dut.io_DataBus_R_RLAST = 0;
  dut.io_DataBus_R_RVALID = 0;
  dut.io_DCacheFlush = 0;
}

static void issue_failing_load(DUT &dut) {
  dut.io_in_valid = 1;
  wait_until(dut, [&] { return dut.io_in_ready; },
             "LSU never accepted the test load");
  tick(dut);
  dut.io_in_valid = 0;

  wait_until(dut, [&] { return dut.io_DataBus_AR_ARVALID; },
             "LSU never issued the load address");
  check(dut.io_DataBus_AR_ARADDR == kLoadAddress,
        "LSU issued the wrong load address");
  check(dut.io_DataBus_AR_ARLEN == 0 && dut.io_DataBus_AR_ARSIZE == 2,
        "LSU did not issue a single word read");
  dut.io_DataBus_AR_ARREADY = 1;
  tick(dut);
  dut.io_DataBus_AR_ARREADY = 0;

  wait_until(dut, [&] { return dut.io_DataBus_R_RREADY; },
             "LSU never became ready for the failing response");
  dut.io_DataBus_R_RDATA = 0xdeadbeefU;
  dut.io_DataBus_R_RRESP = kSlaveError;
  dut.io_DataBus_R_RLAST = 1;
  dut.io_DataBus_R_RVALID = 1;
  tick(dut);
  dut.io_DataBus_R_RVALID = 0;
  dut.io_DataBus_R_RLAST = 0;

  wait_until(dut, [&] { return dut.io_out_valid; },
             "failing load never reached the LSU output");
}

static void immediate_commit_positive_control() {
  DUT dut;
  defaults(dut);
  reset(dut);
  dut.io_out_ready = 1;
  issue_failing_load(dut);

  check(dut.io_MemTrapCommit && dut.io_AccessFault,
        "failing load did not commit an access fault");
  check(dut.io_AccessFaultResp == kSlaveError,
        "positive control lost RRESP without downstream backpressure");
  check(dut.io_MemTrapCause == 5 && dut.io_MemTrapPC == kLoadPC,
        "positive control reported the wrong precise trap");
  check(dut.io_FlushIDEX && dut.io_FlushEXMEM,
        "positive control did not flush younger instructions");
  check(!dut.io_out_bits_Retire && !dut.io_out_bits_RegisterWrite,
        "failing load retained an architectural side effect");
  dut.final();
}

static void response_must_survive_backpressure() {
  DUT dut;
  defaults(dut);
  reset(dut);
  dut.io_out_ready = 0;
  issue_failing_load(dut);

  for (int cycle = 0; cycle < 4; ++cycle) {
    dut.eval();
    check(dut.io_out_valid,
          "LSU withdrew out.valid while the fault was backpressured");
    check(!dut.io_MemTrapCommit && !dut.io_AccessFault,
          "LSU committed the fault before the output handshake");
    check(
        dut.io_AccessFaultResp == kSlaveError,
        "LSU changed AccessFaultResp while the fault output was backpressured "
        "(blocked cycle " +
            std::to_string(cycle) + ")");
    check(dut.io_MemTrapPC == kLoadPC,
          "LSU changed the fault PC while the output was backpressured");
    tick(dut);
  }

  dut.io_out_ready = 1;
  dut.eval();
  check(dut.io_MemTrapCommit && dut.io_AccessFault,
        "delayed output handshake did not commit the access fault");
  check(dut.io_AccessFaultResp == kSlaveError,
        "delayed access-fault commit did not preserve the original RRESP");
  check(dut.io_MemTrapCause == 5 && dut.io_MemTrapPC == kLoadPC,
        "delayed access-fault commit reported the wrong precise trap");
  check(dut.io_FlushIDEX && dut.io_FlushEXMEM,
        "delayed access-fault commit did not flush younger instructions");
  check(!dut.io_out_bits_Retire && !dut.io_out_bits_RegisterWrite,
        "delayed failing load retained an architectural side effect");
  tick(dut);
  dut.eval();
  check(!dut.io_MemTrapCommit && !dut.io_AccessFault,
        "access-fault commit pulse lasted more than one cycle");
  dut.final();
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  int failures = 0;
  failures += run_test("LSU access-fault immediate commit positive control",
                       immediate_commit_positive_control);
  failures += run_test("LSU preserves access-fault RESP under backpressure",
                       response_must_survive_backpressure);
  return failures == 0 ? 0 : 1;
}
