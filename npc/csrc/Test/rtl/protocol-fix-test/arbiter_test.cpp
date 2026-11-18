#include "Vysyx_26030103_AXI5Arbiter.h"
#include "test_common.hpp"
#include <verilated.h>

using DUT = Vysyx_26030103_AXI5Arbiter;

static void defaults(DUT &dut) {
  dut.io_dmaARLock = 0;
  dut.io_dmaAWLock = 0;
  dut.io_ifu_AR_ARADDR = 0;
  dut.io_ifu_AR_ARLEN = 0;
  dut.io_ifu_AR_ARVALID = 0;

  dut.io_lsu_AW_AWADDR = 0;
  dut.io_lsu_AW_AWSIZE = 2;
  dut.io_lsu_AW_AWVALID = 0;
  dut.io_lsu_W_WDATA = 0;
  dut.io_lsu_W_WSTRB = 0xf;
  dut.io_lsu_W_WLAST = 0;
  dut.io_lsu_W_WVALID = 0;
  dut.io_lsu_B_BREADY = 1;
  dut.io_lsu_AR_ARADDR = 0;
  dut.io_lsu_AR_ARLEN = 0;
  dut.io_lsu_AR_ARSIZE = 2;
  dut.io_lsu_AR_ARVALID = 0;
  dut.io_lsu_R_RREADY = 1;

  dut.io_dma_AW_AWID = 0;
  dut.io_dma_AW_AWADDR = 0;
  dut.io_dma_AW_AWLEN = 0;
  dut.io_dma_AW_AWSIZE = 2;
  dut.io_dma_AW_AWBURST = 1;
  dut.io_dma_AW_AWVALID = 0;
  dut.io_dma_W_WDATA = 0;
  dut.io_dma_W_WSTRB = 0xf;
  dut.io_dma_W_WLAST = 0;
  dut.io_dma_W_WVALID = 0;
  dut.io_dma_B_BREADY = 0;
  dut.io_dma_AR_ARID = 0;
  dut.io_dma_AR_ARADDR = 0;
  dut.io_dma_AR_ARLEN = 0;
  dut.io_dma_AR_ARSIZE = 2;
  dut.io_dma_AR_ARBURST = 1;
  dut.io_dma_AR_ARVALID = 0;
  dut.io_dma_R_RREADY = 0;

  dut.io_memory_AW_AWREADY = 0;
  dut.io_memory_W_WREADY = 0;
  dut.io_memory_B_BID = 0;
  dut.io_memory_B_BRESP = 0;
  dut.io_memory_B_BVALID = 0;
  dut.io_memory_AR_ARREADY = 0;
  dut.io_memory_R_RDATA = 0;
  dut.io_memory_R_RRESP = 0;
  dut.io_memory_R_RID = 0;
  dut.io_memory_R_RLAST = 0;
  dut.io_memory_R_RVALID = 0;
}

static void finish_read(DUT &dut, uint8_t rid, uint32_t data,
                        bool expect_lsu) {
  dut.io_memory_R_RID = rid;
  dut.io_memory_R_RDATA = data;
  dut.io_memory_R_RLAST = 1;
  dut.io_memory_R_RVALID = 1;
  tick(dut); // memory -> skid
  dut.io_memory_R_RVALID = 0;
  dut.eval();
  check(static_cast<bool>(dut.io_lsu_R_RVALID) == expect_lsu &&
            static_cast<bool>(dut.io_ifu_R_RVALID) == !expect_lsu,
        "read response ID was routed to the wrong master");
  check((expect_lsu ? dut.io_lsu_R_RDATA : dut.io_ifu_R_RDATA) == data,
        "read response data was corrupted by ID routing");
  tick(dut); // granted master consumes the skid entry
  dut.io_memory_R_RLAST = 0;
}

static void finish_dma_read(DUT &dut, uint8_t original_id, uint32_t data) {
  dut.io_memory_R_RID = 2;
  dut.io_memory_R_RDATA = data;
  dut.io_memory_R_RRESP = 0;
  dut.io_memory_R_RLAST = 1;
  dut.io_memory_R_RVALID = 1;
  tick(dut); // memory -> skid
  dut.io_memory_R_RVALID = 0;
  dut.eval();
  check(dut.io_dma_R_RVALID && dut.io_dma_R_RID == original_id &&
            dut.io_dma_R_RDATA == data && dut.io_dma_R_RLAST,
        "DMA read response did not restore its upstream RID/payload");
  check(!dut.io_ifu_R_RVALID && !dut.io_lsu_R_RVALID,
        "DMA read response was also delivered to a CPU requester");
  dut.io_dma_R_RREADY = 1;
  tick(dut);
  dut.io_dma_R_RREADY = 0;
  dut.io_memory_R_RLAST = 0;
}

