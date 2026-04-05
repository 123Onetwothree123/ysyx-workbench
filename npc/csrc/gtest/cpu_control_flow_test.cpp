#include <array>

#include <gtest/gtest.h>

#include "cpu_test_utils.hpp"
#include "rv32_encoding.hpp"

namespace npc::test {
namespace {

using rv32::Reg;

TEST(CpuControlFlowTest, JalSkipsInstructionsAndWritesReturnAddress) {
    CpuHarness cpu;
    cpu.load_program(std::to_array<std::uint32_t>({
        rv32::jal(Reg::ra, 8),
        rv32::addi(Reg::a0, Reg::zero, 1),
        rv32::addi(Reg::a0, Reg::ra, 0),
        rv32::ebreak(),
    }));
    cpu.reset();

    expect_halt(cpu.run(), guest_addr(4), guest_addr(12));
}

TEST(CpuControlFlowTest, JalrUsesRegisterTargetAndWritesReturnAddress) {
    CpuHarness cpu;
    cpu.load_program(std::to_array<std::uint32_t>({
        rv32::auipc(Reg::t0, 0),
        rv32::addi(Reg::t0, Reg::t0, 20),
        rv32::jalr(Reg::t1, Reg::t0, 0),
        rv32::addi(Reg::a0, Reg::zero, 1),
        rv32::addi(Reg::a0, Reg::zero, 2),
        rv32::addi(Reg::a0, Reg::t1, 0),
        rv32::ebreak(),
    }));
    cpu.reset();

    expect_halt(cpu.run(), guest_addr(12), guest_addr(24));
}

TEST(CpuControlFlowTest, JalrClearsLowestTargetBit) {
    CpuHarness cpu;
    cpu.load_program(std::to_array<std::uint32_t>({
        rv32::auipc(Reg::t0, 0),
        rv32::addi(Reg::t0, Reg::t0, 21),
        rv32::jalr(Reg::zero, Reg::t0, 0),
        rv32::addi(Reg::a0, Reg::zero, 1),
        rv32::addi(Reg::a0, Reg::zero, 2),
        rv32::auipc(Reg::a0, 0),
        rv32::ebreak(),
    }));
    cpu.reset();

    expect_halt(cpu.run(), guest_addr(20), guest_addr(24));
}

TEST(CpuControlFlowTest, EbreakReportsA0ValueAndCurrentPc) {
    CpuHarness cpu;
    cpu.load_program(std::to_array<std::uint32_t>({
        rv32::addi(Reg::a0, Reg::zero, 7),
        rv32::ebreak(),
    }));
    cpu.reset();

    expect_halt(cpu.run(), 7u, guest_addr(4));
}

}  // namespace
}  // namespace npc::test
