// 设计审计：Xbar 的单一 R 缓冲造成跨源队头阻塞。
//
// 机制：Xbar 只有一个 R 响应缓冲（RBuffer*）。当某个源（例如入站 DMA，
// RID=2）的响应被其消费者反压（io_in_R_RREADY=0）时，缓冲被占住，
// 其他源（IFU/LSU）的响应也无法被接受——下游从设备必须一直保持
// RVALID，否则就是协议违例。
//
// 本测试确认该行为存在：先缓冲 RID=2 的响应并反压消费者，再喂 RID=0
// 的响应，检查 io_SoCBus_R_RREADY 是否为 0（无法接受）；释放消费者后
// 两个响应都能正常排空（无数据损坏）。

#include "Vysyx_26030103_AXI5Xbar.h"
#include "test_common.hpp"

#include <cstdint>
#include <verilated.h>

using DUT = Vysyx_26030103_AXI5Xbar;

static void defaults(DUT &dut) {
  dut.io_in_AW_AWVALID = 0;
  dut.io_in_AW_AWADDR = 0;
  dut.io_in_AW_AWSIZE = 2;
  dut.io_in_AW_AWBURST = 1;
  dut.io_in_W_WVALID = 0;
  dut.io_in_W_WDATA = 0;
  dut.io_in_W_WSTRB = 0xF;
  dut.io_in_W_WLAST = 0;
  dut.io_in_B_BREADY = 1;
  dut.io_in_AR_ARVALID = 0;
  dut.io_in_AR_ARADDR = 0;
  dut.io_in_AR_ARID = 0;
  dut.io_in_AR_ARLEN = 0;
  dut.io_in_AR_ARSIZE = 2;
  dut.io_in_AR_ARBURST = 1;
  dut.io_in_R_RREADY = 1;
  dut.io_SoCBus_AW_AWREADY = 0;
  dut.io_SoCBus_W_WREADY = 0;
  dut.io_SoCBus_B_BVALID = 0;
  dut.io_SoCBus_B_BRESP = 0;
  dut.io_SoCBus_B_BID = 0;
  dut.io_SoCBus_AR_ARREADY = 0;
  dut.io_SoCBus_R_RVALID = 0;
  dut.io_SoCBus_R_RDATA = 0;
  dut.io_SoCBus_R_RRESP = 0;
  dut.io_SoCBus_R_RLAST = 0;
  dut.io_SoCBus_R_RID = 0;
  dut.io_CLINT_AW_AWREADY = 0;
  dut.io_CLINT_W_WREADY = 0;
  dut.io_CLINT_B_BVALID = 0;
  dut.io_CLINT_B_BRESP = 0;
  dut.io_CLINT_B_BID = 0;
  dut.io_CLINT_AR_ARREADY = 0;
  dut.io_CLINT_R_RVALID = 0;
  dut.io_CLINT_R_RDATA = 0;
  dut.io_CLINT_R_RRESP = 0;
  dut.io_CLINT_R_RLAST = 0;
  dut.io_CLINT_R_RID = 0;
}

static void issue_soc_read(DUT &dut, uint8_t id, uint32_t address) {
  dut.io_SoCBus_AR_ARREADY = 1;
  dut.io_in_AR_ARID = id;
  dut.io_in_AR_ARADDR = address;
  dut.io_in_AR_ARLEN = 0;
  dut.io_in_AR_ARVALID = 1;
  dut.eval();
  check(dut.io_in_AR_ARREADY, "xbar rejected a SoC read request");
  tick(dut); // 输入 AR 握手，槽位进入 Request
  dut.io_in_AR_ARVALID = 0;

  // 等下游 AR 真正握手：槽位进入 Response 后，该 RID 的响应才会被接受。
  for (int cycle = 0; cycle < 16; ++cycle) {
    dut.eval();
    if (dut.io_SoCBus_AR_ARVALID && dut.io_SoCBus_AR_ARREADY) {
      tick(dut);
      break;
    }
    tick(dut);
  }
  dut.eval();
  check(!dut.io_SoCBus_AR_ARVALID,
        "xbar kept ARVALID after the downstream handshake");
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  return run_test("xbar single R buffer blocks unrelated responses", [] {
    DUT dut;
    defaults(dut);
    reset(dut);

    // 两笔到 PSRAM 的读：RID=2（入站 DMA）与 RID=0（IFU）。
    issue_soc_read(dut, 2, 0x80001000U);
    issue_soc_read(dut, 0, 0x80002000U);

    // 先返回 RID=2 的响应，消费者保持反压（DMA 侧不取）。
    dut.io_in_R_RREADY = 0;
    dut.io_SoCBus_R_RID = 2;
    dut.io_SoCBus_R_RDATA = 0x22222222U;
    dut.io_SoCBus_R_RLAST = 1;
    dut.io_SoCBus_R_RVALID = 1;
    dut.eval();
    check(dut.io_SoCBus_R_RREADY,
          "xbar did not accept the DMA response into its buffer");
    tick(dut);
    dut.io_SoCBus_R_RVALID = 0;
    dut.io_SoCBus_R_RLAST = 0;
    dut.eval();
    check(dut.io_in_R_RVALID && dut.io_in_R_RID == 2,
          "buffered DMA response is not visible to the consumer");

    // 现在喂 IFU 的响应：单一缓冲被 DMA 占住，必须无法接受。
    dut.io_SoCBus_R_RID = 0;
    dut.io_SoCBus_R_RDATA = 0x00000000U;
    dut.io_SoCBus_R_RLAST = 1;
    dut.io_SoCBus_R_RVALID = 1;
    dut.eval();
    check(!dut.io_SoCBus_R_RREADY,
          "xbar accepted an unrelated IFU response while the DMA response "
          "was still buffered (head-of-line blocking absent)");

    // 释放消费者：DMA 响应被消费；缓冲空出后 IFU 响应才被接受
    // （Xbar 的缓冲是弹性的：pop 与 push 可以同拍发生）。
    dut.io_in_R_RREADY = 1;
    tick(dut);
    dut.eval();
    check(!dut.io_in_R_RVALID || dut.io_in_R_RID == 0,
          "DMA response was not drained before the IFU response");

    if (!dut.io_in_R_RVALID) {
      check(dut.io_SoCBus_R_RREADY,
            "xbar did not accept the IFU response after the buffer drained");
      tick(dut);
      dut.eval();
    }
    // IFU 响应已被捕获：从设备撤销 RVALID（握手完成）。
    dut.io_SoCBus_R_RVALID = 0;
    dut.io_SoCBus_R_RLAST = 0;
    dut.eval();
    check(dut.io_in_R_RVALID && dut.io_in_R_RID == 0 &&
              dut.io_in_R_RDATA == 0x00000000U,
          "IFU response was lost or corrupted after the HOL block");
    dut.final();
  });
}
