#include "Vriscv32e_npc_AXIRAM.h"
#include "test_common.hpp"
#include <verilated.h>
#include <vector>

using DUT = Vriscv32e_npc_AXIRAM;

static void defaults(DUT &dut) {
  dut.io_axi_AW_AWID = 0;
  dut.io_axi_AW_AWADDR = 0;
  dut.io_axi_AW_AWLEN = 0;
  dut.io_axi_AW_AWSIZE = 2;
  dut.io_axi_AW_AWBURST = 1;
  dut.io_axi_AW_AWPROT = 0;
  dut.io_axi_AW_AWVALID = 0;
  dut.io_axi_W_WDATA = 0;
  dut.io_axi_W_WSTRB = 0xf;
  dut.io_axi_W_WLAST = 1;
  dut.io_axi_W_WVALID = 0;
  dut.io_axi_B_BREADY = 0;
  dut.io_axi_AR_ARID = 0;
  dut.io_axi_AR_ARADDR = 0;
  dut.io_axi_AR_ARLEN = 0;
  dut.io_axi_AR_ARSIZE = 2;
  dut.io_axi_AR_ARBURST = 1;
  dut.io_axi_AR_ARPROT = 0;
  dut.io_axi_AR_ARVALID = 0;
  dut.io_axi_R_RREADY = 0;
}

static void send_aw(DUT &dut, uint32_t address, uint8_t id, uint8_t len,
                    uint8_t size, uint8_t burst) {
  dut.io_axi_AW_AWADDR = address;
  dut.io_axi_AW_AWID = id;
  dut.io_axi_AW_AWLEN = len;
  dut.io_axi_AW_AWSIZE = size;
  dut.io_axi_AW_AWBURST = burst;
  dut.io_axi_AW_AWVALID = 1;
  wait_until(dut, [&] { return dut.io_axi_AW_AWREADY; },
             "AXIRAM never accepted AW");
  tick(dut);
  dut.io_axi_AW_AWVALID = 0;
}

static void send_w(DUT &dut, uint32_t data, uint8_t strb, bool last) {
  dut.io_axi_W_WDATA = data;
  dut.io_axi_W_WSTRB = strb;
  dut.io_axi_W_WLAST = last;
  dut.io_axi_W_WVALID = 1;
  wait_until(dut, [&] { return dut.io_axi_W_WREADY; },
             "AXIRAM never accepted W");
  tick(dut);
  dut.io_axi_W_WVALID = 0;
}

static void expect_b(DUT &dut, uint8_t id, uint8_t resp = 0) {
  wait_until(dut, [&] { return dut.io_axi_B_BVALID; },
             "AXIRAM never produced B");
  check(dut.io_axi_B_BID == id, "AXIRAM returned the wrong BID");
  check(dut.io_axi_B_BRESP == resp, "AXIRAM returned the wrong BRESP");
  dut.io_axi_B_BREADY = 1;
  tick(dut);
  dut.io_axi_B_BREADY = 0;
}

static void write_single(DUT &dut, uint32_t address, uint32_t data,
                         uint8_t strb, uint8_t size, uint8_t id,
                         bool w_first = false, uint8_t expected_resp = 0) {
  if (w_first) {
    send_w(dut, data, strb, true);
    dut.eval();
    check(dut.io_axi_AW_AWREADY,
          "AXIRAM did not retain W while waiting for AW");
    send_aw(dut, address, id, 0, size, 1);
  } else {
    send_aw(dut, address, id, 0, size, 1);
    dut.eval();
    check(dut.io_axi_W_WREADY,
          "AXIRAM did not retain AW while waiting for W");
    send_w(dut, data, strb, true);
  }
  expect_b(dut, id, expected_resp);
}

static void send_ar(DUT &dut, uint32_t address, uint8_t id, uint8_t len,
                    uint8_t size, uint8_t burst) {
  dut.io_axi_AR_ARADDR = address;
  dut.io_axi_AR_ARID = id;
  dut.io_axi_AR_ARLEN = len;
  dut.io_axi_AR_ARSIZE = size;
  dut.io_axi_AR_ARBURST = burst;
  dut.io_axi_AR_ARVALID = 1;
  wait_until(dut, [&] { return dut.io_axi_AR_ARREADY; },
             "AXIRAM never accepted AR");
  tick(dut);
  dut.io_axi_AR_ARVALID = 0;
}