static void test_multibeat_write(DUT &dut) {
  dut.io_memory_AW_AWREADY = 1;
  dut.io_memory_W_WREADY = 1;
  dut.io_lsu_AW_AWADDR = 0x80000100U;
  dut.io_lsu_AW_AWVALID = 1;
  dut.io_lsu_W_WVALID = 1;
  dut.io_lsu_W_WDATA = 0x11111111U;
  dut.io_lsu_W_WLAST = 0;
  // A simultaneous DMA write must wait once this LSU transaction wins.
  dut.io_dma_AW_AWID = 0xa;
  dut.io_dma_AW_AWADDR = 0xc0000200U;
  dut.io_dma_AW_AWVALID = 1;
  dut.io_dma_W_WDATA = 0xddddddddU;
  dut.io_dma_W_WLAST = 1;
  dut.io_dma_W_WVALID = 1;
  // A fetch address must be accepted independently while the LSU write starts.
  dut.io_memory_AR_ARREADY = 1;
  dut.io_ifu_AR_ARADDR = 0x80000040U;
  dut.io_ifu_AR_ARVALID = 1;

  dut.eval();
  check(dut.io_memory_AR_ARVALID && dut.io_memory_AR_ARID == 0,
        "IFU read was blocked by an independent LSU write");
  tick(dut); // accept IFU AR and enter write request
  dut.io_ifu_AR_ARVALID = 0;
  dut.eval();
  check(dut.io_memory_AW_AWVALID && dut.io_memory_W_WVALID,
        "first AW/W beat was not forwarded");
  check(dut.io_memory_AW_AWID == 0 && !dut.io_dma_AW_AWREADY &&
            !dut.io_dma_W_WREADY,
        "DMA write interleaved with an already selected LSU write");
  tick(dut); // AW and first W beat
  dut.io_lsu_AW_AWVALID = 0;

  dut.io_lsu_W_WDATA = 0x22222222U;
  dut.io_lsu_W_WLAST = 0;
  dut.eval();
  check(dut.io_lsu_W_WREADY && dut.io_memory_W_WVALID,
        "arbiter stopped before WLAST on the second beat");
  tick(dut);
  check(!dut.io_lsu_B_BVALID,
        "arbiter entered write response before WLAST");

  dut.io_lsu_W_WDATA = 0x33333333U;
  dut.io_lsu_W_WLAST = 1;
  dut.eval();
  check(dut.io_lsu_W_WREADY && dut.io_memory_W_WLAST,
        "final WLAST beat was not forwarded");
  tick(dut);
  dut.io_lsu_W_WVALID = 0;

  dut.io_memory_B_BRESP = 0;
  dut.io_memory_B_BVALID = 1;
  dut.eval();
  check(dut.io_lsu_B_BVALID && dut.io_lsu_B_BRESP == 0,
        "write response was not returned to LSU");
  tick(dut);
  dut.io_memory_B_BVALID = 0;

  // DMA kept VALID asserted throughout the LSU transaction, as AXI requires.
  // Once the LSU B response completes, it must obtain the next write grant.
  tick(dut); // idle -> select the waiting DMA transaction
  dut.eval();
  check(dut.io_memory_AW_AWVALID && dut.io_memory_AW_AWID == 2 &&
            !dut.io_memory_W_WVALID && !dut.io_dma_W_WREADY,
        "waiting DMA AW did not issue before its guarded W channel");
  tick(dut); // accept and classify DMA AW
  dut.io_dma_AW_AWVALID = 0;
  dut.eval();
  check(dut.io_memory_W_WVALID && dut.io_memory_W_WLAST &&
            dut.io_dma_W_WREADY,
        "waiting DMA W did not issue after its AW was accepted");
  tick(dut);
  dut.io_dma_W_WVALID = 0;
  dut.io_memory_B_BID = 2;
  dut.io_memory_B_BRESP = 0;
  dut.io_memory_B_BVALID = 1;
  dut.io_dma_B_BREADY = 0;
  dut.eval();
  check(dut.io_memory_B_BREADY && dut.io_DMAWriteInvalidate,
        "waiting DMA B was not captured independently of upstream BREADY");
  tick(dut);
  dut.io_memory_B_BVALID = 0;
  dut.io_memory_B_BID = 0;
  dut.eval();
  check(dut.io_dma_B_BVALID && dut.io_dma_B_BID == 0xa,
        "buffered DMA write did not restore its original BID");
  dut.io_dma_B_BREADY = 1;
  tick(dut);
  dut.io_dma_B_BREADY = 0;

  finish_read(dut, 0, 0x1234abcdU, false);
}

