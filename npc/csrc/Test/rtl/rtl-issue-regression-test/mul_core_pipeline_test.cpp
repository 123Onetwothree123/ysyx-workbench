#include "Vysyx_26030103_MULWallaceCore.h"
#include "test_common.hpp"

#include <array>
#include <cstdint>
#include <verilated.h>

using DUT = Vysyx_26030103_MULWallaceCore;

static void wallace_core_accepts_and_returns_back_to_back_requests() {
  DUT dut;
  dut.IO_Req_valid = 0;
  dut.IO_Req_bits_LHSMagnitude = 0;
  dut.IO_Req_bits_RHSMagnitude = 0;
  dut.IO_Resp_ready = 1;
  dut.IO_Flush = 0;
  reset(dut);

  dut.IO_Req_valid = 1;
  dut.IO_Req_bits_LHSMagnitude = 3;
  dut.IO_Req_bits_RHSMagnitude = 5;
  dut.eval();
  check(dut.IO_Req_ready, "Wallace core rejected the first pipeline request");
  tick(dut);

  dut.IO_Req_bits_LHSMagnitude = 7;
  dut.IO_Req_bits_RHSMagnitude = 11;
  dut.eval();
  check(dut.IO_Req_ready,
        "Wallace core itself cannot accept back-to-back requests");
  tick(dut);
  dut.IO_Req_valid = 0;

  std::array<std::uint64_t, 2> products{};
  unsigned responses = 0;
  for (int cycle = 0; cycle < 12 && responses < products.size(); ++cycle) {
    dut.eval();
    if (dut.IO_Resp_valid) {
      products[responses++] = dut.IO_Resp_bits_Product;
    }
    tick(dut);
  }
  check(responses == 2, "Wallace core did not return both pipelined responses");
  check(products[0] == 15 && products[1] == 77,
        "Wallace core changed result order or product data");
  dut.final();
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  return run_test("Wallace core has initiation interval one",
                  wallace_core_accepts_and_returns_back_to_back_requests);
}
