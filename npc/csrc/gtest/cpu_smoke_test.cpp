#include <gtest/gtest.h>

#include "cpu_test_utils.hpp"
#include "rv32_encoding.hpp"

namespace npc::test {
namespace {

using rv32::Reg;

TEST(CpuExecutionTest, EbreakOnlyProgramHaltsWithinCycleBudget) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::ebreak(),
    });
    cpu.reset();

    const auto result{cpu.run(8)};
    ASSERT_TRUE(result.halted);
    EXPECT_LE(result.cycles, 8u);
    EXPECT_EQ(result.halt_pc, guest_addr(0));
}

TEST(CpuExecutionTest, InfiniteLoopStopsAtCycleBudget) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::jal(Reg::zero, 0),
    });
    cpu.reset();

    expect_timeout(cpu.run(16), 16u);
}

TEST(CpuExecutionTest, RunCanResumeAfterStoppingAtCycleBudget) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::a0, Reg::zero, 1),
        rv32::addi(Reg::a0, Reg::a0, 2),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_timeout(cpu.run(1), 1u);
    expect_halt(cpu.run(8), 3u, guest_addr(8));
}

TEST(CpuExecutionTest, ResetClearsPreviousHaltStateBeforeNextRun) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::a0, Reg::zero, 3),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 3u, guest_addr(4));

    cpu.reset();
    expect_halt(cpu.run(), 3u, guest_addr(4));
}

TEST(CpuExecutionTest, RunAfterHaltReturnsImmediatelyUntilReset) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::a0, Reg::zero, 5),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 5u, guest_addr(4));

    const auto second_run{cpu.run(16)};
    ASSERT_TRUE(second_run.halted);
    EXPECT_EQ(second_run.cycles, 0u);
    EXPECT_EQ(second_run.halt_code, 5u);
    EXPECT_EQ(second_run.halt_pc, guest_addr(4));
}

TEST(CpuExecutionTest, ResetReturnsPcToResetVectorAfterDebugPcWrite) {
    CpuHarness cpu;
    cpu.reset();
    cpu.debug_write_pc(guest_addr(0x80));
    ASSERT_EQ(cpu.debug_read_pc(), guest_addr(0x80));

    cpu.reset();

    EXPECT_EQ(cpu.debug_read_pc(), guest_addr(0));
}

TEST(CpuResetPendingTest, DebugWrittenGprSurvivesResetButHaltStateClears) {
    CpuHarness cpu;
    cpu.reset();
    cpu.debug_write_gpr(rv32::reg_bits(Reg::t0), 0x1234'5678u);
    ASSERT_EQ(cpu.debug_read_gpr(rv32::reg_bits(Reg::t0)), 0x1234'5678u);

    cpu.load_program({
        rv32::ebreak(),
    });
    cpu.reset();

    const auto result{cpu.run(8)};
    ASSERT_TRUE(result.halted);
    EXPECT_EQ(result.halt_pc, guest_addr(0));
}

TEST(CpuExecutionTest, DebugWritesCanSeedProgramStateBeforeRun) {
    CpuHarness cpu;
    const auto data_addr{guest_addr(0x100)};
    cpu.write_word(data_addr, 0u);
    cpu.load_program({
        rv32::nop(),
        rv32::nop(),
        rv32::nop(),
        rv32::nop(),
        rv32::sw(Reg::t1, Reg::t0, 0),
        rv32::lw(Reg::a0, Reg::t0, 0),
        rv32::ebreak(),
    });
    cpu.reset();
    cpu.debug_write_gpr(rv32::reg_bits(Reg::t0), data_addr);
    cpu.debug_write_gpr(rv32::reg_bits(Reg::t1), 0x2468'ace0u);
    cpu.debug_write_pc(guest_addr(0x10));

    expect_halt(cpu.run(), 0x2468'ace0u, guest_addr(0x18));
    EXPECT_EQ(cpu.read_word(data_addr), 0x2468'ace0u);
}

TEST(CpuExecutionTest, DebugPcWriteStartsExecutionAtCustomEntryPoint) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::a0, Reg::zero, 1),
        rv32::ebreak(),
    });
    cpu.load_program({
        rv32::addi(Reg::a0, Reg::zero, 42),
        rv32::ebreak(),
    }, guest_addr(0x40));
    cpu.reset();
    cpu.debug_write_pc(guest_addr(0x40));

    expect_halt(cpu.run(), 42u, guest_addr(0x44));
}

TEST(CpuExecutionTest, EndToEndProgramCallsSubroutineStoresResultAndHalts) {
    CpuHarness cpu;
    const auto result_addr{guest_addr(0x100)};
    cpu.write_word(result_addr, 0u);
    cpu.load_program({
        rv32::auipc(Reg::t0, 0),
        rv32::addi(Reg::t0, Reg::t0, 0x100),
        rv32::addi(Reg::a0, Reg::zero, 5),
        rv32::jal(Reg::ra, 16),
        rv32::sw(Reg::a0, Reg::t0, 0),
        rv32::lw(Reg::a0, Reg::t0, 0),
        rv32::ebreak(),
        rv32::addi(Reg::a0, Reg::a0, 7),
        rv32::jalr(Reg::zero, Reg::ra, 0),
    });
    cpu.reset();

    expect_halt(cpu.run(), 12u, guest_addr(24));
    EXPECT_EQ(cpu.read_word(result_addr), 12u);
}

}  // namespace
}  // namespace npc::test
