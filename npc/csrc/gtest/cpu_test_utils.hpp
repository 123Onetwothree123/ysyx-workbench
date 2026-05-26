#pragma once

#include <cstdint>
#include <iomanip>

#include <gtest/gtest.h>

#include "test_runtime.hpp"

namespace npc::test {

inline void expect_halt_code(const RunResult &result, const std::uint32_t expected_code) {
    ASSERT_TRUE(result.halted);
    EXPECT_EQ(result.halt_code, expected_code);
}

inline void expect_halt(const RunResult &result, const std::uint32_t expected_code, const std::uint32_t expected_pc) {
    ASSERT_TRUE(result.halted);
    EXPECT_EQ(result.halt_code, expected_code);
    EXPECT_EQ(result.halt_pc, expected_pc);
}

inline void expect_timeout(const RunResult &result, const std::uint64_t expected_cycles) {
    EXPECT_FALSE(result.halted);
    EXPECT_EQ(result.cycles, expected_cycles);
}

inline void expect_gpr(CpuHarness &cpu, const std::uint8_t reg, const std::uint32_t expected) {
    EXPECT_EQ(cpu.debug_read_gpr(reg), expected) << "x" << static_cast<unsigned>(reg);
}

inline void expect_pc(CpuHarness &cpu, const std::uint32_t expected) {
    EXPECT_EQ(cpu.debug_read_pc(), expected);
}

inline void expect_memory_word(const CpuHarness &cpu, const std::uint32_t addr, const std::uint32_t expected) {
    EXPECT_EQ(cpu.read_word(addr), expected) << "addr=0x" << std::hex << addr;
}

inline void expect_memory_half(const CpuHarness &cpu, const std::uint32_t addr, const std::uint16_t expected) {
    EXPECT_EQ(cpu.read_half(addr), expected) << "addr=0x" << std::hex << addr;
}

inline void expect_memory_byte(const CpuHarness &cpu, const std::uint32_t addr, const std::uint8_t expected) {
    EXPECT_EQ(cpu.read_byte(addr), expected) << "addr=0x" << std::hex << addr;
}

}  // namespace npc::test
