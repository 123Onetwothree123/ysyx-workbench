#include "Vysyx_26030103_AXI5CLINTSlave.h"
#include "test_common.hpp"
#include <verilated.h>

#include <vector>

using DUT = Vysyx_26030103_AXI5CLINTSlave;

static void defaults(DUT &dut) {
  dut.io_AW_AWVALID = 0;
  dut.io_W_WVALID = 0;
  dut.io_B_BREADY = 0;
  dut.io_AR_ARADDR = 0;
  dut.io_AR_ARLEN = 0;
  dut.io_AR_ARSIZE = 2;
  dut.io_AR_ARBURST = 1;
  dut.io_AR_ARVALID = 0;
  dut.io_R_RREADY = 0;
}

static void send_ar(DUT &dut, uint32_t address, uint8_t len, uint8_t size,
                    uint8_t burst) {
  dut.io_AR_ARADDR = address;
  dut.io_AR_ARLEN = len;
  dut.io_AR_ARSIZE = size;
  dut.io_AR_ARBURST = burst;
  dut.io_AR_ARVALID = 1;
  wait_until(dut, [&] { return dut.io_AR_ARREADY; },
             "CLINT never accepted AR");
  tick(dut);
  dut.io_AR_ARVALID = 0;
}

struct ReadBeat {
  uint32_t data;
  uint8_t resp;
  bool last;
};

static ReadBeat peek_r(DUT &dut) {
  wait_until(dut, [&] { return dut.io_R_RVALID; },
             "CLINT never produced R");
  return {static_cast<uint32_t>(dut.io_R_RDATA),
          static_cast<uint8_t>(dut.io_R_RRESP),
          static_cast<bool>(dut.io_R_RLAST)};
}

static ReadBeat receive_r(DUT &dut) {
  const ReadBeat result = peek_r(dut);
  dut.io_R_RREADY = 1;
  tick(dut);
  dut.io_R_RREADY = 0;
  return result;
}

static std::vector<ReadBeat> read_burst(DUT &dut, uint32_t address,
                                        uint8_t len, uint8_t size,
                                        uint8_t burst) {
  send_ar(dut, address, len, size, burst);
  std::vector<ReadBeat> result;
  for (unsigned beat = 0; beat <= len; ++beat) {
    result.push_back(receive_r(dut));
  }
  return result;
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  return run_test("CLINT FIXED/INCR read bursts and error drain", [] {
    DUT dut;
    defaults(dut);
    reset(dut);

    constexpr uint32_t mtime_low = 0x0200bff8U;
    constexpr uint32_t mtime_high = 0x0200bffcU;

    send_ar(dut, mtime_low, 1, 2, 1);
    const ReadBeat low = peek_r(dut);
    tick(dut);
    tick(dut);
    check(dut.io_R_RVALID && dut.io_R_RDATA == low.data &&
              dut.io_R_RRESP == low.resp && dut.io_R_RLAST == low.last,
          "CLINT changed an R beat while backpressured");
    check(low.resp == 0 && !low.last,
          "CLINT mtime-low INCR beat was incorrect");
    dut.io_R_RREADY = 1;
    tick(dut);
    dut.io_R_RREADY = 0;
    const ReadBeat high = receive_r(dut);
    check(high.data == 0 && high.resp == 0 && high.last,
          "CLINT INCR burst did not advance from mtime low to high");

    const auto fixed = read_burst(dut, mtime_high, 2, 2, 0);
    check(fixed.size() == 3, "CLINT returned the wrong FIXED burst length");
    for (size_t beat = 0; beat < fixed.size(); ++beat) {
      check(fixed[beat].data == 0 && fixed[beat].resp == 0 &&
                fixed[beat].last == (beat == 2),
            "CLINT FIXED burst beat was incorrect");
    }

    const auto bad_address =
        read_burst(dut, 0x0200bff0U, 2, 2, 0);
    for (size_t beat = 0; beat < bad_address.size(); ++beat) {
      check(bad_address[beat].resp == 2 &&
                bad_address[beat].last == (beat == 2),
            "CLINT did not drain an invalid-address burst through RLAST");
    }

    const auto bad_burst = read_burst(dut, mtime_low, 2, 2, 2);
    for (size_t beat = 0; beat < bad_burst.size(); ++beat) {
      check(bad_burst[beat].data == 0 && bad_burst[beat].resp == 2 &&
                bad_burst[beat].last == (beat == 2),
            "CLINT did not safely drain an unsupported burst");
    }

    const auto bad_size = read_burst(dut, mtime_low, 1, 3, 1);
    check(bad_size[0].resp == 2 && !bad_size[0].last &&
              bad_size[1].resp == 2 && bad_size[1].last,
          "CLINT did not safely drain an oversized read");
    dut.final();
  });
}