static void test_dma_read_burst_and_id_restore(DUT &dut) {
  dut.io_memory_AR_ARREADY = 1;
  dut.io_dma_AR_ARID = 0xa;
  dut.io_dma_AR_ARADDR = 0xc0001040U;
  dut.io_dma_AR_ARLEN = 2;
  dut.io_dma_AR_ARSIZE = 2;
  dut.io_dma_AR_ARBURST = 1;
  dut.io_dma_AR_ARVALID = 1;

  dut.eval();
  check(dut.io_memory_AR_ARVALID && dut.io_dma_AR_ARREADY,
        "DMA read address was not accepted");
  check(dut.io_memory_AR_ARID == 2 &&
            dut.io_memory_AR_ARADDR == 0xc0001040U &&
            dut.io_memory_AR_ARLEN == 2,
        "DMA read address/length was not forwarded with reserved RID 2");
  tick(dut);

  // Only one DMA read may be outstanding because one register stores its
  // original upstream ID.
  dut.io_dma_AR_ARID = 3;
  dut.io_dma_AR_ARADDR = 0xc0002000U;
  dut.io_dma_AR_ARLEN = 0;
  dut.eval();
  check(!dut.io_dma_AR_ARREADY,
        "arbiter accepted a second DMA read before the first RLAST");

  for (unsigned beat = 0; beat < 3; ++beat) {
    const uint32_t data = 0xd0000000U + beat;
    dut.io_memory_R_RID = 2;
    dut.io_memory_R_RDATA = data;
    dut.io_memory_R_RRESP = 0;
    dut.io_memory_R_RLAST = beat == 2;
    dut.io_memory_R_RVALID = 1;
    tick(dut); // capture one downstream beat
    dut.io_memory_R_RVALID = 0;
    dut.eval();
    check(dut.io_dma_R_RVALID && dut.io_dma_R_RID == 0xa &&
              dut.io_dma_R_RDATA == data &&
              static_cast<bool>(dut.io_dma_R_RLAST) == (beat == 2),
          "DMA burst response lost RID, data, or RLAST");

    // The skid entry and restored RID must be stable under upstream pressure.
    tick(dut);
    check(dut.io_dma_R_RVALID && dut.io_dma_R_RID == 0xa &&
              dut.io_dma_R_RDATA == data,
          "DMA R payload changed while RREADY was low");
    dut.io_dma_R_RREADY = 1;
    tick(dut);
    dut.io_dma_R_RREADY = 0;
  }
  dut.io_memory_R_RLAST = 0;

  dut.eval();
  check(dut.io_dma_AR_ARREADY && dut.io_memory_AR_ARID == 2,
        "DMA RID slot was not released after consuming RLAST");
  tick(dut); // accept the continuously asserted second request
  dut.io_dma_AR_ARVALID = 0;
  finish_dma_read(dut, 3, 0x20002000U);
}

static void test_zero_latency_dma_read(DUT &dut) {
  dut.io_memory_AR_ARREADY = 1;
  dut.io_dma_AR_ARID = 0xd;
  dut.io_dma_AR_ARADDR = 0xc0002800U;
  dut.io_dma_AR_ARLEN = 0;
  dut.io_dma_AR_ARVALID = 1;
  dut.io_memory_R_RID = 2;
  dut.io_memory_R_RDATA = 0xd00d0001U;
  dut.io_memory_R_RRESP = 0;
  dut.io_memory_R_RLAST = 1;
  dut.io_memory_R_RVALID = 1;
  dut.eval();
  check(dut.io_memory_AR_ARVALID && dut.io_memory_R_RREADY,
        "same-cycle DMA AR/R was not accepted");
  tick(dut);
  dut.io_dma_AR_ARVALID = 0;
  dut.io_memory_R_RVALID = 0;
  dut.io_memory_R_RLAST = 0;
  dut.eval();
  check(dut.io_dma_R_RVALID && dut.io_dma_R_RID == 0xd &&
            dut.io_dma_R_RDATA == 0xd00d0001U && dut.io_dma_R_RLAST,
        "same-cycle DMA AR/R lost the newly accepted original RID");
  dut.io_dma_R_RREADY = 1;
  tick(dut);
  dut.io_dma_R_RREADY = 0;
}

