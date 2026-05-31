#include <cstdint>

#include <gtest/gtest.h>

#include "cpu_test_utils.hpp"
#include "rv32_encoding.hpp"

namespace npc::test {
namespace {

using rv32::Reg;

[[nodiscard]] constexpr std::uint32_t illegal_rtype_funct3_010_bad_funct7(const Reg rd, const Reg rs1, const Reg rs2) {
    return rv32::encode_r(0b0000001, rs2, rs1, 0b010, rd, rv32::Opcode::reg);
}

[[nodiscard]] constexpr std::uint32_t illegal_add_funct7(const Reg rd, const Reg rs1, const Reg rs2) {
    return rv32::encode_r(0b1000000, rs2, rs1, 0b000, rd, rv32::Opcode::reg);
}

[[nodiscard]] constexpr std::uint32_t illegal_slli_bad_funct7(const Reg rd, const Reg rs1, const std::uint32_t shamt) {
    return rv32::encode_i(static_cast<std::int32_t>((0b0000001u << 5) | (shamt & 0x1fu)),
                          rs1, 0b001, rd, rv32::Opcode::immediate);
}

[[nodiscard]] constexpr std::uint32_t illegal_branch_funct3_010(const Reg rs1, const Reg rs2, const std::int32_t imm) {
    return rv32::encode_b(imm, rs2, rs1, 0b010);
}

TEST(CpuIllegalTest, IllegalRTypeFunct3WithBadFunct7DoesNotCrash) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, 7),
        rv32::addi(Reg::t1, Reg::zero, 2),
        illegal_rtype_funct3_010_bad_funct7(Reg::a0, Reg::t0, Reg::t1),
        rv32::addi(Reg::a0, Reg::zero, 66),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 66u, guest_addr(16));
}

TEST(CpuIllegalTest, IllegalAddWithInvalidFunct7DoesNotCrash) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, 5),
        rv32::addi(Reg::t1, Reg::zero, 3),
        illegal_add_funct7(Reg::a0, Reg::t0, Reg::t1),
        rv32::addi(Reg::a0, Reg::zero, 55),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 55u, guest_addr(16));
}

TEST(CpuIllegalTest, IllegalShiftImmediateWithBadFunct7DoesNotCrash) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, 5),
        illegal_slli_bad_funct7(Reg::a0, Reg::t0, 3),
        rv32::addi(Reg::a0, Reg::zero, 77),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 77u, guest_addr(12));
}

TEST(CpuIllegalTest, ReservedBranchEncodingContinuesExecution) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, 1),
        rv32::addi(Reg::t1, Reg::zero, 1),
        illegal_branch_funct3_010(Reg::t0, Reg::t1, 8),
        rv32::addi(Reg::a0, Reg::zero, 99),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 99u, guest_addr(16));
}

TEST(CpuIllegalTest, MultipleIllegalInstructionsInSequenceDoNotCrash) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, 1),
        rv32::addi(Reg::t1, Reg::zero, 1),
        illegal_rtype_funct3_010_bad_funct7(Reg::a0, Reg::t0, Reg::t1),
        illegal_slli_bad_funct7(Reg::a0, Reg::t0, 3),
        illegal_branch_funct3_010(Reg::t0, Reg::t1, 8),
        rv32::addi(Reg::a0, Reg::zero, 88),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 88u, guest_addr(24));
}

}  // namespace
}  // namespace npc::test
