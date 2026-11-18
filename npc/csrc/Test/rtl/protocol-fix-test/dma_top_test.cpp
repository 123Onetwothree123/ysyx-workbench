#include "Vysyx_26030103.h"
#include "test_common.hpp"

#include <cstdint>
#include <deque>
#include <optional>
#include <string>

using DUT = Vysyx_26030103;

namespace {

// DMA 入站属性透传的探针常量（CPU 自身流量保持 0）。
constexpr std::uint8_t kDmaAwCache = 0x3;
constexpr std::uint8_t kDmaAwQos = 0x7;
constexpr std::uint8_t kDmaArCache = 0xa;
constexpr std::uint8_t kDmaArQos = 0x6;

void defaults(DUT &dut) {
  dut.clock = 0;
  dut.reset = 0;
  dut.io_interrupt = 0;
  dut.io_debug_gpr_raddr = 0;

  dut.io_master_awready = 0;
  dut.io_master_wready = 0;
  dut.io_master_bvalid = 0;
  dut.io_master_bresp = 0;
  dut.io_master_bid = 0;
  dut.io_master_arready = 0;
  dut.io_master_rvalid = 0;
  dut.io_master_rresp = 0;
  dut.io_master_rdata = 0;
  dut.io_master_rlast = 0;
  dut.io_master_rid = 0;

  dut.io_slave_awvalid = 0;
  dut.io_slave_awaddr = 0;
  dut.io_slave_awid = 0;
  dut.io_slave_awlen = 0;
  dut.io_slave_awsize = 2;
  dut.io_slave_awburst = 1;
  dut.io_slave_awlock = 0;
  dut.io_slave_awcache = 0;
  dut.io_slave_awprot = 0;
  dut.io_slave_awqos = 0;
  dut.io_slave_wvalid = 0;
  dut.io_slave_wdata = 0;
  dut.io_slave_wstrb = 0xf;
  dut.io_slave_wlast = 0;
  dut.io_slave_bready = 0;
  dut.io_slave_arvalid = 0;
  dut.io_slave_araddr = 0;
  dut.io_slave_arid = 0;
  dut.io_slave_arlen = 0;
  dut.io_slave_arsize = 2;
  dut.io_slave_arburst = 1;
  dut.io_slave_arlock = 0;
  dut.io_slave_arcache = 0;
  dut.io_slave_arprot = 0;
  dut.io_slave_arqos = 0;
  dut.io_slave_rready = 0;
}

void reset_top(DUT &dut) {
  dut.reset = 1;
  tick(dut);
  tick(dut);
  tick(dut);
  dut.reset = 0;
  dut.eval();
}

// Let the CPU issue exactly one reset-vector fetch and leave it outstanding.
// The in-order core then remains quiescent while the test exercises io_slave.
void park_cpu_on_fetch(DUT &dut) {
  dut.io_master_arready = 1;
  for (int cycle = 0; cycle < 50; ++cycle) {
    dut.eval();
    if (dut.io_master_arvalid) {
      check(dut.io_master_arid == 0,
            "initial CPU fetch did not use reserved IFU RID 0");
      tick(dut);
      dut.io_master_arready = 0;
      for (int settle = 0; settle < 5; ++settle) {
        dut.eval();
        if (!dut.io_master_arvalid) {
          return;
        }
        tick(dut);
      }
      throw std::runtime_error("initial CPU ARVALID did not clear after handshake");
    }
    tick(dut);
  }
  throw std::runtime_error("CPU never issued its reset-vector fetch");
}

[[maybe_unused]] void check_no_new_master_request(DUT &dut,
                                                   const std::string &where) {
  dut.eval();
  check(!dut.io_master_awvalid && !dut.io_master_wvalid &&
            !dut.io_master_arvalid,
        "CPU slave transaction leaked onto io_master while " + where);
}

void accept_slave_ar(DUT &dut) {
  for (int cycle = 0; cycle < 50; ++cycle) {
    dut.eval();
    if (dut.io_slave_arvalid && dut.io_slave_arready) {
      tick(dut);
      dut.io_slave_arvalid = 0;
      return;
    }
    tick(dut);
  }
  throw std::runtime_error("CPU slave never accepted AR");
}

[[maybe_unused]] void accept_slave_aw_and_w(DUT &dut) {
  bool aw_done = false;
  bool w_done = false;
  for (int cycle = 0; cycle < 100 && !(aw_done && w_done); ++cycle) {
    dut.eval();
    const bool aw_fire = !aw_done && dut.io_slave_awvalid &&
                         dut.io_slave_awready;
    const bool w_fire = !w_done && dut.io_slave_wvalid &&
                        dut.io_slave_wready;
    tick(dut);
    if (aw_fire) {
      aw_done = true;
      dut.io_slave_awvalid = 0;
    }
    if (w_fire) {
      w_done = true;
      dut.io_slave_wvalid = 0;
    }
  }
  check(aw_done && w_done, "CPU slave did not accept AW and W");
}

#ifdef TEST_CHIPLINK

void accept_forwarded_dma_ar(DUT &dut, std::uint32_t address,
                             std::uint8_t length, std::uint8_t prot) {
  for (int cycle = 0; cycle < 50; ++cycle) {
    dut.eval();
    if (dut.io_master_arvalid) {
      check(dut.io_master_arid == 2,
            "forwarded DMA read did not use downstream RID 2");
      check(dut.io_master_araddr == address &&
                dut.io_master_arlen == length &&
                dut.io_master_arsize == 2 &&
                dut.io_master_arburst == 1 &&
                dut.io_master_arprot == prot &&
                dut.io_master_arcache == kDmaArCache &&
                dut.io_master_arqos == kDmaArQos,
            "forwarded DMA AR payload/attributes changed");
      // Hold once to exercise the top-level AR backpressure path.
      tick(dut);
      check(dut.io_master_arvalid && dut.io_master_arid == 2 &&
                dut.io_master_araddr == address &&
                dut.io_master_arcache == kDmaArCache &&
                dut.io_master_arqos == kDmaArQos,
            "forwarded DMA AR was not stable while ARREADY was low");
      dut.io_master_arready = 1;
      tick(dut);
      dut.io_master_arready = 0;
      return;
    }
    tick(dut);
  }
  throw std::runtime_error("DMA read never reached io_master");
}

void return_dma_read_beat(DUT &dut, std::uint8_t original_id,
                          std::uint32_t data, bool last) {
  dut.io_master_rid = 2;
  dut.io_master_rdata = data;
  dut.io_master_rresp = 0;
  dut.io_master_rlast = last;
  dut.io_master_rvalid = 1;
  for (int cycle = 0; cycle < 50; ++cycle) {
    dut.eval();
    if (dut.io_master_rready) {
      tick(dut);
      dut.io_master_rvalid = 0;
      break;
    }
    tick(dut);
  }
  check(!dut.io_master_rvalid,
        "DMA downstream R beat was never accepted by the CPU");

  for (int cycle = 0; cycle < 50; ++cycle) {
    dut.eval();
    if (dut.io_slave_rvalid) {
      check(dut.io_slave_rid == original_id &&
                dut.io_slave_rdata == data && dut.io_slave_rresp == 0 &&
                static_cast<bool>(dut.io_slave_rlast) == last,
            "DMA read response did not restore RID/data/RLAST");
      tick(dut);
      check(dut.io_slave_rvalid && dut.io_slave_rid == original_id &&
                dut.io_slave_rdata == data,
            "DMA R response changed under slave backpressure");
      dut.io_slave_rready = 1;
      tick(dut);
      dut.io_slave_rready = 0;
      return;
    }
    tick(dut);
  }
  throw std::runtime_error("DMA read response never reached io_slave");
}

void test_chiplink_read_proxy(DUT &dut) {
  constexpr std::uint32_t address = 0xc0001040U;
  constexpr std::uint8_t original_id = 0xa;
  dut.io_slave_arid = original_id;
  dut.io_slave_araddr = address;
  dut.io_slave_arlen = 2;
  dut.io_slave_arsize = 2;
  dut.io_slave_arburst = 1;
  dut.io_slave_arprot = 5;
  dut.io_slave_arcache = kDmaArCache;
  dut.io_slave_arqos = kDmaArQos;
  dut.io_slave_arvalid = 1;
  accept_slave_ar(dut);
  accept_forwarded_dma_ar(dut, address, 2, 5);

  return_dma_read_beat(dut, original_id, 0x11111111U, false);
  return_dma_read_beat(dut, original_id, 0x22222222U, false);
  return_dma_read_beat(dut, original_id, 0x33333333U, true);
}

void accept_forwarded_dma_write(DUT &dut, std::uint32_t address,
                                std::uint32_t data, std::uint8_t prot) {
  bool aw_done = false;
  bool w_done = false;
  for (int cycle = 0; cycle < 100 && !(aw_done && w_done); ++cycle) {
    dut.eval();
    if (!aw_done && dut.io_master_awvalid) {
      check(dut.io_master_awid == 2 && dut.io_master_awaddr == address &&
                dut.io_master_awlen == 0 && dut.io_master_awsize == 2 &&
                dut.io_master_awprot == prot &&
                dut.io_master_awcache == kDmaAwCache &&
                dut.io_master_awqos == kDmaAwQos,
            "forwarded DMA AW payload/attributes was incorrect");
      dut.io_master_awready = 1;
    }
    if (!w_done && dut.io_master_wvalid) {
      check(dut.io_master_wdata == data && dut.io_master_wstrb == 0xf &&
                dut.io_master_wlast,
            "forwarded DMA W payload was incorrect");
      dut.io_master_wready = 1;
    }
    dut.eval();
    const bool aw_fire = !aw_done && dut.io_master_awvalid &&
                         dut.io_master_awready;
    const bool w_fire = !w_done && dut.io_master_wvalid &&
                        dut.io_master_wready;
    tick(dut);
    if (aw_fire) {
      aw_done = true;
      dut.io_master_awready = 0;
    }
    if (w_fire) {
      w_done = true;
      dut.io_master_wready = 0;
    }
  }
  check(aw_done && w_done, "DMA write never reached both io_master channels");
}

void perform_chiplink_write(DUT &dut, std::uint32_t address,
                            std::uint32_t data, std::uint8_t original_id) {
  dut.io_slave_awid = original_id;
  dut.io_slave_awaddr = address;
  dut.io_slave_awlen = 0;
  dut.io_slave_awsize = 2;
  dut.io_slave_awburst = 1;
  dut.io_slave_awprot = 3;
  dut.io_slave_awcache = kDmaAwCache;
  dut.io_slave_awqos = kDmaAwQos;
  dut.io_slave_awvalid = 1;
  dut.io_slave_wdata = data;
  dut.io_slave_wstrb = 0xf;
  dut.io_slave_wlast = 1;
  dut.io_slave_wvalid = 1;
  accept_slave_aw_and_w(dut);
  accept_forwarded_dma_write(dut, address, data, 3);

  dut.io_master_bid = 2;
  dut.io_master_bresp = 0;
  dut.io_master_bvalid = 1;
  dut.io_slave_bready = 0;
  bool downstream_captured = false;
  for (int cycle = 0; cycle < 50; ++cycle) {
    dut.eval();
    if (!downstream_captured && dut.io_master_bready) {
      // The arbiter must absorb DMA B independently of slave_bready so the
      // shared write path can immediately return to CPU stores.
      tick(dut);
      downstream_captured = true;
      dut.io_master_bvalid = 0;
      continue;
    }
    if (dut.io_slave_bvalid) {
      check(dut.io_slave_bid == original_id && dut.io_slave_bresp == 0,
            "DMA B response did not restore its original BID");
      check(!dut.io_master_bready,
            "buffered DMA B kept the completed downstream transaction active");
      tick(dut);
      check(dut.io_slave_bvalid && dut.io_slave_bid == original_id &&
                !dut.io_master_bready,
            "DMA B response changed or escaped backpressure");
      dut.io_slave_bready = 1;
      dut.eval();
      check(!dut.io_master_bready,
            "slave_bready incorrectly propagated through the DMA B buffer");
      tick(dut);
      dut.io_slave_bready = 0;
      dut.eval();
      check(!dut.io_slave_bvalid,
            "DMA BVALID remained set after the top-level handshake");
      check(downstream_captured,
            "DMA B reached the slave without first completing downstream");
      return;
    }
    tick(dut);
  }
  throw std::runtime_error("DMA B response never reached io_slave");
}

void test_chiplink_write_proxy(DUT &dut) {
  perform_chiplink_write(dut, 0xc0002080U, 0xa5a55a5aU, 0xb);
}

void test_chiplink_window_guard(DUT &dut) {
  // The first beat is in the ChipLink window but the complete two-beat burst
  // crosses 4GiB; validating only ARADDR would incorrectly forward it.
  dut.io_slave_arid = 7;
  dut.io_slave_araddr = 0xfffffffcU;
  dut.io_slave_arlen = 1;
  dut.io_slave_arsize = 2;
  dut.io_slave_arburst = 1;
  dut.io_slave_arprot = 2;
  dut.io_slave_arvalid = 1;
  accept_slave_ar(dut);
  check_no_new_master_request(dut, "rejecting an out-of-window DMA read");

  dut.io_slave_rready = 0;
  for (unsigned beat = 0; beat < 2; ++beat) {
    dut.eval();
    check(dut.io_slave_rvalid && dut.io_slave_rid == 7 &&
              dut.io_slave_rresp == 3 &&
              static_cast<bool>(dut.io_slave_rlast) == (beat == 1),
          "out-of-window DMA read did not return an equal-length DECERR burst");
    check_no_new_master_request(dut, "holding an out-of-window read DECERR");
    tick(dut);
    check(dut.io_slave_rvalid && dut.io_slave_rid == 7,
          "out-of-window DMA R response changed under backpressure");
    dut.io_slave_rready = 1;
    tick(dut);
    dut.io_slave_rready = 0;
  }

  // The CPU-local CLINT must never be reachable through the ChipLink
  // MEM-facing slave port either.
  dut.io_slave_awid = 8;
  dut.io_slave_awaddr = 0x0200bff8U;
  dut.io_slave_awlen = 1;
  dut.io_slave_awsize = 2;
  dut.io_slave_awburst = 1;
  dut.io_slave_awprot = 6;
  dut.io_slave_awvalid = 1;
  dut.io_slave_wdata = 0xdeadbeefU;
  dut.io_slave_wstrb = 0xf;
  dut.io_slave_wlast = 0;
  dut.io_slave_wvalid = 1;

  bool aw_done = false;
  for (int cycle = 0; cycle < 50 && !aw_done; ++cycle) {
    dut.eval();
    const bool fire = dut.io_slave_awvalid && dut.io_slave_awready;
    check_no_new_master_request(dut, "classifying an out-of-window DMA write");
    tick(dut);
    if (fire) {
      aw_done = true;
      dut.io_slave_awvalid = 0;
    }
  }
  check(aw_done, "out-of-window DMA AW was not accepted locally");

  for (unsigned beat = 0; beat < 2; ++beat) {
    dut.io_slave_wdata = 0xdead0000U + beat;
    dut.io_slave_wlast = beat == 1;
    dut.io_slave_wvalid = 1;
    bool w_done = false;
    for (int cycle = 0; cycle < 50 && !w_done; ++cycle) {
      dut.eval();
      const bool fire = dut.io_slave_wvalid && dut.io_slave_wready;
      check_no_new_master_request(dut, "draining an out-of-window DMA write");
      tick(dut);
      if (fire) {
        w_done = true;
        dut.io_slave_wvalid = 0;
      }
    }
    check(w_done, "out-of-window DMA write did not drain through WLAST");
  }

  dut.io_slave_bready = 0;
  dut.eval();
  check(dut.io_slave_bvalid && dut.io_slave_bid == 8 &&
            dut.io_slave_bresp == 3,
        "out-of-window DMA write did not return BID-preserving DECERR");
  check_no_new_master_request(dut, "holding an out-of-window write DECERR");
  tick(dut);
  check(dut.io_slave_bvalid && dut.io_slave_bid == 8,
        "out-of-window DMA B response changed under backpressure");
  dut.io_slave_bready = 1;
  tick(dut);
  dut.io_slave_bready = 0;
  dut.eval();
  check(!dut.io_slave_bvalid,
        "out-of-window DMA BVALID remained set after handshake");
}

void test_chiplink_exclusive_guard(DUT &dut) {
  // The internal proxy has no exclusive monitor.  LOCK must therefore cause a
  // local, ID-preserving DECERR rather than silently becoming a normal access.
  dut.io_slave_arid = 0xd;
  dut.io_slave_araddr = 0xc0005000U;
  dut.io_slave_arlen = 1;
  dut.io_slave_arsize = 2;
  dut.io_slave_arburst = 1;
  dut.io_slave_arlock = 1;
  dut.io_slave_arvalid = 1;
  accept_slave_ar(dut);
  check_no_new_master_request(dut, "rejecting an exclusive DMA read");

  dut.io_slave_rready = 0;
  for (unsigned beat = 0; beat < 2; ++beat) {
    dut.eval();
    check(dut.io_slave_rvalid && dut.io_slave_rid == 0xd &&
              dut.io_slave_rresp == 3 &&
              static_cast<bool>(dut.io_slave_rlast) == (beat == 1),
          "exclusive DMA read did not return an equal-length DECERR burst");
    check_no_new_master_request(dut, "holding exclusive read DECERR");
    tick(dut);
    check(dut.io_slave_rvalid && dut.io_slave_rid == 0xd,
          "exclusive read DECERR changed under backpressure");
    dut.io_slave_rready = 1;
    tick(dut);
    dut.io_slave_rready = 0;
  }
  dut.io_slave_arlock = 0;

  dut.io_slave_awid = 0xe;
  dut.io_slave_awaddr = 0xc0006000U;
  dut.io_slave_awlen = 1;
  dut.io_slave_awsize = 2;
  dut.io_slave_awburst = 1;
  dut.io_slave_awlock = 1;
  dut.io_slave_awvalid = 1;
  dut.io_slave_wvalid = 1;
  dut.io_slave_wstrb = 0xf;
  dut.io_slave_wlast = 0;

  bool aw_done = false;
  for (int cycle = 0; cycle < 50 && !aw_done; ++cycle) {
    dut.eval();
    const bool fire = dut.io_slave_awvalid && dut.io_slave_awready;
    check_no_new_master_request(dut, "classifying an exclusive DMA write");
    tick(dut);
    if (fire) {
      aw_done = true;
      dut.io_slave_awvalid = 0;
    }
  }
  check(aw_done, "exclusive DMA AW was not accepted locally");

  for (unsigned beat = 0; beat < 2; ++beat) {
    dut.io_slave_wdata = 0xee000000U + beat;
    dut.io_slave_wlast = beat == 1;
    dut.io_slave_wvalid = 1;
    bool w_done = false;
    for (int cycle = 0; cycle < 50 && !w_done; ++cycle) {
      dut.eval();
      const bool fire = dut.io_slave_wvalid && dut.io_slave_wready;
      check_no_new_master_request(dut, "draining an exclusive DMA write");
      tick(dut);
      if (fire) {
        w_done = true;
        dut.io_slave_wvalid = 0;
      }
    }
    check(w_done, "exclusive DMA W burst was not drained locally");
  }

  dut.io_slave_bready = 0;
  dut.eval();
  check(dut.io_slave_bvalid && dut.io_slave_bid == 0xe &&
            dut.io_slave_bresp == 3,
        "exclusive DMA write did not return an ID-preserving DECERR");
  check_no_new_master_request(dut, "holding exclusive write DECERR");
  tick(dut);
  check(dut.io_slave_bvalid && dut.io_slave_bid == 0xe,
        "exclusive DMA B changed under backpressure");
  dut.io_slave_bready = 1;
  tick(dut);
  dut.io_slave_bready = 0;
  dut.io_slave_awlock = 0;
}

struct ReadTransaction {
  std::uint8_t id;
  std::uint32_t address;
  std::uint8_t length;
  std::uint8_t size;
  std::uint8_t burst;
  std::uint8_t beat{0};
};

class DCacheInvalidationTest {
public:
  DCacheInvalidationTest() {
    defaults(dut_);
    reset_top(dut_);
    dut_.io_master_arready = 1;
  }