static void test_dma_r_backpressure_does_not_block_cpu(DUT &dut) {
  // Queue one maximum-length DMA burst while its upstream consumer is stopped.
  dut.io_memory_AR_ARREADY = 1;
  dut.io_dma_AR_ARID = 5;
  dut.io_dma_AR_ARADDR = 0xc0010000U;
  dut.io_dma_AR_ARLEN = 255;
  dut.io_dma_AR_ARVALID = 1;
  dut.eval();
  check(dut.io_dma_AR_ARREADY && dut.io_memory_AR_ARID == 2,
        "maximum DMA burst AR was not accepted");
  tick(dut);
  dut.io_dma_AR_ARVALID = 0;

  // Keep independent CPU requests outstanding before filling the DMA FIFO.
  dut.io_ifu_AR_ARADDR = 0x80008000U;
  dut.io_ifu_AR_ARLEN = 0;
  dut.io_ifu_AR_ARVALID = 1;
  dut.eval();
  check(dut.io_memory_AR_ARVALID && dut.io_memory_AR_ARID == 0,
        "IFU AR was blocked by an outstanding DMA read");
  tick(dut);
  dut.io_ifu_AR_ARVALID = 0;

  dut.io_lsu_AR_ARADDR = 0x80009000U;
  dut.io_lsu_AR_ARLEN = 0;
  dut.io_lsu_AR_ARVALID = 1;
  dut.eval();
  check(dut.io_memory_AR_ARVALID && dut.io_memory_AR_ARID == 1,
        "LSU AR was blocked by an outstanding DMA read");
  tick(dut);
  dut.io_lsu_AR_ARVALID = 0;

  dut.io_dma_R_RREADY = 0;
  dut.io_memory_R_RID = 2;
  dut.io_memory_R_RRESP = 0;
  dut.io_memory_R_RVALID = 1;
  for (unsigned beat = 0; beat < 256; ++beat) {
    dut.io_memory_R_RDATA = 0xd1000000U + beat;
    dut.io_memory_R_RLAST = beat == 255;
    dut.eval();
    check(dut.io_memory_R_RREADY,
          "DMA full-burst FIFO backpressured a legal response beat");
    if (beat != 0) {
      check(dut.io_dma_R_RVALID && dut.io_dma_R_RID == 5 &&
                dut.io_dma_R_RDATA == 0xd1000000U,
            "DMA head payload changed while upstream RREADY was low");
    }
    tick(dut);
  }
  dut.io_memory_R_RVALID = 0;
  dut.io_memory_R_RLAST = 0;
  dut.eval();
  check(dut.io_dma_R_RVALID && dut.io_dma_R_RDATA == 0xd1000000U,
        "full DMA FIFO did not retain its first beat");

  // Even with all 256 DMA entries occupied, per-owner queues must accept and
  // deliver CPU responses rather than inheriting DMA's upstream backpressure.
  finish_read(dut, 1, 0x90009000U, true);
  check(dut.io_dma_R_RVALID && dut.io_dma_R_RDATA == 0xd1000000U,
        "LSU response disturbed the stalled DMA queue");
  finish_read(dut, 0, 0x80008000U, false);
  check(dut.io_dma_R_RVALID && dut.io_dma_R_RDATA == 0xd1000000U,
        "IFU response disturbed the stalled DMA queue");

  dut.io_dma_R_RREADY = 1;
  for (unsigned beat = 0; beat < 256; ++beat) {
    dut.eval();
    check(dut.io_dma_R_RVALID && dut.io_dma_R_RID == 5 &&
              dut.io_dma_R_RDATA == 0xd1000000U + beat &&
              static_cast<bool>(dut.io_dma_R_RLAST) == (beat == 255),
          "DMA FIFO changed beat order/payload while draining");
    tick(dut);
  }
  dut.io_dma_R_RREADY = 0;
  dut.eval();
  check(!dut.io_dma_R_RVALID,
        "DMA FIFO remained valid after consuming its complete burst");
}

