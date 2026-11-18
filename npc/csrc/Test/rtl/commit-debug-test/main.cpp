#include "Vysyx_26030103.h"

#include <verilated.h>

#include <cstdint>
#include <iostream>
#include <stdexcept>
#include <string>
#include <unordered_map>

namespace {

constexpr std::uint32_t kReset = 0x80000000U;
constexpr std::uint32_t kCustomHalt = 0x0000000bU;

void check(bool condition, const std::string &message) {
  if (!condition) {
    throw std::runtime_error(message);
  }
}

class CoreTestbench {
public:
  CoreTestbench() {
    // Program layout:
    //   install mtvec; write a0=7; execute architectural EBREAK;
    //   run a misaligned load, a DECERR load and a successful load;
    //   handler advances mepc after every exception; write a0=8; execute two
    //   taken JALs (the first flushes a wrong-path fetch fault), then take one
    //   real instruction access fault and finally execute custom halt.
    words_ = {
        {kReset + 0x00, 0x800002b7U}, // lui   t0,0x80000
        {kReset + 0x04, 0x06028293U}, // addi  t0,t0,0x60
        {kReset + 0x08, 0x30529073U}, // csrw  mtvec,t0
        {kReset + 0x0c, 0x00700513U}, // addi  a0,zero,7
        {kReset + 0x10, 0x00100073U}, // ebreak (must enter mtvec)
        {kReset + 0x14, 0x0022a383U}, // lw    t2,2(t0), misaligned
        {kReset + 0x18, 0x900003b7U}, // lui   t2,0x90000
        {kReset + 0x1c, 0x0003a303U}, // lw    t1,0(t2), bus DECERR
        {kReset + 0x20, 0x0002a303U}, // lw    t1,0(t0), successful
        {kReset + 0x24, 0x00150513U}, // addi  a0,a0,1
        {kReset + 0x28, 0x0080006fU}, // jal   zero,+8 -> 0x30
        {kReset + 0x2c, 0x00000013U}, // wrong path; bus deliberately DECERR
        {kReset + 0x30, 0x0100006fU}, // jal   zero,+16 -> 0x40
        {kReset + 0x44, kCustomHalt}, // resume here after fetch fault
        {kReset + 0x60, 0x34102373U}, // csrr  t1,mepc
        {kReset + 0x64, 0x00430313U}, // addi  t1,t1,4
        {kReset + 0x68, 0x34131073U}, // csrw  mepc,t1
        {kReset + 0x6c, 0x30200073U}, // mret
    };
    driveStaticInputs();
  }

  void reset() {
    dut_.reset = 1;
    read_active_ = false;
    for (int i = 0; i < 3; ++i) {
      cycle(false);
    }
    dut_.reset = 0;
  }