  void run() {
    for (int cycle = 0; cycle < 1000 && !barrier_seen_; ++cycle) {
      cycle_bus();
    }
    check(barrier_seen_, "CPU never reached the controlled uncached load");
    check(dcache_misses_ == 1 && dcache_hits_ == 1 && data_line_reads_ == 1,
          "DCache line was not filled once and hit once before DMA");

    // The uncached load keeps the in-order CPU parked while an external agent
    // modifies the already cached data line.
    perform_chiplink_write(dut_, kDataAddress, kNewData, 6);
    memory_updated_ = true;
    release_barrier_ = true;

    for (int cycle = 0; cycle < 1000 && !saw_halt_; ++cycle) {
      cycle_bus();
    }
    check(saw_halt_, "cache-invalidation program did not reach custom halt");
    check(dcache_misses_ == 2 && dcache_hits_ == 1 && data_line_reads_ == 2,
          "post-DMA CPU load hit a stale DCache line instead of refilling");
    dut_.final();
  }

private:
  static constexpr std::uint32_t kResetAddress = 0x30000000U;
  static constexpr std::uint32_t kDataAddress = 0xc0000000U;
  static constexpr std::uint32_t kBarrierAddress = 0x10000000U;
  static constexpr std::uint32_t kOldData = 0x11223344U;
  static constexpr std::uint32_t kNewData = 0xa1b2c3d4U;