static void test_dma_exclusive_is_rejected_locally(DUT &dut) {
  dut.io_memory_AR_ARREADY = 1;
  dut.io_dmaARLock = 1;
  dut.io_dma_AR_ARID = 0xc;
  dut.io_dma_AR_ARADDR = 0xc0020000U;
  dut.io_dma_AR_ARLEN = 1;
  dut.io_dma_AR_ARVALID = 1;
  dut.eval();
  check(dut.io_dma_AR_ARREADY && !dut.io_memory_AR_ARVALID,
        "exclusive DMA AR leaked to memory instead of local rejection");
  tick(dut);
  dut.io_dma_AR_ARVALID = 0;
  for (unsigned beat = 0; beat < 2; ++beat) {
    dut.eval();
    check(dut.io_dma_R_RVALID && dut.io_dma_R_RID == 0xc &&
              dut.io_dma_R_RRESP == 3 &&
              static_cast<bool>(dut.io_dma_R_RLAST) == (beat == 1),
          "exclusive DMA read did not return an ID-preserving DECERR burst");
    check(!dut.io_memory_AR_ARVALID,
          "exclusive DMA read generated a downstream AR while returning DECERR");
    tick(dut);
    check(dut.io_dma_R_RVALID && dut.io_dma_R_RID == 0xc,
          "exclusive DMA DECERR changed under R backpressure");
    dut.io_dma_R_RREADY = 1;
    tick(dut);
    dut.io_dma_R_RREADY = 0;
  }
  dut.io_dmaARLock = 0;

  dut.io_memory_AW_AWREADY = 1;
  dut.io_memory_W_WREADY = 1;
  dut.io_dmaAWLock = 1;
  dut.io_dma_AW_AWID = 0xe;
  dut.io_dma_AW_AWADDR = 0xc0021000U;
  dut.io_dma_AW_AWLEN = 1;
  dut.io_dma_AW_AWVALID = 1;
  dut.io_dma_W_WVALID = 1;
  dut.io_dma_W_WLAST = 0;
  tick(dut); // select DMA owner
  dut.eval();
  check(dut.io_dma_AW_AWREADY && !dut.io_memory_AW_AWVALID &&
            !dut.io_memory_W_WVALID,
        "exclusive DMA AW/W leaked before local classification");
  tick(dut); // accept locked AW
  dut.io_dma_AW_AWVALID = 0;
  for (unsigned beat = 0; beat < 2; ++beat) {
    dut.io_dma_W_WDATA = 0xee000000U + beat;
    dut.io_dma_W_WLAST = beat == 1;
    dut.eval();
    check(dut.io_dma_W_WREADY && !dut.io_memory_W_WVALID,
          "exclusive DMA W was not drained locally");
    tick(dut);
  }
  dut.io_dma_W_WVALID = 0;
  dut.io_dma_W_WLAST = 0;
  dut.eval();
  check(dut.io_dma_B_BVALID && dut.io_dma_B_BID == 0xe &&
            dut.io_dma_B_BRESP == 3 && !dut.io_DMAWriteInvalidate,
        "exclusive DMA write did not return an ID-preserving local DECERR");
  check(!dut.io_memory_AW_AWVALID && !dut.io_memory_W_WVALID,
        "exclusive DMA write touched memory while B was backpressured");
  tick(dut);
  check(dut.io_dma_B_BVALID && dut.io_dma_B_BID == 0xe,
        "exclusive DMA B payload changed under backpressure");
  dut.io_dma_B_BREADY = 1;
  tick(dut);
  dut.io_dma_B_BREADY = 0;
  dut.io_dmaAWLock = 0;
}