  void run() {
    bool saw_a0_seven_commit = false;
    bool saw_handler_commit = false;
    bool saw_a0_eight_commit = false;
    bool saw_halt = false;
    unsigned data_access_fault_count = 0;
    unsigned fetch_access_fault_count = 0;
    unsigned mtrace_count = 0;
    unsigned trap_count = 0;
    unsigned mret_count = 0;

    for (int elapsed = 0; elapsed < 1000 && !saw_halt; ++elapsed) {
      cycle(true);

      if (dut_.io_debug_commit) {
        const auto pc = static_cast<std::uint32_t>(dut_.io_debug_pc);
        const auto instruction =
            static_cast<std::uint32_t>(dut_.io_debug_instructions);
        if (pc == kReset + 0x0c) {
          check(instruction == 0x00700513U,
                "debug instruction was not aligned with the a0=7 commit");
          check(dut_.io_debug_gpr_rdata == 7,
                "debug_commit became visible before the a0=7 GPR write");
          check(!saw_a0_seven_commit,
                "the a0=7 instruction emitted duplicate commit events");
          check(dut_.io_debug_next_pc == kReset + 0x10,
                "sequential retire next-PC was not PC+4");
          check(dut_.io_debug_arch_pc == kReset + 0x10,
                "architectural PC exposed the retire trace PC");
          saw_a0_seven_commit = true;
        }
        if (pc >= kReset + 0x60 && pc <= kReset + 0x6c) {
          saw_handler_commit = true;
        }
        if (pc == kReset + 0x24) {
          check(instruction == 0x00150513U,
                "debug instruction was not aligned with the a0=8 commit");
          check(dut_.io_debug_gpr_rdata == 8,
                "debug_commit became visible before the a0=8 GPR write");
          check(!saw_a0_eight_commit,
                "the a0=8 instruction emitted duplicate commit events");
          check(dut_.io_debug_next_pc == kReset + 0x28,
                "final sequential retire carried the wrong next-PC");
          saw_a0_eight_commit = true;
        }
        if (pc == kReset + 0x28) {
          check(dut_.io_debug_next_pc == kReset + 0x30,
                "taken JAL retire record carried the sequential PC");
        }
        if (pc == kReset + 0x30) {
          check(dut_.io_debug_next_pc == kReset + 0x40,
                "JAL to the real fetch-fault address carried the wrong PC");
        }
        if (pc == kReset + 0x6c) {
          const auto next =
              static_cast<std::uint32_t>(dut_.io_debug_next_pc);
          check(next == kReset + 0x14 || next == kReset + 0x18 ||
                    next == kReset + 0x20 || next == kReset + 0x44,
                "MRET retire record used snpc instead of mepc");
          ++mret_count;
        }
      }

      if (dut_.io_debug_trap_valid) {
        ++trap_count;
        const auto pc = static_cast<std::uint32_t>(dut_.io_debug_trap_pc);
        const auto cause =
            static_cast<std::uint32_t>(dut_.io_debug_trap_cause);
        check(dut_.io_debug_trap_target == kReset + 0x60,
              "trap event carried the wrong mtvec target");
        check(dut_.io_debug_arch_pc == kReset + 0x60,
              "architectural PC did not move to mtvec at trap commit");
        if (pc == kReset + 0x10) {
          check(cause == 3, "EBREAK trap carried the wrong cause");
        } else if (pc == kReset + 0x14) {
          check(cause == 4, "misaligned load trap carried the wrong cause");
        } else if (pc == kReset + 0x1c) {
          check(cause == 5, "load access fault carried the wrong cause");
        } else if (pc == kReset + 0x40) {
          check(cause == 1,
                "instruction access fault carried the wrong cause");
        } else {
          check(false, "unexpected precise trap event PC");
        }
      }

      if (dut_.io_debug_access_fault) {
        check(dut_.io_debug_access_fault_resp == 3,
              "DECERR response code was not preserved by debug fault trace");
        const auto pc =
            static_cast<std::uint32_t>(dut_.io_debug_access_fault_pc);
        if (pc == kReset + 0x1c) {
          ++data_access_fault_count;
        } else if (pc == kReset + 0x40) {
          ++fetch_access_fault_count;
        } else {
          check(false,
                "wrong-path, misaligned, or unrelated access fault was reported");
        }
      }

      if (dut_.io_debug_mtrace_valid) {
        ++mtrace_count;
        check(dut_.io_debug_mtrace_pc == kReset + 0x20,
              "failed or misaligned load generated an mtrace record");
        check(!dut_.io_debug_mtrace_wen,
              "successful load mtrace was marked as a store");
        check(dut_.io_debug_mtrace_addr == kReset + 0x60,
              "successful load mtrace carried the wrong address");
        check(dut_.io_debug_mtrace_rdata == 0x34102373U,
              "successful load mtrace carried stale read data");
        check(dut_.io_debug_mtrace_width == 2,
              "successful word load mtrace carried the wrong width");
      }

      if (dut_.io_trap_valid) {
        check(dut_.io_trap_pc == kReset + 0x44,
              "architectural EBREAK leaked into the simulation halt output");
        check(dut_.io_debug_gpr_rdata == 8,
              "simulation halt was visible before older GPR writes committed");
        check(dut_.io_debug_arch_pc == kReset + 0x44,
              "debugger PC did not point at the simulation halt instruction");
        saw_halt = true;
      }
    }

    check(saw_a0_seven_commit, "never observed the first a0 commit");
    check(saw_handler_commit,
          "architectural EBREAK did not execute its mtvec handler");
    check(saw_a0_eight_commit,
          "execution did not resume after architectural EBREAK");
    check(data_access_fault_count == 1,
          "data DECERR did not produce exactly one debug fault event");
    check(fetch_access_fault_count == 1,
          "precise instruction DECERR event was missing or duplicated");
    check(mtrace_count == 1,
          "successful and failed loads produced the wrong mtrace count");
    check(trap_count == 4,
          "architectural exceptions did not produce one precise event each");
    check(mret_count == 4,
          "MRET retirement/next-PC records were missing or duplicated");
    check(saw_halt, "custom halt never produced a simulation halt event");
  }

