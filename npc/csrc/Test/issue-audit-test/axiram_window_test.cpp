// 审计：AXIRAM 的窗口检查只看突发首拍，跨窗口突发会按截断索引回绕。
//
// 疑点（来自静态审计）：IsRAMBeat(arAddr/awAddr, size) 只验证首字节区间，
// 之后每拍用 ((addr-RamBase)>>2)(15,0) 递增并截断到 16 位。因此从窗口末尾
// （0x8003FFFC）发起的多拍突发，其越界拍会回绕到 0x80000000 附近读写，
// 并且 RRESP/BRESP 仍按首拍判定为 OKAY。
//
// 预期正确行为：突发中任何一拍的地址越出 RAM 窗口（0x80000000..0x8003FFFF）
// 都必须以 DECERR 终止，而不是回绕访问。
//
// 注：整机路径上 Xbar 会按整笔突发做 PMA 末端检查，因此该缺陷在当前集成
// 中不可达；但 AXIRAM 是独立导出的顶层（Elaborate 会单独生成 SV），本测试
// 按模块级契约判定。

#include "Vriscv32e_npc_AXIRAM.h"
#include "test_common.hpp"

#include <cstdint>
#include <string>
#include <vector>
#include <verilated.h>

using DUT = Vriscv32e_npc_AXIRAM;

static constexpr uint32_t kRamBase = 0x80000000U;
static constexpr uint32_t kRamLastWord = 0x8003FFFCU;

static void defaults(DUT &dut) {
  dut.io_axi_AW_AWVALID = 0;
  dut.io_axi_AW_AWADDR = 0;
  dut.io_axi_AW_AWID = 0;
  dut.io_axi_AW_AWLEN = 0;
  dut.io_axi_AW_AWSIZE = 2;
  dut.io_axi_AW_AWBURST = 1;
  dut.io_axi_AW_AWPROT = 0;
  dut.io_axi_W_WVALID = 0;
  dut.io_axi_W_WDATA = 0;
  dut.io_axi_W_WSTRB = 0xF;
  dut.io_axi_W_WLAST = 0;
  dut.io_axi_B_BREADY = 1;
  dut.io_axi_AR_ARVALID = 0;
  dut.io_axi_AR_ARADDR = 0;
  dut.io_axi_AR_ARID = 0;
  dut.io_axi_AR_ARLEN = 0;
  dut.io_axi_AR_ARSIZE = 2;
  dut.io_axi_AR_ARBURST = 1;
  dut.io_axi_AR_ARPROT = 0;
  dut.io_axi_R_RREADY = 1;
  dut.io_hostWriteValid = 0;
  dut.io_hostWriteIndex = 0;
  dut.io_hostWriteData = 0;
}

static void host_write(DUT &dut, uint16_t word_index, uint32_t data) {
  dut.io_hostWriteValid = 1;
  dut.io_hostWriteIndex = word_index;
  dut.io_hostWriteData = data;
  tick(dut);
  dut.io_hostWriteValid = 0;
  dut.eval();
}

struct Beat {
  uint32_t data = 0;
  uint8_t resp = 0;
  bool last = false;
};

static std::vector<Beat> read_burst(DUT &dut, uint32_t address, uint8_t len) {
  dut.io_axi_AR_ARADDR = address;
  dut.io_axi_AR_ARLEN = len;
  dut.io_axi_AR_ARSIZE = 2;
  dut.io_axi_AR_ARBURST = 1; // INCR
  dut.io_axi_AR_ARVALID = 1;
  dut.eval();
  check(dut.io_axi_AR_ARREADY, "AXIRAM did not accept AR");
  tick(dut);
  dut.io_axi_AR_ARVALID = 0;

  std::vector<Beat> beats;
  for (int cycle = 0; cycle < 64 && beats.size() < len + 1U; ++cycle) {
    dut.eval();
    if (dut.io_axi_R_RVALID) {
      Beat beat;
      beat.data = dut.io_axi_R_RDATA;
      beat.resp = dut.io_axi_R_RRESP;
      beat.last = dut.io_axi_R_RLAST;
      beats.push_back(beat);
    }
    tick(dut);
  }
  dut.eval();
  return beats;
}

