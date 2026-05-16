#pragma once

#include <cstdint>
#include <initializer_list>
#include <memory>
#include <span>

#include "VRV32E32Reg.h"

namespace npc::test {

inline constexpr std::uint32_t kPmemBase{0x80000000u};

[[nodiscard]] constexpr std::uint32_t guest_addr(const std::uint32_t offset) {
    return kPmemBase + offset;
}

struct RunResult {
    bool halted{};
    std::uint32_t halt_pc{};
    std::uint32_t halt_code{};
    std::uint64_t cycles{};
};

class CpuHarness {
public:
    CpuHarness();
    ~CpuHarness();

    CpuHarness(const CpuHarness &) = delete;
    CpuHarness &operator=(const CpuHarness &) = delete;
    CpuHarness(CpuHarness &&) = delete;
    CpuHarness &operator=(CpuHarness &&) = delete;

    void load_program(std::span<const std::uint32_t> program_words, std::uint32_t base_addr = kPmemBase);
    void load_program(std::initializer_list<std::uint32_t> program_words, std::uint32_t base_addr = kPmemBase);
    void write_word(std::uint32_t addr, std::uint32_t value);
    void write_half(std::uint32_t addr, std::uint16_t value);
    void write_byte(std::uint32_t addr, std::uint8_t value);

    [[nodiscard]] std::uint32_t read_word(std::uint32_t addr) const;
    [[nodiscard]] std::uint16_t read_half(std::uint32_t addr) const;
    [[nodiscard]] std::uint8_t read_byte(std::uint32_t addr) const;

    void reset();
    void step();
    [[nodiscard]] RunResult run(std::uint64_t max_cycles = 256);

    void debug_write_gpr(std::uint8_t reg, std::uint32_t value);
    void debug_write_pc(std::uint32_t pc);
    [[nodiscard]] std::uint32_t debug_read_gpr(std::uint8_t reg);
    [[nodiscard]] std::uint32_t debug_read_pc();

private:
    void pulse_debug_clock();

    std::unique_ptr<VRV32E32Reg> dut_;
};

}  // namespace npc::test
