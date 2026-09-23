#include "Vysyx_26030103_ICache.h"
#include "test_common.hpp"
#include <array>
#include <cstdint>
#include <verilated.h>

using DUT = Vysyx_26030103_ICache;

static void defaults(DUT &dut) {
  dut.io_fetch_addr = 0;
  dut.io_fetch_valid = 0;
  dut.io_resp_ready = 0;
  dut.io_axi_AR_ARREADY = 0;
  dut.io_axi_R_RDATA = 0;
  dut.io_axi_R_RRESP = 0;
  dut.io_axi_R_RLAST = 0;
  dut.io_axi_R_RVALID = 0;
  dut.io_flush = 0;
  dut.io_kill = 0;
}

static void consume_local_fault(DUT &dut, std::uint32_t address) {
  dut.io_fetch_addr = address;
  dut.io_fetch_valid = 1;
  dut.io_resp_ready = 0;
  dut.eval();
  check(dut.io_fetch_ready, "ICache did not accept an illegal fetch");
  check(!dut.io_axi_AR_ARVALID,
        "ICache issued AXI before accepting an illegal fetch");
  tick(dut);
  dut.io_fetch_valid = 0;

  for (int cycle = 0; cycle < 3; ++cycle) {
    dut.eval();
    check(dut.io_resp_valid && dut.io_resp_fault,
          "illegal fetch did not produce a local fault response");
    check(dut.io_resp_addr == address,
          "illegal fetch response lost the faulting PC");
    check(dut.io_resp_data == 0x00000013U,
          "illegal fetch did not substitute a NOP payload");
    check(dut.io_access_fault_resp == 3,
          "PMA rejection did not report DECERR to debug logic");
    check(!dut.io_axi_AR_ARVALID,
          "illegal fetch touched AXI while response was backpressured");
    tick(dut);
  }

  dut.io_resp_ready = 1;
  tick(dut);
  dut.io_resp_ready = 0;
  dut.eval();
  check(!dut.io_resp_valid,
        "illegal fetch response did not retire after its handshake");
}

static void executable_memory_uses_line_refill(DUT &dut,
                                               std::uint32_t address,
                                               std::uint32_t line_address) {
  dut.io_fetch_addr = address;
  dut.io_fetch_valid = 1;
  dut.eval();
  check(dut.io_fetch_ready, "ICache did not accept an executable fetch");
  tick(dut);
  dut.io_fetch_valid = 0;

  wait_until(
      dut, [&] { return bool(dut.io_axi_AR_ARVALID); },
      "executable MROM fetch never issued an AXI refill", 8);
  check(dut.io_axi_AR_ARADDR == line_address,
        "cacheable fetch did not align its line refill");
  check(dut.io_axi_AR_ARLEN == 3,
        "16-byte cache line did not request four AXI beats");
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
#ifdef TEST_NPC
  constexpr const char *kTestName = "ICache PMA enforcement for direct NPC";
#elif defined(TEST_CHIPLINK)
  constexpr const char *kTestName =
      "ICache PMA enforcement with ChipLink enabled";
#else
  constexpr const char *kTestName =
      "ICache PMA enforcement with ChipLink disabled";
#endif
  return run_test(kTestName, [] {
    DUT dut;
    defaults(dut);
    reset(dut);

    // Every one of these addresses used to be treated as cacheable and could
    // generate a four-beat line read into a device or an unmapped hole.
    constexpr std::array<std::uint32_t, 5> kIllegalFetches = {
        0x02000000U, // CLINT
        0x10000000U, // UART
        0x21000000U, // VGA/MMIO
        0x20000000U, // unmapped hole
#ifdef TEST_NPC
        0x80040000U // first byte beyond the direct NPC's 256 KiB RAM
#elif defined(TEST_CHIPLINK)
        0x40000000U // ChipLink MMIO remains non-executable
#else
        0xc0000000U // ChipLink memory is a hole when the link is disabled
#endif
    };
    for (const auto address : kIllegalFetches) {
      consume_local_fault(dut, address);
    }

#ifdef TEST_NPC
    executable_memory_uses_line_refill(dut, 0x80000004U, 0x80000000U);
#else
    executable_memory_uses_line_refill(dut, 0x30000004U, 0x30000000U);
#endif
#if defined(TEST_CHIPLINK) && !defined(TEST_NPC)
    // With the link enabled, its MEM aperture is both executable and cacheable.
    reset(dut);
    defaults(dut);
    executable_memory_uses_line_refill(dut, 0xc0000004U, 0xc0000000U);
#endif
    dut.final();
  });
}