static uint8_t write_burst(DUT &dut, uint32_t address, uint8_t len,
                           const std::vector<uint32_t> &words) {
  dut.io_axi_AW_AWADDR = address;
  dut.io_axi_AW_AWLEN = len;
  dut.io_axi_AW_AWSIZE = 2;
  dut.io_axi_AW_AWBURST = 1;
  dut.io_axi_AW_AWVALID = 1;
  dut.eval();
  wait_until(dut, [&] { return dut.io_axi_AW_AWREADY; },
             "AXIRAM did not accept AW");
  tick(dut);
  dut.io_axi_AW_AWVALID = 0;

  // AXIRAM 每次经过 sIdle 只收一拍 W；每拍都必须等 WREADY。
  for (size_t beat = 0; beat < words.size(); ++beat) {
    dut.io_axi_W_WDATA = words[beat];
    dut.io_axi_W_WSTRB = 0xF;
    dut.io_axi_W_WLAST = (beat + 1 == words.size());
    dut.io_axi_W_WVALID = 1;
    dut.eval();
    wait_until(dut, [&] { return dut.io_axi_W_WREADY; },
               "AXIRAM did not accept W beat " + std::to_string(beat));
    tick(dut);
  }
  dut.io_axi_W_WVALID = 0;
  dut.io_axi_W_WLAST = 0;

  uint8_t resp = 0;
  for (int cycle = 0; cycle < 32; ++cycle) {
    dut.eval();
    if (dut.io_axi_B_BVALID) {
      resp = dut.io_axi_B_BRESP;
      break;
    }
    tick(dut);
  }
  tick(dut);
  dut.eval();
  return resp;
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  return run_test("AXIRAM rejects bursts crossing the window end", [] {
    DUT dut;
    defaults(dut);
    reset(dut);

    // 哨兵：窗口首字与末字
    constexpr uint32_t kFirstWord = 0xAAAA0000U;
    constexpr uint32_t kLastWord = 0xBBBBFFFFU;
    host_write(dut, 0x0000, kFirstWord);
    host_write(dut, 0xFFFF, kLastWord);

    // --- 读：从末字发起 4 拍突发（末字 + 3 拍越界）---
    auto beats = read_burst(dut, kRamLastWord, 3);
    check(beats.size() == 4, "read burst did not return four beats");
    check(beats[0].resp == 0 && beats[0].data == kLastWord,
          "first beat of the in-window burst is wrong");
    check(beats[3].last, "last read beat did not assert RLAST");
    for (size_t index = 1; index < beats.size(); ++index) {
      if (beats[index].resp == 0) {
        throw std::runtime_error(
            "read beat " + std::to_string(index) +
            " is outside the RAM window but returned OKAY with data 0x" +
            std::to_string(beats[index].data) +
            " (wrapped access instead of DECERR)");
      }
    }

    // --- 写：从末字发起 4 拍突发，检查是否回绕污染窗口首字 ---
    const std::vector<uint32_t> words = {0x11111111U, 0x22222222U, 0x33333333U,
                                         0x44444444U};
    const uint8_t bresp = write_burst(dut, kRamLastWord, 3, words);
    check(bresp != 0,
          "write burst crossing the window end returned OKAY (BRESP=" +
              std::to_string(bresp) + ")");

    // 回读窗口首字与末字：越界拍不得写进 RAM。
    auto first = read_burst(dut, kRamBase, 0);
    check(first.size() == 1, "first-word readback failed");
    check(first[0].data == kFirstWord,
          "wrapped write corrupted the first RAM word: got 0x" +
              std::to_string(first[0].data));
    auto last = read_burst(dut, kRamLastWord, 0);
    check(last.size() == 1, "last-word readback failed");
    check(last[0].data == words[0],
          "in-window beat of the write burst was not applied");
    dut.final();
  });
}
