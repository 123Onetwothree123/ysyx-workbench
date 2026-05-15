#include <cstdint>

#include <gtest/gtest.h>

#include "cpu_test_utils.hpp"
#include "rv32_encoding.hpp"

namespace npc::test {
namespace {

using rv32::Reg;

TEST(CpuHazardTest, BackToBackArithmeticDependenciesResolveCorrectly) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, 10),
        rv32::addi(Reg::t0, Reg::t0, 20),
        rv32::addi(Reg::t0, Reg::t0, 30),
        rv32::addi(Reg::a0, Reg::t0, 0),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 60u, guest_addr(16));
}

TEST(CpuHazardTest, DataForwardingFromExToNextInstruction) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, 77),
        rv32::addi(Reg::a0, Reg::t0, 23),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 100u, guest_addr(8));
}

TEST(CpuHazardTest, SubDependsOnPredecessor) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, 100),
        rv32::addi(Reg::t1, Reg::zero, 40),
        rv32::sub(Reg::a0, Reg::t0, Reg::t1),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 60u, guest_addr(12));
}

TEST(CpuHazardTest, LuiThenAddiBuildsConstant) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::lui(Reg::t0, 0x1234'5000u),
        rv32::addi(Reg::a0, Reg::t0, 0x678),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 0x1234'5678u, guest_addr(8));
}

TEST(CpuHazardTest, ControlHazardBranchNotTakenPipelineFlush) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, 1),
        rv32::addi(Reg::t1, Reg::zero, 2),
        rv32::beq(Reg::t0, Reg::t1, 12),
        rv32::addi(Reg::a0, Reg::zero, 63),
        rv32::ebreak(),
        rv32::addi(Reg::a0, Reg::zero, 0),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 63u, guest_addr(16));
}

TEST(CpuHazardTest, LoadStoreForwardThroughSameAddress) {
    CpuHarness cpu;
    cpu.write_word(guest_addr(0x100), 42u);
    cpu.write_word(guest_addr(0x104), 0u);
    cpu.load_program({
        rv32::auipc(Reg::t0, 0),
        rv32::addi(Reg::t0, Reg::t0, 0x100),
        rv32::lw(Reg::t1, Reg::t0, 0),
        rv32::sw(Reg::t1, Reg::t0, 4),
        rv32::lw(Reg::a0, Reg::t0, 4),
        rv32::ebreak(),
    });
    cpu.reset();

    const auto result = cpu.run(64);
    ASSERT_TRUE(result.halted);
}

}  // namespace
}  // namespace npc::test
