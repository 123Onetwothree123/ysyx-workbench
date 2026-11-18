#include "Vysyx_26030103_AXI5Xbar.h"
#include "test_common.hpp"

#include <cstdint>
#include <string>
#include <verilated.h>

using DUT = Vysyx_26030103_AXI5Xbar;

static void defaults(DUT &dut) {
  dut.io_in_AW_AWADDR = 0;
  dut.io_in_AW_AWSIZE = 2;
  dut.io_in_AW_AWBURST = 1;
  dut.io_in_AW_AWVALID = 0;
  dut.io_in_W_WDATA = 0;
  dut.io_in_W_WSTRB = 0xf;
  dut.io_in_W_WLAST = 0;
  dut.io_in_W_WVALID = 0;
  dut.io_in_B_BREADY = 1;
  dut.io_in_AR_ARADDR = 0;
  dut.io_in_AR_ARID = 0;
  dut.io_in_AR_ARLEN = 0;
  dut.io_in_AR_ARSIZE = 2;
  dut.io_in_AR_ARBURST = 1;
  dut.io_in_AR_ARVALID = 0;
  dut.io_in_R_RREADY = 0;

  dut.io_SoCBus_AW_AWREADY = 1;
  dut.io_SoCBus_W_WREADY = 1;
  dut.io_SoCBus_B_BRESP = 0;
  dut.io_SoCBus_B_BVALID = 0;
  dut.io_SoCBus_AR_ARREADY = 0;
  dut.io_SoCBus_R_RDATA = 0;
  dut.io_SoCBus_R_RID = 0;
  dut.io_SoCBus_R_RRESP = 0;
  dut.io_SoCBus_R_RLAST = 0;
  dut.io_SoCBus_R_RVALID = 0;

  dut.io_CLINT_AW_AWREADY = 1;
  dut.io_CLINT_W_WREADY = 1;
  dut.io_CLINT_B_BRESP = 0;
  dut.io_CLINT_B_BVALID = 0;
  dut.io_CLINT_AR_ARREADY = 0;
  dut.io_CLINT_R_RDATA = 0;
  dut.io_CLINT_R_RID = 0;
  dut.io_CLINT_R_RRESP = 0;
  dut.io_CLINT_R_RLAST = 0;
  dut.io_CLINT_R_RVALID = 0;
}

static void accept_ar(DUT &dut, std::uint8_t id, std::uint32_t address,
                      std::uint8_t len) {
  dut.io_in_AR_ARID = id;
  dut.io_in_AR_ARADDR = address;
  dut.io_in_AR_ARLEN = len;
  dut.io_in_AR_ARVALID = 1;
  dut.eval();
  check(dut.io_in_AR_ARREADY,
        "Xbar did not accept a request into a free RID slot");
  tick(dut);
  dut.io_in_AR_ARVALID = 0;
}

static void check_local_decerr_baseline() {
  DUT dut;
  defaults(dut);
  reset(dut);
  dut.io_in_R_RREADY = 1;
  accept_ar(dut, 0, 0x20000000U, 3);

  unsigned beats = 0;
  bool saw_last = false;
  for (int cycle = 0; cycle < 12 && !saw_last; ++cycle) {
    dut.eval();
    if (dut.io_in_R_RVALID) {
      check(dut.io_in_R_RID == 0 && dut.io_in_R_RRESP == 3,
            "baseline local response metadata was corrupted");
      ++beats;
      if (dut.io_in_R_RLAST) {
        check(beats == 4,
              "baseline local DECERR returned the wrong burst length");
        saw_last = true;
      }
    }
    tick(dut);
  }
  check(saw_last, "baseline local DECERR burst did not terminate");
  dut.final();
}

static void check_local_decerr_interleave() {
  DUT dut;
  defaults(dut);
  reset(dut);

  // RID 0 是未映射的四拍突发，必须恰好返回 4 个本地 DECERR 拍，
  // 与另一 RID 的交错响应无关。R FIFO（深度 4）会把本地拍连续吸收，
  // 交错响应按入队顺序排在它们之后。
  accept_ar(dut, 0, 0x20000000U, 3);
  tick(dut); // 本地拍 0 入队
  dut.eval();
  check(dut.io_in_R_RVALID && dut.io_in_R_RID == 0 && dut.io_in_R_RRESP == 3 &&
            !dut.io_in_R_RLAST,
        "Xbar did not buffer local DECERR beat 0");

  // FIFO 有余量：本地拍 1..3 继续入队（不再像单缓冲那样停住）。
  for (int cycle = 0; cycle < 3; ++cycle) {
    tick(dut);
  }
  dut.eval();

  // 期间建立一笔合法的单拍读（RID 1）并发出到 SoC 总线。
  accept_ar(dut, 1, 0x80001000U, 0);
  dut.io_SoCBus_AR_ARREADY = 1;
  dut.eval();
  check(dut.io_SoCBus_AR_ARVALID && dut.io_SoCBus_AR_ARID == 1,
        "mapped RID 1 request was not issued while RID 0 held local DECERR");
  tick(dut);
  dut.io_SoCBus_AR_ARREADY = 0;

  // 释放消费者并喂入 SoC 响应：FIFO 满（4 拍本地）时弹出本地拍 0，
  // SoC 响应在同拍入队；顺序保持为 [本地1, 本地2, 本地3, SoC]。
  dut.io_in_R_RREADY = 1;
  dut.io_SoCBus_R_RID = 1;
  dut.io_SoCBus_R_RDATA = 0x11223344U;
  dut.io_SoCBus_R_RRESP = 0;
  dut.io_SoCBus_R_RLAST = 1;
  dut.io_SoCBus_R_RVALID = 1;
  dut.eval();
  check(dut.io_in_R_RVALID && dut.io_in_R_RID == 0 && dut.io_SoCBus_R_RREADY,
        "test did not create the local-pop/SoC-capture overlap");
  tick(dut);
  dut.io_SoCBus_R_RVALID = 0;
  dut.io_SoCBus_R_RLAST = 0;

  // 依次消费：本地拍 1..3（最后一拍带 RLAST），然后才是 SoC 拍。
  unsigned local_beats = 1; // 拍 0 已在上一个沿消费
  bool local_last = false;
  bool soc_seen = false;
  for (int cycle = 0; cycle < 16 && !soc_seen; ++cycle) {
    dut.eval();
    if (dut.io_in_R_RVALID && dut.io_in_R_RID == 0) {
      check(dut.io_in_R_RRESP == 3,
            "unexpected response appeared while draining local DECERR");
      ++local_beats;
      if (dut.io_in_R_RLAST) {
        check(local_beats == 4, "local DECERR asserted RLAST after " +
                                    std::to_string(local_beats) +
                                    " beats; ARLEN=3 requires exactly 4");
        local_last = true;
      }
    } else if (dut.io_in_R_RVALID && dut.io_in_R_RID == 1) {
      check(dut.io_in_R_RDATA == 0x11223344U && dut.io_in_R_RLAST,
            "interleaved SoC response was corrupted");
      check(local_last,
            "SoC response drained before the local DECERR burst finished");
      soc_seen = true;
    }
    tick(dut);
  }
  check(local_last, "local DECERR burst did not terminate within four beats");
  check(soc_seen, "interleaved SoC response never drained");
  dut.final();
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  int failures = 0;
  failures += run_test("Xbar local DECERR baseline length",
                       check_local_decerr_baseline);
  failures += run_test("Xbar local DECERR survives interleaved SoC response",
                       check_local_decerr_interleave);
  return failures == 0 ? 0 : 1;
}
