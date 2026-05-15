#include <gtest/gtest.h>

#include "cpu_test_utils.hpp"
#include "rv32_encoding.hpp"

namespace npc::test {
namespace {

using rv32::Reg;

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

TEST(CpuControlFlowTest, BeqTakenSkipsFallthroughInstruction) {
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

TEST(CpuControlFlowTest, BeqNotTakenFallsThrough) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::a0, Reg::zero, 5),
        rv32::addi(Reg::t0, Reg::zero, 1),
        rv32::addi(Reg::t1, Reg::zero, 2),
        rv32::beq(Reg::t0, Reg::t1, 8),
        rv32::addi(Reg::a0, Reg::a0, 3),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 8u, guest_addr(20));
}

TEST(CpuControlFlowTest, BneTakenSkipsFallthroughInstruction) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::a0, Reg::zero, 9),
        rv32::addi(Reg::t0, Reg::zero, 1),
        rv32::addi(Reg::t1, Reg::zero, 2),
        rv32::bne(Reg::t0, Reg::t1, 8),
        rv32::addi(Reg::a0, Reg::zero, 0),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 9u, guest_addr(20));
}

TEST(CpuControlFlowTest, BneNotTakenFallsThrough) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::a0, Reg::zero, 3),
        rv32::addi(Reg::t0, Reg::zero, 1),
        rv32::addi(Reg::t1, Reg::zero, 1),
        rv32::bne(Reg::t0, Reg::t1, 8),
        rv32::addi(Reg::a0, Reg::a0, 7),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 10u, guest_addr(20));
}

TEST(CpuControlFlowTest, BltTakenWhenLessThanSigned) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::a0, Reg::zero, 13),
        rv32::addi(Reg::t0, Reg::zero, -5),
        rv32::addi(Reg::t1, Reg::zero, 3),
        rv32::blt(Reg::t0, Reg::t1, 8),
        rv32::addi(Reg::a0, Reg::zero, 0),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 13u, guest_addr(20));
}

TEST(CpuControlFlowTest, BltNotTakenWhenGreaterThanOrEqual) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::a0, Reg::zero, 5),
        rv32::addi(Reg::t0, Reg::zero, 7),
        rv32::addi(Reg::t1, Reg::zero, 3),
        rv32::blt(Reg::t0, Reg::t1, 8),
        rv32::addi(Reg::a0, Reg::a0, 6),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 11u, guest_addr(20));
}

TEST(CpuControlFlowTest, BgeTakenWhenGreaterThanOrEqual) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::a0, Reg::zero, 17),
        rv32::addi(Reg::t0, Reg::zero, 7),
        rv32::addi(Reg::t1, Reg::zero, 3),
        rv32::bge(Reg::t0, Reg::t1, 8),
        rv32::addi(Reg::a0, Reg::zero, 0),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 17u, guest_addr(20));
}

TEST(CpuControlFlowTest, BgeTakenWhenEqual) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::a0, Reg::zero, 11),
        rv32::addi(Reg::t0, Reg::zero, 4),
        rv32::addi(Reg::t1, Reg::zero, 4),
        rv32::bge(Reg::t0, Reg::t1, 8),
        rv32::addi(Reg::a0, Reg::zero, 0),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 11u, guest_addr(20));
}

TEST(CpuControlFlowTest, BgeNotTakenWhenLessThan) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::a0, Reg::zero, 2),
        rv32::addi(Reg::t0, Reg::zero, 1),
        rv32::addi(Reg::t1, Reg::zero, 5),
        rv32::bge(Reg::t0, Reg::t1, 8),
        rv32::addi(Reg::a0, Reg::a0, 9),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 11u, guest_addr(20));
}

TEST(CpuControlFlowTest, BltuTakenWhenUnsignedLessThan) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::a0, Reg::zero, 19),
        rv32::addi(Reg::t0, Reg::zero, 3),
        rv32::addi(Reg::t1, Reg::zero, 7),
        rv32::bltu(Reg::t0, Reg::t1, 8),
        rv32::addi(Reg::a0, Reg::zero, 0),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 19u, guest_addr(20));
}

TEST(CpuControlFlowTest, BltuNotTakenWhenUnsignedGreaterThanOrEqual) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::a0, Reg::zero, 3),
        rv32::addi(Reg::t0, Reg::zero, 7),
        rv32::addi(Reg::t1, Reg::zero, 3),
        rv32::bltu(Reg::t0, Reg::t1, 8),
        rv32::addi(Reg::a0, Reg::a0, 8),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 11u, guest_addr(20));
}

TEST(CpuControlFlowTest, BgeuTakenWhenUnsignedGreaterThanOrEqual) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::a0, Reg::zero, 23),
        rv32::addi(Reg::t0, Reg::zero, 9),
        rv32::addi(Reg::t1, Reg::zero, 4),
        rv32::bgeu(Reg::t0, Reg::t1, 8),
        rv32::addi(Reg::a0, Reg::zero, 0),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 23u, guest_addr(20));
}

TEST(CpuControlFlowTest, BgeuNotTakenWhenUnsignedLessThan) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::a0, Reg::zero, 4),
        rv32::addi(Reg::t0, Reg::zero, 2),
        rv32::addi(Reg::t1, Reg::zero, 5),
        rv32::bgeu(Reg::t0, Reg::t1, 8),
        rv32::addi(Reg::a0, Reg::a0, 10),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 14u, guest_addr(20));
}

TEST(CpuControlFlowTest, BltNegativeSignedCompareTaken) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::a0, Reg::zero, 29),
        rv32::addi(Reg::t0, Reg::zero, -100),
        rv32::addi(Reg::t1, Reg::zero, 50),
        rv32::blt(Reg::t0, Reg::t1, 8),
        rv32::addi(Reg::a0, Reg::zero, 0),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 29u, guest_addr(20));
}

TEST(CpuControlFlowTest, BltuTreatsNegativeAsLargeUnsigned) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::a0, Reg::zero, 7),
        rv32::addi(Reg::t0, Reg::zero, -1),
        rv32::addi(Reg::t1, Reg::zero, 1),
        rv32::bltu(Reg::t0, Reg::t1, 8),
        rv32::addi(Reg::a0, Reg::a0, 5),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 12u, guest_addr(20));
}

TEST(CpuControlFlowTest, BranchBackwardNegativeOffset) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, 5),
        rv32::addi(Reg::t0, Reg::t0, -1),
        rv32::addi(Reg::t1, Reg::zero, 0),
        rv32::bne(Reg::t0, Reg::t1, -8),
        rv32::addi(Reg::a0, Reg::zero, 37),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 37u, guest_addr(20));
}

TEST(CpuControlFlowTest, NestedCallReturnSequenceSimple) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::jal(Reg::ra, 12),
        rv32::addi(Reg::a0, Reg::zero, 99),
        rv32::ebreak(),
        rv32::addi(Reg::a0, Reg::zero, 55),
        rv32::jalr(Reg::zero, Reg::ra, 0),
    });
    cpu.reset();

    expect_halt(cpu.run(), 99u, guest_addr(8));
}

TEST(CpuControlFlowTest, JalWithZeroRdDoesNotWriteReturnAddress) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::ra, Reg::zero, 0),
        rv32::jal(Reg::zero, 8),
        rv32::addi(Reg::a0, Reg::zero, 1),
        rv32::addi(Reg::a0, Reg::ra, 0),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 0u, guest_addr(16));
}

}  // namespace
}  // namespace npc::test
