#include <cstdint>

#include <gtest/gtest.h>

#include "cpu_test_utils.hpp"
#include "rv32_encoding.hpp"

namespace npc::test {
namespace {

using rv32::Reg;

[[nodiscard]] constexpr std::uint32_t sub(const Reg rd, const Reg rs1, const Reg rs2) {
    return rv32::encode_r(0b0100000, rs2, rs1, 0b000, rd, rv32::Opcode::reg);
}

[[nodiscard]] constexpr std::uint32_t bitwise_and(const Reg rd, const Reg rs1, const Reg rs2) {
    return rv32::encode_r(0b0000000, rs2, rs1, 0b111, rd, rv32::Opcode::reg);
}

[[nodiscard]] constexpr std::uint32_t bitwise_or(const Reg rd, const Reg rs1, const Reg rs2) {
    return rv32::encode_r(0b0000000, rs2, rs1, 0b110, rd, rv32::Opcode::reg);
}

[[nodiscard]] constexpr std::uint32_t bitwise_xor(const Reg rd, const Reg rs1, const Reg rs2) {
    return rv32::encode_r(0b0000000, rs2, rs1, 0b100, rd, rv32::Opcode::reg);
}

[[nodiscard]] constexpr std::uint32_t sll(const Reg rd, const Reg rs1, const Reg rs2) {
    return rv32::encode_r(0b0000000, rs2, rs1, 0b001, rd, rv32::Opcode::reg);
}

[[nodiscard]] constexpr std::uint32_t srli(const Reg rd, const Reg rs1, const std::uint32_t shamt) {
    return rv32::encode_i(static_cast<std::int32_t>(shamt & 0x1fu), rs1, 0b101, rd, rv32::Opcode::immediate);
}

[[nodiscard]] constexpr std::uint32_t srai(const Reg rd, const Reg rs1, const std::uint32_t shamt) {
    return rv32::encode_i(static_cast<std::int32_t>((0b0100000u << 5) | (shamt & 0x1fu)),
                          rs1, 0b101, rd, rv32::Opcode::immediate);
}

[[nodiscard]] constexpr std::uint32_t slt(const Reg rd, const Reg rs1, const Reg rs2) {
    return rv32::encode_r(0b0000000, rs2, rs1, 0b010, rd, rv32::Opcode::reg);
}

[[nodiscard]] constexpr std::uint32_t sltu(const Reg rd, const Reg rs1, const Reg rs2) {
    return rv32::encode_r(0b0000000, rs2, rs1, 0b011, rd, rv32::Opcode::reg);
}

struct AddiCase {
    std::int32_t immediate;
    std::uint32_t expected;
};

class CpuAddiTest : public ::testing::TestWithParam<AddiCase> {};

TEST_P(CpuAddiTest, SignExtendsTwelveBitImmediate) {
    const auto [immediate, expected] = GetParam();

    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::a0, Reg::zero, immediate),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), expected, guest_addr(4));
}

INSTANTIATE_TEST_SUITE_P(
    ImmediateEdges,
    CpuAddiTest,
    ::testing::Values(
        AddiCase{.immediate = 0, .expected = 0u},
        AddiCase{.immediate = 1, .expected = 1u},
        AddiCase{.immediate = 2047, .expected = 2047u},
        AddiCase{.immediate = -1, .expected = 0xffff'ffffu},
        AddiCase{.immediate = -2048, .expected = 0xffff'f800u}
    )
);

TEST(CpuArithmeticTest, AddUsesLatestRegisterValuesAndWrapsModulo32) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, -1),
        rv32::addi(Reg::t1, Reg::zero, 2),
        rv32::add(Reg::a0, Reg::t0, Reg::t1),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 1u, guest_addr(12));
}

TEST(CpuArithmeticTest, AddCanReadZeroRegisterAsOperand) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, 123),
        rv32::add(Reg::a0, Reg::t0, Reg::zero),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 123u, guest_addr(8));
}

