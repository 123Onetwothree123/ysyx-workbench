#pragma once

#include <cstdint>

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

}  // namespace npc::test