static void test_dma_write_burst_id_restore_and_flush(DUT &dut) {
  dut.io_memory_AW_AWREADY = 1;
  dut.io_memory_W_WREADY = 1;

  // AXI permits W to arrive before AW.  The ingress guard locks DMA as owner
  // but deliberately backpressures W until AW has been classified.
  dut.io_dma_W_WDATA = 0x11112222U;
  dut.io_dma_W_WSTRB = 0xf;
  dut.io_dma_W_WLAST = 0;
  dut.io_dma_W_WVALID = 1;
  tick(dut); // choose DMA owner
  dut.eval();
  check(!dut.io_memory_W_WVALID && !dut.io_dma_W_WREADY &&
            !dut.io_memory_AW_AWVALID,
        "DMA W escaped before its AW address was classified");

  dut.io_dma_AW_AWID = 0xb;
  dut.io_dma_AW_AWADDR = 0xc0003000U;
  dut.io_dma_AW_AWLEN = 1;
  dut.io_dma_AW_AWSIZE = 2;
  dut.io_dma_AW_AWBURST = 1;
  dut.io_dma_AW_AWVALID = 1;
  dut.eval();
  check(dut.io_memory_AW_AWVALID && dut.io_memory_AW_AWID == 2 &&
            dut.io_memory_AW_AWADDR == 0xc0003000U &&
            dut.io_memory_AW_AWLEN == 1,
        "DMA AW was not translated to reserved BID 2");
  check(!dut.io_memory_W_WVALID && !dut.io_dma_W_WREADY,
        "DMA W was accepted in the same cycle as an unclassified AW");
  tick(dut); // accept AW
  dut.io_dma_AW_AWVALID = 0;

  dut.eval();
  check(dut.io_memory_W_WVALID && dut.io_dma_W_WREADY &&
            !dut.io_memory_W_WLAST &&
            dut.io_memory_W_WDATA == 0x11112222U,
        "held W-before-AW beat was not forwarded after AW acceptance");
  tick(dut); // first W beat
  dut.io_dma_W_WDATA = 0x33334444U;
  dut.io_dma_W_WLAST = 1;
  dut.eval();
  check(dut.io_memory_W_WVALID && dut.io_memory_W_WLAST,
        "DMA final W beat/WLAST was not forwarded");
  tick(dut);
  dut.io_dma_W_WVALID = 0;
  dut.io_dma_W_WLAST = 0;

  dut.io_memory_B_BID = 2;
  dut.io_memory_B_BRESP = 0;
  dut.io_memory_B_BVALID = 1;
  dut.eval();
  check(dut.io_memory_B_BREADY && dut.io_DMAWriteInvalidate,
        "DMA B was not captured/invalidation was not raised before upstream ready");
  tick(dut);
  dut.io_memory_B_BVALID = 0;
  dut.eval();
  check(dut.io_dma_B_BVALID && dut.io_dma_B_BID == 0xb,
        "captured DMA B response did not restore its original BID");
  check(dut.io_dma_B_BRESP == 0,
        "captured DMA B response changed BRESP");
  check(!dut.io_DMAWriteInvalidate,
        "DMA B backpressure repeated the one-shot DCache invalidation");

  tick(dut);
  check(dut.io_dma_B_BVALID && dut.io_dma_B_BID == 0xb,
        "DMA B response changed under backpressure");

  dut.io_dma_B_BREADY = 1;
  dut.eval();
  check(!dut.io_DMAWriteInvalidate,
        "DMA B handshake repeated an already delivered invalidation pulse");
  tick(dut);
  dut.io_dma_B_BREADY = 0;
  dut.eval();
  check(!dut.io_DMAWriteInvalidate,
        "DMA DCache invalidation remained set after the B transaction ended");

  // An errored write response is returned to DMA and must also invalidate:
  // AXI errors do not promise that the slave made no partial modification.
  dut.io_dma_AW_AWID = 4;
  dut.io_dma_AW_AWADDR = 0xc0004000U;
  dut.io_dma_AW_AWLEN = 0;
  dut.io_dma_AW_AWVALID = 1;
  dut.io_dma_W_WDATA = 0x55556666U;
  dut.io_dma_W_WLAST = 1;
  dut.io_dma_W_WVALID = 1;
  tick(dut); // choose owner
  dut.eval();
  check(dut.io_dma_AW_AWREADY && !dut.io_dma_W_WREADY,
        "DMA error-response test did not classify AW before W");
  tick(dut); // accept AW
  dut.io_dma_AW_AWVALID = 0;
  dut.eval();
  check(dut.io_dma_W_WREADY && dut.io_memory_W_WVALID,
        "DMA error-response test never forwarded W after AW");
  tick(dut); // accept WLAST
  dut.io_dma_W_WVALID = 0;
  dut.io_memory_B_BID = 2;
  dut.io_memory_B_BRESP = 2;
  dut.io_memory_B_BVALID = 1;
  dut.io_dma_B_BREADY = 0;
  dut.eval();
  check(dut.io_memory_B_BREADY && dut.io_DMAWriteInvalidate,
        "DMA error B was not captured with conservative invalidation");
  tick(dut);
  dut.io_memory_B_BVALID = 0;
  dut.io_memory_B_BRESP = 0;
  dut.eval();
  check(dut.io_dma_B_BVALID && dut.io_dma_B_BID == 4 &&
            dut.io_dma_B_BRESP == 2,
        "buffered DMA error B lost BID/BRESP");
  dut.io_dma_B_BREADY = 1;
  tick(dut);
  dut.io_dma_B_BREADY = 0;
}

