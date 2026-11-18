// AXI5Arbiter exclusive-request regression.
//
// The arbiter consumes the LOCK value latched by the slave-side WidthBridge.
// An exclusive DMA request must never be forwarded as a normal transaction:
// it is accepted locally, drains its W beats, and terminates with DECERR for
// both read and write.
#include "Vysyx_26030103_AXI5Arbiter.h"
#include "test_common.hpp"
#include <verilated.h>

using DUT = Vysyx_26030103_AXI5Arbiter;

static void defaults(DUT &dut) {
  dut.io_dmaAWLock = 0;
  dut.io_dmaARLock = 0;
  dut.io_atomicBusy = 0;
  dut.io_dmaSnoopDone = 0;

  dut.io_ifu_AW_AWVALID = 0;
  dut.io_ifu_W_WVALID = 0;
  dut.io_ifu_B_BREADY = 1;
  dut.io_ifu_AR_ARVALID = 0;
  dut.io_ifu_R_RREADY = 1;

  dut.io_lsu_AW_AWVALID = 0;
  dut.io_lsu_AW_AWADDR = 0;
  dut.io_lsu_AW_AWID = 0;
  dut.io_lsu_AW_AWLEN = 0;
  dut.io_lsu_AW_AWSIZE = 2;
  dut.io_lsu_AW_AWBURST = 1;
  dut.io_lsu_W_WVALID = 0;
  dut.io_lsu_W_WDATA = 0;
  dut.io_lsu_W_WSTRB = 0;
  dut.io_lsu_W_WLAST = 0;
  dut.io_lsu_B_BREADY = 1;
  dut.io_lsu_AR_ARVALID = 0;
  dut.io_lsu_AR_ARID = 0;
  dut.io_lsu_AR_ARADDR = 0;
  dut.io_lsu_AR_ARLEN = 0;
  dut.io_lsu_AR_ARSIZE = 2;
  dut.io_lsu_AR_ARBURST = 1;
  dut.io_lsu_R_RREADY = 1;

  dut.io_dma_AW_AWVALID = 0;
  dut.io_dma_AW_AWADDR = 0;
  dut.io_dma_AW_AWID = 0;
  dut.io_dma_AW_AWLEN = 0;
  dut.io_dma_AW_AWSIZE = 2;
  dut.io_dma_AW_AWBURST = 1;
  dut.io_dma_AW_AWPROT = 0;
  dut.io_dma_AW_AWCACHE = 0;
  dut.io_dma_AW_AWQOS = 0;
  dut.io_dma_W_WVALID = 0;
  dut.io_dma_W_WDATA = 0;
  dut.io_dma_W_WSTRB = 0;
  dut.io_dma_W_WLAST = 0;
  dut.io_dma_B_BREADY = 0;
  dut.io_dma_AR_ARVALID = 0;
  dut.io_dma_AR_ARADDR = 0;
  dut.io_dma_AR_ARID = 0;
  dut.io_dma_AR_ARLEN = 0;
  dut.io_dma_AR_ARSIZE = 2;
  dut.io_dma_AR_ARBURST = 1;
  dut.io_dma_AR_ARPROT = 0;
  dut.io_dma_AR_ARCACHE = 0;
  dut.io_dma_AR_ARQOS = 0;
  dut.io_dma_R_RREADY = 0;

  dut.io_memory_AW_AWREADY = 1;
  dut.io_memory_W_WREADY = 1;
  dut.io_memory_B_BVALID = 0;
  dut.io_memory_B_BID = 0;
  dut.io_memory_B_BRESP = 0;
  dut.io_memory_AR_ARREADY = 1;
  dut.io_memory_R_RVALID = 0;
  dut.io_memory_R_RID = 0;
  dut.io_memory_R_RDATA = 0;
  dut.io_memory_R_RRESP = 0;
  dut.io_memory_R_RLAST = 0;
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  int status = 0;

  status |= run_test("Arbiter rejects exclusive DMA write with local DECERR",
                     [] {
    DUT dut;
    defaults(dut);
    reset(dut);

    dut.io_dmaAWLock = 1;
    dut.io_dma_AW_AWVALID = 1;
    dut.io_dma_AW_AWADDR = 0xc0000100U;
    dut.io_dma_AW_AWID = 5;
    dut.io_dma_AW_AWLEN = 0;
    dut.io_dma_AW_AWSIZE = 2;
    dut.io_dma_AW_AWBURST = 1;
    dut.io_dma_W_WVALID = 1;
    dut.io_dma_W_WDATA = 0x11223344U;
    dut.io_dma_W_WSTRB = 0xf;
    dut.io_dma_W_WLAST = 1;

    for (int cycle = 0; cycle < 40; ++cycle) {
      dut.eval();
      check(!dut.io_memory_AW_AWVALID && !dut.io_memory_W_WVALID,
            "exclusive DMA write was forwarded to downstream memory");
      if (dut.io_dma_B_BVALID) {
        break;
      }
      tick(dut);
    }
    check(dut.io_dma_B_BVALID,
          "exclusive DMA write never produced a local response");
    check(dut.io_dma_B_BRESP == 3,
          "exclusive DMA write did not terminate with DECERR");
    check(dut.io_dma_B_BID == 5,
          "exclusive DMA write lost its upstream BID");
    dut.io_dma_B_BREADY = 1;
    tick(dut);
    dut.io_dma_B_BREADY = 0;
    dut.final();
  });

  status |= run_test("Arbiter rejects exclusive DMA read with local DECERR",
                     [] {
    DUT dut;
    defaults(dut);
    reset(dut);

    dut.io_dmaARLock = 1;
    dut.io_dma_AR_ARVALID = 1;
    dut.io_dma_AR_ARADDR = 0xc0000200U;
    dut.io_dma_AR_ARID = 3;
    dut.io_dma_AR_ARLEN = 0;
    dut.io_dma_AR_ARSIZE = 2;
    dut.io_dma_AR_ARBURST = 1;

    for (int cycle = 0; cycle < 40; ++cycle) {
      dut.eval();
      check(!dut.io_memory_AR_ARVALID,
            "exclusive DMA read was forwarded to downstream memory");
      if (dut.io_dma_R_RVALID) {
        break;
      }
      tick(dut);
    }
    check(dut.io_dma_R_RVALID,
          "exclusive DMA read never produced a local response");
    check(dut.io_dma_R_RRESP == 3,
          "exclusive DMA read did not terminate with DECERR");
    check(dut.io_dma_R_RID == 3,
          "exclusive DMA read lost its upstream RID");
    check(dut.io_dma_R_RLAST,
          "exclusive DMA read did not terminate with RLAST");
    dut.io_dma_R_RREADY = 1;
    tick(dut);
    dut.io_dma_R_RREADY = 0;
    dut.final();
  });

  return status;
}