struct ReadBeat {
  uint32_t data;
  uint8_t id;
  uint8_t resp;
  bool last;
};

static ReadBeat receive_r(DUT &dut) {
  wait_until(dut, [&] { return dut.io_axi_R_RVALID; },
             "AXIRAM never produced R");
  const ReadBeat result{static_cast<uint32_t>(dut.io_axi_R_RDATA),
                        static_cast<uint8_t>(dut.io_axi_R_RID),
                        static_cast<uint8_t>(dut.io_axi_R_RRESP),
                        static_cast<bool>(dut.io_axi_R_RLAST)};
  dut.io_axi_R_RREADY = 1;
  tick(dut);
  dut.io_axi_R_RREADY = 0;
  return result;
}

static uint32_t read_word(DUT &dut, uint32_t address, uint8_t id) {
  send_ar(dut, address, id, 0, 2, 1);
  const ReadBeat result = receive_r(dut);
  check(result.id == id, "AXIRAM returned the wrong RID");
  check(result.resp == 0 && result.last,
        "AXIRAM returned an invalid single-beat read response");
  return result.data;
}

static std::vector<ReadBeat> read_burst(DUT &dut, uint32_t address,
                                        uint8_t id, uint8_t len,
                                        uint8_t size, uint8_t burst) {
  send_ar(dut, address, id, len, size, burst);
  std::vector<ReadBeat> result;
  for (unsigned beat = 0; beat <= len; ++beat) {
    result.push_back(receive_r(dut));
  }
  return result;
}