static void test_dma_b_backpressure_does_not_block_lsu(DUT &dut) {
  dut.io_memory_AW_AWREADY = 1;
  dut.io_memory_W_WREADY = 1;
  dut.io_dma_AW_AWID = 6;
  dut.io_dma_AW_AWADDR = 0xc0030000U;
  dut.io_dma_AW_AWLEN = 0;
  dut.io_dma_AW_AWVALID = 1;
  dut.io_dma_W_WDATA = 0x66006600U;
  dut.io_dma_W_WLAST = 1;
  dut.io_dma_W_WVALID = 1;
  tick(dut); // select DMA
  dut.eval();
  check(dut.io_dma_AW_AWREADY && !dut.io_dma_W_WREADY,
        "DMA B-isolation setup did not classify AW first");
  tick(dut);
  dut.io_dma_AW_AWVALID = 0;
  dut.eval();
  check(dut.io_dma_W_WREADY && dut.io_memory_W_WVALID,
        "DMA B-isolation setup did not forward WLAST");
  tick(dut);
  dut.io_dma_W_WVALID = 0;

  dut.io_dma_B_BREADY = 0;
  dut.io_memory_B_BID = 2;
  dut.io_memory_B_BRESP = 0;
  dut.io_memory_B_BVALID = 1;
  dut.eval();
  check(dut.io_memory_B_BREADY && dut.io_DMAWriteInvalidate,
        "DMA B was not captured while upstream was stalled");
  tick(dut);
  dut.io_memory_B_BVALID = 0;
  dut.eval();
  check(dut.io_dma_B_BVALID && dut.io_dma_B_BID == 6,
        "stalled DMA B was not retained in its owner buffer");

  // A complete LSU write must proceed through AW/W/B while DMA B remains held.
  dut.io_lsu_AW_AWADDR = 0x8000a000U;
  dut.io_lsu_AW_AWVALID = 1;
  dut.io_lsu_W_WDATA = 0xa5a5a5a5U;
  dut.io_lsu_W_WLAST = 1;
  dut.io_lsu_W_WVALID = 1;
  tick(dut); // idle -> select LSU
  dut.eval();
  check(dut.io_memory_AW_AWVALID && dut.io_memory_AW_AWID == 0 &&
            dut.io_memory_W_WVALID && dut.io_memory_W_WLAST,
        "stalled DMA B prevented LSU AW/W from issuing");
  tick(dut);
  dut.io_lsu_AW_AWVALID = 0;
  dut.io_lsu_W_WVALID = 0;
  dut.io_memory_B_BID = 0;
  dut.io_memory_B_BRESP = 0;
  dut.io_memory_B_BVALID = 1;
  dut.eval();
  check(dut.io_lsu_B_BVALID && dut.io_memory_B_BREADY,
        "stalled DMA B prevented LSU B completion");
  check(dut.io_dma_B_BVALID && dut.io_dma_B_BID == 6,
        "LSU write disturbed the buffered DMA B payload");
  tick(dut);
  dut.io_memory_B_BVALID = 0;

  dut.eval();
  check(dut.io_dma_B_BVALID && dut.io_dma_B_BID == 6,
        "DMA B disappeared before its own upstream handshake");
  dut.io_dma_B_BREADY = 1;
  tick(dut);
  dut.io_dma_B_BREADY = 0;
}

static void test_three_way_read_arbitration(DUT &dut) {
  dut.io_memory_AR_ARREADY = 1;
  dut.io_ifu_AR_ARADDR = 0xc0005000U;
  dut.io_lsu_AR_ARADDR = 0xc0006000U;
  dut.io_dma_AR_ARID = 9;
  dut.io_dma_AR_ARADDR = 0xc0007000U;
  dut.io_ifu_AR_ARVALID = 1;
  dut.io_lsu_AR_ARVALID = 1;
  dut.io_dma_AR_ARVALID = 1;

  bool seen[3] = {false, false, false};
  for (int request = 0; request < 3; ++request) {
    dut.eval();
    check(dut.io_memory_AR_ARVALID,
          "three-way arbiter left an avoidable AR bubble");
    const unsigned id = dut.io_memory_AR_ARID;
    check(id < 3 && !seen[id],
          "three-way arbiter repeated or invented a read owner");
    seen[id] = true;
    tick(dut);
  }
  dut.io_ifu_AR_ARVALID = 0;
  dut.io_lsu_AR_ARVALID = 0;
  dut.io_dma_AR_ARVALID = 0;
  check(seen[0] && seen[1] && seen[2],
        "IFU, LSU, and DMA did not each obtain one read grant");

  // Responses may return in any ID order.
  finish_dma_read(dut, 9, 0x70007000U);
  finish_read(dut, 1, 0x60006000U, true);
  finish_read(dut, 0, 0x50005000U, false);
}

static void test_unexpected_read_id_is_not_consumed(DUT &dut) {
  dut.io_memory_R_RVALID = 1;
  dut.io_memory_R_RLAST = 1;
  dut.io_memory_R_RID = 2; // reserved DMA ID, but no DMA read is outstanding
  dut.eval();
  check(!dut.io_memory_R_RREADY && !dut.io_dma_R_RVALID,
        "inactive DMA RID was consumed or forwarded with a stale upstream ID");
  dut.io_memory_R_RID = 7;
  dut.eval();
  check(!dut.io_memory_R_RREADY,
        "unknown downstream RID was consumed by the read skid buffer");
  dut.io_memory_R_RVALID = 0;
  dut.io_memory_R_RLAST = 0;
  dut.io_memory_R_RID = 0;
}