  void final() { dut_.final(); }

private:
  void driveStaticInputs() {
    dut_.clock = 0;
    dut_.reset = 0;
    dut_.io_interrupt = 0;
    dut_.io_debug_gpr_raddr = 10; // a0

    dut_.io_master_awready = 1;
    dut_.io_master_wready = 1;
    dut_.io_master_bvalid = 0;
    dut_.io_master_bresp = 0;
    dut_.io_master_bid = 0;

    dut_.io_slave_awvalid = 0;
    dut_.io_slave_awaddr = 0;
    dut_.io_slave_awid = 0;
    dut_.io_slave_awlen = 0;
    dut_.io_slave_awsize = 0;
    dut_.io_slave_awburst = 0;
    dut_.io_slave_awlock = 0;
    dut_.io_slave_awcache = 0;
    dut_.io_slave_awprot = 0;
    dut_.io_slave_awqos = 0;
    dut_.io_slave_wvalid = 0;
    dut_.io_slave_wdata = 0;
    dut_.io_slave_wstrb = 0;
    dut_.io_slave_wlast = 0;
    dut_.io_slave_bready = 0;
    dut_.io_slave_arvalid = 0;
    dut_.io_slave_araddr = 0;
    dut_.io_slave_arid = 0;
    dut_.io_slave_arlen = 0;
    dut_.io_slave_arsize = 0;
    dut_.io_slave_arburst = 0;
    dut_.io_slave_arlock = 0;
    dut_.io_slave_arcache = 0;
    dut_.io_slave_arprot = 0;
    dut_.io_slave_arqos = 0;
    dut_.io_slave_rready = 0;
  }

  std::uint32_t readWord(std::uint32_t address) const {
    const auto found = words_.find(address);
    return found == words_.end() ? 0x00000013U : found->second; // NOP
  }

  void driveReadBus() {
    dut_.io_master_arready = !read_active_;
    dut_.io_master_rvalid = read_active_;
    dut_.io_master_rresp =
        (read_address_ == 0x90000000U ||
         read_address_ == kReset + 0x2c ||
         read_address_ == kReset + 0x40)
            ? 3
            : 0;
    dut_.io_master_rid = read_id_;
    dut_.io_master_rdata = readWord(read_address_);
    dut_.io_master_rlast = read_active_ && read_beat_ == read_length_;
  }

  void cycle(bool check_writes) {
    dut_.clock = 0;
    driveReadBus();
    dut_.eval();

    if (check_writes) {
      check(!dut_.io_master_awvalid && !dut_.io_master_wvalid,
            "debug timing program unexpectedly issued a data write");
    }

    const bool ar_fire = dut_.io_master_arvalid && dut_.io_master_arready;
    const bool r_fire = dut_.io_master_rvalid && dut_.io_master_rready;
    const std::uint32_t accepted_address = dut_.io_master_araddr;
    const std::uint8_t accepted_id = dut_.io_master_arid;
    const std::uint8_t accepted_length = dut_.io_master_arlen;
    const std::uint8_t accepted_size = dut_.io_master_arsize;
    const std::uint8_t accepted_burst = dut_.io_master_arburst;

    dut_.clock = 1;
    dut_.eval();

    if (r_fire) {
      if (read_beat_ == read_length_) {
        read_active_ = false;
      } else {
        ++read_beat_;
        if (read_burst_ == 1) {
          read_address_ += 1U << read_size_;
        }
      }
    }
    if (ar_fire) {
      check(!read_active_, "accepted a second AXI read while one was active");
      read_active_ = true;
      read_address_ = accepted_address;
      read_id_ = accepted_id;
      read_length_ = accepted_length;
      read_size_ = accepted_size;
      read_burst_ = accepted_burst;
      read_beat_ = 0;
    }

    dut_.clock = 0;
    driveReadBus();
    dut_.eval();
  }

  Vysyx_26030103 dut_;
  std::unordered_map<std::uint32_t, std::uint32_t> words_;
  bool read_active_{false};
  std::uint32_t read_address_{0};
  std::uint8_t read_id_{0};
  std::uint8_t read_length_{0};
  std::uint8_t read_size_{2};
  std::uint8_t read_burst_{1};
  std::uint8_t read_beat_{0};
};

} // namespace

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  try {
    CoreTestbench testbench;
    testbench.reset();
    testbench.run();
    testbench.final();
    std::cout << "[PASS] post-edge commit, EBREAK, and custom halt timing\n";
    return 0;
  } catch (const std::exception &error) {
    std::cerr << "[FAIL] commit/debug timing: " << error.what() << '\n';
    return 1;
  }
}