static void write_burst(DUT &dut, uint32_t address, uint8_t id,
                        uint8_t burst, const uint32_t (&words)[3],
                        const uint8_t (&strbs)[3]) {
  send_aw(dut, address, id, 2, 2, burst);
  send_w(dut, words[0], strbs[0], false);
  send_w(dut, words[1], strbs[1], false);
  send_w(dut, words[2], strbs[2], true);
  expect_b(dut, id);
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  return run_test("AXIRAM independent channels, masks, and bursts", [] {
    DUT dut;
    defaults(dut);
    reset(dut);

    write_single(dut, 0x80000000U, 0x11223344U, 0xf, 2, 1);
    write_single(dut, 0x80000001U, 0x0000aa00U, 0x2, 0, 2, true);
    check(read_word(dut, 0x80000000U, 9) == 0x1122aa44U,
          "SB corrupted bytes outside WSTRB");

    write_single(dut, 0x80000002U, 0xbeef0000U, 0xc, 1, 3);
    check(read_word(dut, 0x80000000U, 10) == 0xbeefaa44U,
          "SH corrupted bytes outside WSTRB");

    write_single(dut, 0x80000010U, 0xa0a1a2a3U, 0xf, 2, 4);
    write_single(dut, 0x80000014U, 0xb0b1b2b3U, 0xf, 2, 5);
    write_single(dut, 0x80000018U, 0xc0c1c2c3U, 0xf, 2, 6);
    const uint32_t incr[3] = {0x11112222U, 0x33334444U, 0x55556666U};
    const uint8_t incr_strb[3] = {0x3, 0xc, 0x5};
    write_burst(dut, 0x80000010U, 7, 1, incr, incr_strb);
    check(read_word(dut, 0x80000010U, 11) == 0xa0a12222U,
          "INCR beat 0 changed an unstrobed lane");
    check(read_word(dut, 0x80000014U, 12) == 0x3333b2b3U,
          "INCR beat 1 changed an unstrobed lane");
    check(read_word(dut, 0x80000018U, 13) == 0xc055c266U,
          "INCR beat 2 changed an unstrobed lane");

    const uint32_t fixed[3] = {0xaaaabbbbU, 0xccccddddU, 0xeeeeffffU};
    const uint8_t full_strb[3] = {0xf, 0xf, 0xf};
    write_burst(dut, 0x80000020U, 8, 0, fixed, full_strb);
    check(read_word(dut, 0x80000020U, 14) == fixed[2],
          "FIXED burst did not keep all beats on one address");

    write_single(dut, 0x80000040U, 0x01020304U, 0xf, 2, 1);
    write_single(dut, 0x80000044U, 0x11121314U, 0xf, 2, 2);
    write_single(dut, 0x80000048U, 0x21222324U, 0xf, 2, 3);

    send_ar(dut, 0x80000040U, 5, 2, 2, 1);
    wait_until(dut, [&] { return dut.io_axi_R_RVALID; },
               "AXIRAM never produced the first INCR read beat");
    const ReadBeat held{static_cast<uint32_t>(dut.io_axi_R_RDATA),
                        static_cast<uint8_t>(dut.io_axi_R_RID),
                        static_cast<uint8_t>(dut.io_axi_R_RRESP),
                        static_cast<bool>(dut.io_axi_R_RLAST)};
    tick(dut);
    tick(dut);
    check(dut.io_axi_R_RVALID && dut.io_axi_R_RDATA == held.data &&
              dut.io_axi_R_RID == held.id && dut.io_axi_R_RRESP == held.resp &&
              dut.io_axi_R_RLAST == held.last,
          "AXIRAM changed an R beat while backpressured");
    check(held.data == 0x01020304U && held.id == 5 && held.resp == 0 &&
              !held.last,
          "AXIRAM first INCR read beat was incorrect");
    dut.io_axi_R_RREADY = 1;
    tick(dut);
    dut.io_axi_R_RREADY = 0;
    const ReadBeat incr_read_1 = receive_r(dut);
    const ReadBeat incr_read_2 = receive_r(dut);
    check(incr_read_1.data == 0x11121314U && incr_read_1.id == 5 &&
              incr_read_1.resp == 0 && !incr_read_1.last,
          "AXIRAM second INCR read beat was incorrect");
    check(incr_read_2.data == 0x21222324U && incr_read_2.id == 5 &&
              incr_read_2.resp == 0 && incr_read_2.last,
          "AXIRAM final INCR read beat was incorrect");

    const auto fixed_read = read_burst(dut, 0x80000040U, 6, 2, 2, 0);
    check(fixed_read.size() == 3, "AXIRAM returned the wrong FIXED length");
    for (size_t beat = 0; beat < fixed_read.size(); ++beat) {
      check(fixed_read[beat].data == 0x01020304U &&
                fixed_read[beat].id == 6 && fixed_read[beat].resp == 0 &&
                fixed_read[beat].last == (beat == 2),
            "AXIRAM FIXED read beat was incorrect");
    }

    write_single(dut, 0x8003fffcU, 0xfeedc0deU, 0xf, 2, 7);
    const auto crossing = read_burst(dut, 0x8003fffcU, 8, 1, 2, 1);
    check(crossing[0].data == 0xfeedc0deU && crossing[0].resp == 0 &&
              !crossing[0].last,
          "AXIRAM rejected the final in-range RAM beat");
    check(crossing[1].data == 0 && crossing[1].resp == 3 &&
              crossing[1].last,
          "AXIRAM did not DECERR an INCR beat outside RAM");

    const uint32_t ram_zero_before = read_word(dut, 0x80000000U, 9);
    write_single(dut, 0x10000000U, '.', 0xf, 2, 10);
    check(read_word(dut, 0x80000000U, 11) == ram_zero_before,
          "UART output aliased and modified RAM word zero");
    write_single(dut, 0x80040000U, 0xdeadc0deU, 0xf, 2, 12, false, 3);
    write_single(dut, 0x00000000U, 0xbadc0de0U, 0xf, 2, 13, false, 3);
    check(read_word(dut, 0x80000000U, 14) == ram_zero_before,
          "an unmapped write wrapped into RAM word zero");

    const auto unmapped = read_burst(dut, 0x10000000U, 1, 1, 2, 0);
    check(unmapped[0].resp == 3 && !unmapped[0].last &&
              unmapped[1].resp == 3 && unmapped[1].last,
          "AXIRAM did not return a complete DECERR read burst");

    const auto bad_burst = read_burst(dut, 0x80000040U, 2, 1, 2, 2);
    check(bad_burst[0].resp == 2 && !bad_burst[0].last &&
              bad_burst[1].resp == 2 && bad_burst[1].last,
          "AXIRAM did not safely complete an unsupported read burst");
    const auto bad_size = read_burst(dut, 0x80000040U, 3, 1, 3, 1);
    check(bad_size[0].resp == 2 && !bad_size[0].last &&
              bad_size[1].resp == 2 && bad_size[1].last,
          "AXIRAM did not safely complete an oversized read burst");
    dut.final();
  });
}