  std::uint32_t instruction(std::uint32_t address) const {
    switch (address) {
    case kResetAddress + 0x00:
      return 0xc00002b7U; // lui  t0,0xc0000
    case kResetAddress + 0x04:
      return 0x0002a303U; // lw   t1,0(t0): miss/fill
    case kResetAddress + 0x08:
      return 0x0002a383U; // lw   t2,0(t0): hit
    case kResetAddress + 0x0c:
      return 0x10000437U; // lui  s0,0x10000
    case kResetAddress + 0x10:
      return 0x00042003U; // lw   zero,0(s0): controlled barrier
    case kResetAddress + 0x14:
      return 0x0002a483U; // lw   s1,0(t0): must miss after DMA
    case kResetAddress + 0x18:
      return 0x0000000bU; // simulation custom halt
    default:
      return 0x00000013U; // nop
    }
  }

  bool blocked_barrier(const ReadTransaction &transaction) const {
    return transaction.id == 1 &&
           transaction.address == kBarrierAddress && !release_barrier_;
  }

  void choose_response() {
    if (active_.has_value()) {
      return;
    }
    for (auto it = pending_.begin(); it != pending_.end(); ++it) {
      if (!blocked_barrier(*it)) {
        active_ = *it;
        pending_.erase(it);
        return;
      }
    }
  }