TEST(CpuArithmeticTest, BackToBackDependenciesUseNewlyWrittenValues) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, 1),
        rv32::addi(Reg::t0, Reg::t0, 2),
        rv32::add(Reg::t0, Reg::t0, Reg::t0),
        rv32::addi(Reg::a0, Reg::t0, -1),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 5u, guest_addr(16));
}

TEST(CpuArithmeticTest, LuiCopiesUpperImmediateAndClearsLowBits) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::lui(Reg::a0, 0x8000'1000u),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 0x8000'1000u, guest_addr(4));
}

TEST(CpuArithmeticTest, LuiAndAddiBuildConstantAcrossSignBoundary) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::lui(Reg::a0, 0x1234'6000u),
        rv32::addi(Reg::a0, Reg::a0, -0x788),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 0x1234'5878u, guest_addr(8));
}

TEST(CpuArithmeticTest, AuipcUsesCurrentInstructionPcAsBase) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::jal(Reg::zero, 8),
        rv32::addi(Reg::a0, Reg::zero, 1),
        rv32::auipc(Reg::a0, 0),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), guest_addr(8), guest_addr(12));
}

TEST(CpuArithmeticTest, AuipcAddsUpperImmediateToProgramCounter) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::auipc(Reg::a0, 0x2000),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), guest_addr(0x2000), guest_addr(4));
}

TEST(CpuArithmeticTest, ZeroRegisterIgnoresWritesFromEveryImplementedWritebackPath) {
    CpuHarness cpu;
    cpu.write_word(guest_addr(0x100), 0xdead'beefu);
    cpu.load_program({
        rv32::auipc(Reg::t0, 0),
        rv32::addi(Reg::t0, Reg::t0, 0x100),
        rv32::addi(Reg::zero, Reg::zero, 99),
        rv32::lui(Reg::zero, 0xffff'f000u),
        rv32::lw(Reg::zero, Reg::t0, 0),
        rv32::jal(Reg::zero, 8),
        rv32::addi(Reg::zero, Reg::zero, 77),
        rv32::addi(Reg::a0, Reg::zero, 0),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 0u, guest_addr(32));
}

TEST(CpuRv32iPendingTest, SubSubtractsOperands) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, 7),
        rv32::addi(Reg::t1, Reg::zero, 2),
        sub(Reg::a0, Reg::t0, Reg::t1),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 5u, guest_addr(12));
}

TEST(CpuRv32iPendingTest, BitwiseRegisterOpsOperatePerBit) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, 0x0f0),
        rv32::addi(Reg::t1, Reg::zero, 0x033),
        bitwise_and(Reg::a1, Reg::t0, Reg::t1),
        bitwise_or(Reg::a2, Reg::t0, Reg::t1),
        bitwise_xor(Reg::a0, Reg::a1, Reg::a2),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 0x0c3u, guest_addr(20));
}

TEST(CpuRv32iPendingTest, ShiftLeftUsesLowFiveBitsOfRegisterOperand) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, 1),
        rv32::addi(Reg::t1, Reg::zero, 35),
        sll(Reg::a0, Reg::t0, Reg::t1),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 8u, guest_addr(12));
}

TEST(CpuRv32iPendingTest, ShiftRightImmediateVariantsDistinguishLogicalAndArithmetic) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::lui(Reg::t0, 0x8000'0000u),
        srli(Reg::a1, Reg::t0, 31),
        srai(Reg::a2, Reg::t0, 31),
        bitwise_xor(Reg::a0, Reg::a1, Reg::a2),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 0xffff'fffeu, guest_addr(16));
}

TEST(CpuRv32iPendingTest, SignedAndUnsignedSetLessThanUseDifferentOrdering) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, -1),
        rv32::addi(Reg::t1, Reg::zero, 1),
        slt(Reg::a1, Reg::t0, Reg::t1),
        sltu(Reg::a2, Reg::t1, Reg::t0),
        rv32::add(Reg::a0, Reg::a1, Reg::a2),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 2u, guest_addr(20));
}

}  // namespace
}  // namespace npc::test