static void test_round_robin(DUT &dut) {
  dut.io_memory_AR_ARREADY = 1;
  dut.io_ifu_AR_ARADDR = 0x80001000U;
  dut.io_lsu_AR_ARADDR = 0x80002000U;
  dut.io_ifu_AR_ARVALID = 1;
  dut.io_lsu_AR_ARVALID = 1;

  dut.eval();
  check(dut.io_memory_AR_ARVALID,
        "arbiter did not issue the first simultaneous read");
  const uint32_t first = dut.io_memory_AR_ARADDR;
  const uint8_t first_id = dut.io_memory_AR_ARID;
  tick(dut); // first AR is outstanding; no response yet

  dut.eval();
  check(dut.io_memory_AR_ARVALID,
        "arbiter did not allow the second read to become outstanding");
  const uint32_t second = dut.io_memory_AR_ARADDR;
  const uint8_t second_id = dut.io_memory_AR_ARID;
  check(first != second,
        "round-robin granted the same master twice while both requested");
  check((first == 0x80001000U && second == 0x80002000U) ||
            (first == 0x80002000U && second == 0x80001000U),
        "round-robin forwarded an unexpected address");
  check(first_id != second_id && first_id <= 1 && second_id <= 1,
        "arbiter did not assign distinct IFU/LSU read IDs");
  tick(dut); // second AR accepted before either response
  dut.io_ifu_AR_ARVALID = 0;
  dut.io_lsu_AR_ARVALID = 0;

  // Return responses in reverse order and without a bubble.  The second beat
  // must enter the skid buffer in the same cycle the first is consumed, with
  // ownership switching solely from RID.
  dut.io_memory_R_RID = second_id;
  dut.io_memory_R_RDATA = 0xbbbb0002U;
  dut.io_memory_R_RLAST = 1;
  dut.io_memory_R_RVALID = 1;
  tick(dut);
  dut.eval();
  check(static_cast<bool>(dut.io_lsu_R_RVALID) == (second_id == 1) &&
            static_cast<bool>(dut.io_ifu_R_RVALID) == (second_id == 0),
        "first interleaved RID was routed to the wrong master");

  dut.io_memory_R_RID = first_id;
  dut.io_memory_R_RDATA = 0xaaaa0001U;
  tick(dut); // consume previous skid entry and capture this response
  dut.eval();
  check(static_cast<bool>(dut.io_lsu_R_RVALID) == (first_id == 1) &&
            static_cast<bool>(dut.io_ifu_R_RVALID) == (first_id == 0),
        "same-cycle skid pop/push lost the new response owner");
  check((first_id == 1 ? dut.io_lsu_R_RDATA : dut.io_ifu_R_RDATA) ==
            0xaaaa0001U,
        "same-cycle skid pop/push corrupted response data");
  dut.io_memory_R_RVALID = 0;
  dut.io_memory_R_RLAST = 0;
  tick(dut);
}

static void test_stalled_ar_grant_is_stable(DUT &dut) {
  dut.io_memory_AR_ARREADY = 0;
  dut.io_lsu_AR_ARADDR = 0x80006000U;
  dut.io_lsu_AR_ARVALID = 1;
  dut.io_ifu_AR_ARVALID = 0;
  dut.eval();
  check(dut.io_memory_AR_ARVALID && dut.io_memory_AR_ARID == 1,
        "LSU stalled AR was not selected");
  const auto held_addr = dut.io_memory_AR_ARADDR;
  tick(dut);

  // A lower-ID IFU request arriving later must not steal the stalled channel.
  dut.io_ifu_AR_ARADDR = 0x80007000U;
  dut.io_ifu_AR_ARVALID = 1;
  for (int cycle = 0; cycle < 3; ++cycle) {
    dut.eval();
    check(dut.io_memory_AR_ARVALID && dut.io_memory_AR_ARID == 1 &&
              dut.io_memory_AR_ARADDR == held_addr,
          "arbiter changed AR payload while ARREADY was low");
    tick(dut);
  }
  dut.io_memory_AR_ARREADY = 1;
  tick(dut);
  dut.io_lsu_AR_ARVALID = 0;
  dut.eval();
  check(dut.io_memory_AR_ARVALID && dut.io_memory_AR_ARID == 0 &&
            dut.io_memory_AR_ARADDR == 0x80007000U,
        "IFU request did not issue after stalled LSU AR handshook");
  tick(dut);
  dut.io_ifu_AR_ARVALID = 0;

  // Release both outstanding IDs before the next test.
  finish_read(dut, 1, 0x60006000U, true);
  finish_read(dut, 0, 0x70007000U, false);
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  return run_test("AXI arbiter concurrent reads/writes and ID routing", [] {
    DUT dut;
    defaults(dut);
    reset(dut);
    test_multibeat_write(dut);
    test_stalled_ar_grant_is_stable(dut);
    test_round_robin(dut);
    test_dma_read_burst_and_id_restore(dut);
    test_zero_latency_dma_read(dut);
    test_dma_r_backpressure_does_not_block_cpu(dut);
    test_dma_write_burst_id_restore_and_flush(dut);
    test_dma_b_backpressure_does_not_block_lsu(dut);
    test_dma_exclusive_is_rejected_locally(dut);
    test_three_way_read_arbitration(dut);
    test_unexpected_read_id_is_not_consumed(dut);
    dut.final();
  });
}
