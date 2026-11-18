// 设计审计：Xbar 的响应缓冲由单拍改为 4 拍小 FIFO 后，跨源队头阻塞消除。
//
// 背景：此前 Xbar 只有一个 R 响应缓冲，某个源（例如入站 DMA，RID=2）的
// 响应被消费者反压（io_in_R_RREADY=0）时会独占通道，其他源（IFU/LSU）
// 的响应无法被接受。现改为小 FIFO（深度与仲裁器 CPU 侧队列一致）：
// 被反压的响应不再阻塞无关 owner。
//
// 本测试确认：先入队 RID=2 并反压消费者，RID=0 的响应仍必须被接受
// （RREADY=1）；释放消费者后按到达顺序排空且数据无损。

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
  return run_test(
      "xbar R FIFO absorbs an unrelated response while one is back-pressured",
      [] {
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
              "xbar did not accept the DMA response into its FIFO");
        tick(dut);
        dut.io_SoCBus_R_RVALID = 0;
        dut.io_SoCBus_R_RLAST = 0;
        dut.eval();
        check(dut.io_in_R_RVALID && dut.io_in_R_RID == 2,
              "buffered DMA response is not visible to the consumer");

        // 再喂 IFU 的响应：FIFO 有余量，必须被接受；旧单拍缓冲会在此阻塞。
        dut.io_SoCBus_R_RID = 0;
        dut.io_SoCBus_R_RDATA = 0x00000011U;
        dut.io_SoCBus_R_RLAST = 1;
        dut.io_SoCBus_R_RVALID = 1;
        dut.eval();
        check(dut.io_SoCBus_R_RREADY,
              "xbar did not absorb the unrelated IFU response behind a "
              "back-pressured one (head-of-line blocking still present)");
        tick(dut);
        dut.io_SoCBus_R_RVALID = 0;
        dut.io_SoCBus_R_RLAST = 0;
        dut.eval();

        // 释放消费者：按到达顺序排空（先 DMA 后 IFU），数据无损。
        dut.io_in_R_RREADY = 1;
        dut.eval();
        check(dut.io_in_R_RVALID && dut.io_in_R_RID == 2 &&
                  dut.io_in_R_RDATA == 0x22222222U,
              "first drained response was not the DMA beat");
        tick(dut);
        dut.eval();
        check(dut.io_in_R_RVALID && dut.io_in_R_RID == 0 &&
                  dut.io_in_R_RDATA == 0x00000011U,
              "second drained response was not the intact IFU beat");
        tick(dut);
        dut.eval();
        check(!dut.io_in_R_RVALID, "R FIFO did not drain empty");
        dut.final();
      });
}
