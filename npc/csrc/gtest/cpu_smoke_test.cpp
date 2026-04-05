#include <array>

#include <gtest/gtest.h>

#include "cpu_test_utils.hpp"
#include "rv32_encoding.hpp"

namespace npc::test {
namespace {

using rv32::Reg;

TEST(CpuExecutionTest, ProgramHaltsWithinCycleBudget) {
    CpuHarness cpu;
    cpu.load_program(std::to_array<std::uint32_t>({
        rv32::ebreak(),
    }));
    cpu.reset();

    const auto result = cpu.run(8);
    ASSERT_TRUE(result.halted);
    EXPECT_LE(result.cycles, 8u);
    EXPECT_EQ(result.halt_pc, guest_addr(0));
}

TEST(CpuExecutionTest, InfiniteLoopStopsAtCycleBudget) {
    CpuHarness cpu;
    cpu.load_program(std::to_array<std::uint32_t>({
        rv32::jal(Reg::zero, 0),
    }));
    cpu.reset();

    expect_timeout(cpu.run(16), 16u);
}

TEST(CpuExecutionTest, ResetClearsPreviousHaltStateBeforeNextRun) {
    CpuHarness cpu;
    cpu.load_program(std::to_array<std::uint32_t>({
        rv32::addi(Reg::a0, Reg::zero, 3),
        rv32::ebreak(),
    }));
    cpu.reset();

    expect_halt(cpu.run(), 3u, guest_addr(4));

    cpu.reset();
    expect_halt(cpu.run(), 3u, guest_addr(4));
}

}  // namespace
}  // namespace npc::test
