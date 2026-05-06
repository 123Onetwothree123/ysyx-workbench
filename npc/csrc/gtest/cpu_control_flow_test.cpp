#include <gtest/gtest.h>

#include "cpu_test_utils.hpp"
#include "rv32_encoding.hpp"

namespace npc::test {
namespace {

using rv32::Reg;

[[nodiscard]] constexpr std::uint32_t bne(const Reg rs1, const Reg rs2, const std::int32_t imm) {
    return rv32::encode_b(imm, rs2, rs1, 0b001);
}

TEST(CpuControlFlowTest, JalSkipsInstructionsAndWritesReturnAddress) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::jal(Reg::ra, 8),
        rv32::addi(Reg::a0, Reg::zero, 1),
        rv32::addi(Reg::a0, Reg::ra, 0),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), guest_addr(4), guest_addr(12));
}

TEST(CpuControlFlowTest, JalSupportsNegativeOffsets) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::jal(Reg::zero, 12),
        rv32::addi(Reg::a0, Reg::zero, 77),
        rv32::ebreak(),
        rv32::addi(Reg::a0, Reg::zero, 1),
        rv32::jal(Reg::zero, -12),
    });
    cpu.reset();

    expect_halt(cpu.run(), 77u, guest_addr(8));
}

TEST(CpuControlFlowTest, JalrUsesRegisterTargetAndWritesReturnAddress) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::auipc(Reg::t0, 0),
        rv32::addi(Reg::t0, Reg::t0, 20),
        rv32::jalr(Reg::t1, Reg::t0, 0),
        rv32::addi(Reg::a0, Reg::zero, 1),
        rv32::addi(Reg::a0, Reg::zero, 2),
        rv32::addi(Reg::a0, Reg::t1, 0),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), guest_addr(12), guest_addr(24));
}

TEST(CpuControlFlowTest, JalrAddsSignedImmediateBeforeClearingTargetLowBit) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::auipc(Reg::t0, 0),
        rv32::addi(Reg::t0, Reg::t0, 25),
        rv32::jalr(Reg::ra, Reg::t0, -9),
        rv32::addi(Reg::a0, Reg::zero, 1),
        rv32::addi(Reg::a0, Reg::ra, 0),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), guest_addr(12), guest_addr(20));
}

TEST(CpuControlFlowTest, JalrCanReturnThroughRa) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::jal(Reg::ra, 12),
        rv32::addi(Reg::a0, Reg::zero, 9),
        rv32::ebreak(),
        rv32::addi(Reg::a0, Reg::zero, 3),
        rv32::jalr(Reg::zero, Reg::ra, 0),
    });
    cpu.reset();

    expect_halt(cpu.run(), 9u, guest_addr(8));
}

TEST(CpuControlFlowTest, JalrClearsLowestTargetBit) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::auipc(Reg::t0, 0),
        rv32::addi(Reg::t0, Reg::t0, 21),
        rv32::jalr(Reg::zero, Reg::t0, 0),
        rv32::addi(Reg::a0, Reg::zero, 1),
        rv32::addi(Reg::a0, Reg::zero, 2),
        rv32::auipc(Reg::a0, 0),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), guest_addr(20), guest_addr(24));
}

TEST(CpuControlFlowTest, JumpsCanTargetEbreakDirectly) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::a0, Reg::zero, 31),
        rv32::jal(Reg::zero, 8),
        rv32::addi(Reg::a0, Reg::zero, 0),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 31u, guest_addr(12));
}

TEST(CpuControlFlowTest, EbreakReportsA0ValueAndCurrentPc) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::a0, Reg::zero, 7),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 7u, guest_addr(4));
}

TEST(CpuBranchPendingTest, BeqTakenSkipsFallthroughInstruction) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::a0, Reg::zero, 7),
        rv32::addi(Reg::t0, Reg::zero, 1),
        rv32::addi(Reg::t1, Reg::zero, 1),
        rv32::beq(Reg::t0, Reg::t1, 8),
        rv32::addi(Reg::a0, Reg::zero, 0),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 7u, guest_addr(20));
}

TEST(CpuBranchPendingTest, BneTakenSkipsFallthroughInstruction) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::a0, Reg::zero, 9),
        rv32::addi(Reg::t0, Reg::zero, 1),
        rv32::addi(Reg::t1, Reg::zero, 2),
        bne(Reg::t0, Reg::t1, 8),
        rv32::addi(Reg::a0, Reg::zero, 0),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 9u, guest_addr(20));
}

}  // namespace
}  // namespace npc::test
