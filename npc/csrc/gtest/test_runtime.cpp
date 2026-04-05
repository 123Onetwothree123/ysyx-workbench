#include "test_runtime.hpp"

#include <algorithm>
#include <array>
#include <cstdint>
#include <cstdlib>
#include <print>

namespace {

constexpr std::uint32_t kPmemSize = 1024 * 1024;

std::array<std::uint8_t, kPmemSize> g_pmem{};
bool g_halted = false;
std::uint32_t g_halt_pc = 0;
std::uint32_t g_halt_code = 0;

[[nodiscard]] bool is_safe_pmem_access(const std::uint32_t addr, const std::size_t len = 1) {
    if (addr < npc::test::kPmemBase) {
        return false;
    }

    const auto host_addr = static_cast<std::uint64_t>(addr - npc::test::kPmemBase);
    return host_addr + len <= kPmemSize;
}

void clear_runtime_state() {
    std::ranges::fill(g_pmem, std::uint8_t{0});
    g_halted = false;
    g_halt_pc = 0;
    g_halt_code = 0;
}

[[nodiscard]] std::uint32_t read_word_raw(const std::uint32_t addr) {
    if (!is_safe_pmem_access(addr, 4)) {
        std::println(stderr, "read_word_raw out of bound: 0x{:08x}", addr);
        std::abort();
    }

    const auto host_addr = addr - npc::test::kPmemBase;
    std::uint32_t data = 0;
    data |= static_cast<std::uint32_t>(g_pmem[host_addr + 0]) << 0;
    data |= static_cast<std::uint32_t>(g_pmem[host_addr + 1]) << 8;
    data |= static_cast<std::uint32_t>(g_pmem[host_addr + 2]) << 16;
    data |= static_cast<std::uint32_t>(g_pmem[host_addr + 3]) << 24;
    return data;
}

void write_word_raw(const std::uint32_t addr, const std::uint32_t value) {
    if (!is_safe_pmem_access(addr, 4)) {
        std::println(stderr, "write_word_raw out of bound: 0x{:08x}", addr);
        std::abort();
    }

    const auto host_addr = addr - npc::test::kPmemBase;
    g_pmem[host_addr + 0] = static_cast<std::uint8_t>((value >> 0) & 0xffu);
    g_pmem[host_addr + 1] = static_cast<std::uint8_t>((value >> 8) & 0xffu);
    g_pmem[host_addr + 2] = static_cast<std::uint8_t>((value >> 16) & 0xffu);
    g_pmem[host_addr + 3] = static_cast<std::uint8_t>((value >> 24) & 0xffu);
}

}  // namespace

extern "C" int pmem_read(int raddr) {
    auto addr = static_cast<std::uint32_t>(raddr);
    addr &= ~0x3u;

    if (!is_safe_pmem_access(addr, 4)) {
        std::println(stderr, "pmem_read out of bound: 0x{:08x}", addr);
        std::abort();
    }

    return static_cast<int>(read_word_raw(addr));
}

extern "C" void pmem_write(int waddr, int wdata, char wmask) {
    auto addr = static_cast<std::uint32_t>(waddr);
    const auto data = static_cast<std::uint32_t>(wdata);
    const auto mask = static_cast<std::uint8_t>(wmask);

    addr &= ~0x3u;

    if (!is_safe_pmem_access(addr, 4)) {
        std::println(stderr, "pmem_write out of bound: 0x{:08x}", addr);
        std::abort();
    }

    const auto host_addr = addr - npc::test::kPmemBase;
    for (int i = 0; i < 4; ++i) {
        if ((mask & (1u << i)) != 0u) {
            g_pmem[host_addr + i] = static_cast<std::uint8_t>((data >> (8 * i)) & 0xffu);
        }
    }
}

extern "C" void npc_ebreak(int pc, int code) {
    g_halted = true;
    g_halt_pc = static_cast<std::uint32_t>(pc);
    g_halt_code = static_cast<std::uint32_t>(code);
}

namespace npc::test {

CpuHarness::CpuHarness() : dut_(std::make_unique<Vminirvcpu>()) {
    clear_runtime_state();
    dut_->clk = 0;
    dut_->rst = 0;
    dut_->eval();
}

void CpuHarness::load_program(const std::span<const std::uint32_t> program_words, const std::uint32_t base_addr) {
    for (std::size_t i = 0; i < program_words.size(); ++i) {
        write_word(base_addr + static_cast<std::uint32_t>(i * 4), program_words[i]);
    }
}

void CpuHarness::write_word(const std::uint32_t addr, const std::uint32_t value) {
    write_word_raw(addr, value);
}

void CpuHarness::write_byte(const std::uint32_t addr, const std::uint8_t value) {
    if (!is_safe_pmem_access(addr, 1)) {
        std::println(stderr, "write_byte out of bound: 0x{:08x}", addr);
        std::abort();
    }
    g_pmem[addr - kPmemBase] = value;
}

std::uint32_t CpuHarness::read_word(const std::uint32_t addr) const {
    return read_word_raw(addr);
}

std::uint8_t CpuHarness::read_byte(const std::uint32_t addr) const {
    if (!is_safe_pmem_access(addr, 1)) {
        std::println(stderr, "read_byte out of bound: 0x{:08x}", addr);
        std::abort();
    }
    return g_pmem[addr - kPmemBase];
}

void CpuHarness::reset() {
    g_halted = false;
    g_halt_pc = 0;
    g_halt_code = 0;

    dut_->clk = 0;
    dut_->rst = 1;
    dut_->eval();

    dut_->clk = 1;
    dut_->eval();

    dut_->clk = 0;
    dut_->rst = 0;
    dut_->eval();
}

void CpuHarness::step() {
    dut_->clk = 0;
    dut_->eval();

    dut_->clk = 1;
    dut_->eval();
}

RunResult CpuHarness::run(const std::uint64_t max_cycles) {
    std::uint64_t cycles = 0;
    while (!g_halted && cycles < max_cycles) {
        step();
        ++cycles;
    }

    return RunResult{
        .halted = g_halted,
        .halt_pc = g_halt_pc,
        .halt_code = g_halt_code,
        .cycles = cycles,
    };
}

}  // namespace npc::test