  std::uint32_t response_data(const ReadTransaction &transaction) const {
    const std::uint32_t beat_address =
        transaction.address +
        (transaction.burst == 1
             ? (static_cast<std::uint32_t>(transaction.beat)
                << transaction.size)
             : 0U);
    if (transaction.id == 0) {
      return instruction(beat_address);
    }
    if ((beat_address & 0xfffffff0U) == kDataAddress) {
      if ((beat_address & 0xfU) == 0) {
        return memory_updated_ ? kNewData : kOldData;
      }
      return 0x50000000U | (beat_address & 0xfU);
    }
    return 0;
  }

  void drive_read_response() {
    choose_response();
    if (!active_.has_value()) {
      dut_.io_master_rvalid = 0;
      dut_.io_master_rlast = 0;
      return;
    }
    const auto &transaction = *active_;
    dut_.io_master_rid = transaction.id;
    dut_.io_master_rdata = response_data(transaction);
    dut_.io_master_rresp = 0;
    dut_.io_master_rlast = transaction.beat == transaction.length;
    dut_.io_master_rvalid = 1;
  }

  void cycle_bus() {
    dut_.clock = 0;
    dut_.io_master_arready = 1;
    drive_read_response();
    dut_.eval();

    const bool hit = dut_.io_perf_dcache_hit;
    const bool miss = dut_.io_perf_dcache_miss;
    if (hit && !previous_hit_) {
      ++dcache_hits_;
    }
    if (miss && !previous_miss_) {
      ++dcache_misses_;
    }
    previous_hit_ = hit;
    previous_miss_ = miss;

    const bool ar_fire = dut_.io_master_arvalid && dut_.io_master_arready;
    const bool r_fire = dut_.io_master_rvalid && dut_.io_master_rready;
    ReadTransaction accepted{};
    if (ar_fire) {
      accepted.id = dut_.io_master_arid;
      accepted.address = dut_.io_master_araddr;
      accepted.length = dut_.io_master_arlen;
      accepted.size = dut_.io_master_arsize;
      accepted.burst = dut_.io_master_arburst;
      check(accepted.id == 0 || accepted.id == 1,
            "cache test observed an unexpected master read ID");
    }

    dut_.clock = 1;
    dut_.eval();

    if (r_fire) {
      check(active_.has_value(), "accepted R beat without an active response");
      if (active_->beat == active_->length) {
        active_.reset();
      } else {
        ++active_->beat;
      }
    }
    if (ar_fire) {
      if (accepted.id == 1 && accepted.address == kDataAddress) {
        ++data_line_reads_;
        check(accepted.length == 3 && accepted.size == 2,
              "cacheable CPU load did not request one complete cache line");
      }
      if (accepted.id == 1 && accepted.address == kBarrierAddress) {
        check(accepted.length == 0,
              "uncached barrier unexpectedly became a burst");
        barrier_seen_ = true;
      }
      pending_.push_back(accepted);
    }

    dut_.clock = 0;
    drive_read_response();
    dut_.eval();
    if (dut_.io_trap_valid) {
      saw_halt_ = true;
    }
  }

