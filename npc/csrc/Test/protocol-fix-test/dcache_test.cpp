#include "Vysyx_26030103_DCache.h"
#include "test_common.hpp"
#include <verilated.h>

using DUT = Vysyx_26030103_DCache;

static void defaults(DUT &dut) {
  dut.io_req_valid = 0;
  dut.io_req_bits_addr = 0;
  dut.io_req_bits_WidthSelect = 2;
  dut.io_req_bits_signed = 0;
  dut.io_resp_ready = 1;
  dut.io_StoreValid = 0;
  dut.io_StoreAddr = 0;
  dut.io_StoreData = 0;
  dut.io_StoreStrb = 0;
  dut.io_AXI_AR_ARREADY = 0;
  dut.io_AXI_R_RDATA = 0;
  dut.io_AXI_R_RRESP = 0;
  dut.io_AXI_R_RVALID = 0;
  dut.io_flush = 0;
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  return run_test("DCache flush preserves pending ARVALID", [] {
    DUT dut;
    defaults(dut);
    reset(dut);

    dut.io_req_bits_addr = 0x80000004U;
    dut.io_req_valid = 1;
    dut.eval();
    check(dut.io_req_ready, "DCache did not accept the initial miss");
    tick(dut);
    dut.io_req_valid = 0;
    dut.eval();
    check(dut.io_AXI_AR_ARVALID,
          "DCache did not present ARVALID for the miss");
    const uint32_t address = dut.io_AXI_AR_ARADDR;

    dut.io_flush = 1;
    tick(dut);
    dut.io_flush = 0;
    dut.eval();
    check(dut.io_AXI_AR_ARVALID,
          "flush withdrew ARVALID before ARREADY");
    check(dut.io_AXI_AR_ARADDR == address,
          "flush changed a stalled AR address");

    tick(dut);
    dut.eval();
    check(dut.io_AXI_AR_ARVALID,
          "stalled ARVALID was not held for a second cycle");

    dut.io_AXI_AR_ARREADY = 1;
    tick(dut);
    dut.io_AXI_AR_ARREADY = 0;
    dut.eval();
    check(dut.io_AXI_R_RREADY,
          "DCache did not drain the response after flushed AR handshake");

    dut.io_AXI_R_RDATA = 0x12345678U;
    dut.io_AXI_R_RVALID = 1;
    tick(dut);
    dut.io_AXI_R_RVALID = 0;
    dut.eval();
    check(!dut.io_resp_valid,
          "DCache exposed a response from a flushed transaction");
    check(dut.io_req_ready,
          "DCache did not return to idle after draining flushed response");
    dut.final();
  });
}