  DUT dut_;
  std::deque<ReadTransaction> pending_;
  std::optional<ReadTransaction> active_;
  bool release_barrier_{false};
  bool barrier_seen_{false};
  bool memory_updated_{false};
  bool previous_hit_{false};
  bool previous_miss_{false};
  bool saw_halt_{false};
  unsigned dcache_hits_{0};
  unsigned dcache_misses_{0};
  unsigned data_line_reads_{0};
};

void run_chiplink_enabled() {
  DUT dut;
  defaults(dut);
  reset_top(dut);
  park_cpu_on_fetch(dut);
  test_chiplink_read_proxy(dut);
  test_chiplink_write_proxy(dut);
  test_chiplink_window_guard(dut);
  test_chiplink_exclusive_guard(dut);
  dut.final();

  DCacheInvalidationTest cache_test;
  cache_test.run();
}

#else

void check_disabled_master_quiet(DUT &dut, const std::string &where) {
  check_no_new_master_request(dut, where);
}

void test_disabled_read_decerr(DUT &dut) {
  dut.io_slave_arid = 5;
  dut.io_slave_araddr = 0xc0001000U;
  dut.io_slave_arlen = 2;
  dut.io_slave_arsize = 2;
  dut.io_slave_arburst = 1;
  dut.io_slave_arvalid = 1;
  accept_slave_ar(dut);

  dut.io_slave_rready = 0;
  for (unsigned beat = 0; beat < 3; ++beat) {
    dut.eval();
    check(dut.io_slave_rvalid && dut.io_slave_rid == 5 &&
              dut.io_slave_rresp == 3 &&
              static_cast<bool>(dut.io_slave_rlast) == (beat == 2),
          "disabled ChipLink read returned the wrong DECERR burst");
    check_disabled_master_quiet(dut, "returning a local read DECERR");
    tick(dut);
    check(dut.io_slave_rvalid && dut.io_slave_rid == 5 &&
              dut.io_slave_rresp == 3,
          "disabled-path R response changed under backpressure");
    dut.io_slave_rready = 1;
    tick(dut);
    dut.io_slave_rready = 0;
  }
  dut.eval();
  check(!dut.io_slave_rvalid,
        "disabled-path RVALID remained set after ARLEN+1 beats");
}

void test_disabled_write_decerr(DUT &dut) {
  dut.io_slave_awid = 0xa;
  dut.io_slave_awaddr = 0xc0002000U;
  dut.io_slave_awlen = 2;
  dut.io_slave_awsize = 2;
  dut.io_slave_awburst = 1;
  dut.io_slave_awvalid = 1;

  // AW and the first W beat are independent; keep AW asserted until accepted.
  for (unsigned beat = 0; beat < 3; ++beat) {
    dut.io_slave_wdata = 0xabc00000U + beat;
    dut.io_slave_wstrb = 0xf;
    dut.io_slave_wlast = beat == 2;
    dut.io_slave_wvalid = 1;
    bool w_done = false;
    for (int cycle = 0; cycle < 50 && !w_done; ++cycle) {
      dut.eval();
      const bool aw_fire = dut.io_slave_awvalid && dut.io_slave_awready;
      const bool w_fire = dut.io_slave_wvalid && dut.io_slave_wready;
      check_disabled_master_quiet(dut, "draining a disabled-path write");
      tick(dut);
      if (aw_fire) {
        dut.io_slave_awvalid = 0;
      }
      if (w_fire) {
        dut.io_slave_wvalid = 0;
        w_done = true;
      }
    }
    check(w_done, "disabled DMA error slave stopped draining W burst");
  }
  check(!dut.io_slave_awvalid,
        "disabled DMA error slave never accepted AW");

  dut.io_slave_bready = 0;
  dut.eval();
  check(dut.io_slave_bvalid && dut.io_slave_bid == 0xa &&
            dut.io_slave_bresp == 3,
        "disabled ChipLink write did not return BID-preserving DECERR");
  check_disabled_master_quiet(dut, "holding a local write DECERR");
  tick(dut);
  check(dut.io_slave_bvalid && dut.io_slave_bid == 0xa &&
            dut.io_slave_bresp == 3,
        "disabled-path B response changed under backpressure");
  dut.io_slave_bready = 1;
  tick(dut);
  dut.io_slave_bready = 0;
  dut.eval();
  check(!dut.io_slave_bvalid,
        "disabled-path BVALID remained set after handshake");
}

void run_chiplink_disabled() {
  DUT dut;
  defaults(dut);
  reset_top(dut);
  park_cpu_on_fetch(dut);
  check_disabled_master_quiet(dut, "before the local DECERR tests");
  test_disabled_read_decerr(dut);
  test_disabled_write_decerr(dut);
  dut.final();
}

#endif

} // namespace

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
#ifdef TEST_CHIPLINK
  return run_test("CPU top-level ChipLink DMA read/write proxy", [] {
    run_chiplink_enabled();
  });
#else
  return run_test("CPU top-level disabled-ChipLink DECERR isolation", [] {
    run_chiplink_disabled();
  });
#endif
}
