#include <cstdint>

#include <gtest/gtest.h>

#include "cpu_test_utils.hpp"
#include "rv32_encoding.hpp"

namespace npc::test {
namespace {

using rv32::Reg;

struct AddiCase {
    std::int32_t immediate;
    std::uint32_t expected;
};

class CpuAddiTest : public ::testing::TestWithParam<AddiCase> {};

TEST_P(CpuAddiTest, SignExtendsTwelveBitImmediate) {
    const auto [immediate, expected]{GetParam()};

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

TEST(CpuArithmeticTest, SubSubtractsOperands) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, 7),
        rv32::addi(Reg::t1, Reg::zero, 2),
        rv32::sub(Reg::a0, Reg::t0, Reg::t1),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 5u, guest_addr(12));
}

TEST(CpuArithmeticTest, SubHandlesNegativeResult) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, 3),
        rv32::addi(Reg::t1, Reg::zero, 7),
        rv32::sub(Reg::a0, Reg::t0, Reg::t1),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 0xffff'fffcu, guest_addr(12));
}

TEST(CpuArithmeticTest, BitwiseXorRegisterOpsOperatePerBit) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, 0x0f0),
        rv32::addi(Reg::t1, Reg::zero, 0x033),
        rv32::xor_(Reg::a0, Reg::t0, Reg::t1),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 0x0c3u, guest_addr(12));
}

TEST(CpuArithmeticTest, BitwiseOrRegisterOpsOperatePerBit) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, 0x0f0),
        rv32::addi(Reg::t1, Reg::zero, 0x033),
        rv32::or_(Reg::a0, Reg::t0, Reg::t1),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 0x0f3u, guest_addr(12));
}

TEST(CpuArithmeticTest, BitwiseAndRegisterOpsOperatePerBit) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, 0x0f0),
        rv32::addi(Reg::t1, Reg::zero, 0x033),
        rv32::and_(Reg::a0, Reg::t0, Reg::t1),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 0x030u, guest_addr(12));
}

TEST(CpuArithmeticTest, BitwiseRegisterOpsAllTogether) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, 0x0f0),
        rv32::addi(Reg::t1, Reg::zero, 0x033),
        rv32::and_(Reg::a1, Reg::t0, Reg::t1),
        rv32::or_(Reg::a2, Reg::t0, Reg::t1),
        rv32::xor_(Reg::a0, Reg::a1, Reg::a2),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 0x0c3u, guest_addr(20));
}

TEST(CpuArithmeticTest, ShiftLeftUsesLowFiveBitsOfRegisterOperand) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, 1),
        rv32::addi(Reg::t1, Reg::zero, 35),
        rv32::sll(Reg::a0, Reg::t0, Reg::t1),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 8u, guest_addr(12));
}

TEST(CpuArithmeticTest, ShiftRightImmediateVariantsDistinguishLogicalAndArithmetic) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::lui(Reg::t0, 0x8000'0000u),
        rv32::srli(Reg::a1, Reg::t0, 31),
        rv32::srai(Reg::a2, Reg::t0, 31),
        rv32::xor_(Reg::a0, Reg::a1, Reg::a2),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 0xffff'fffeu, guest_addr(16));
}

TEST(CpuArithmeticTest, ShiftRightRegisterVariantsDistinguishLogicalAndArithmetic) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::lui(Reg::t0, 0x8000'0000u),
        rv32::addi(Reg::t1, Reg::zero, 31),
        rv32::srl(Reg::a1, Reg::t0, Reg::t1),
        rv32::sra(Reg::a2, Reg::t0, Reg::t1),
        rv32::xor_(Reg::a0, Reg::a1, Reg::a2),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 0xffff'fffeu, guest_addr(20));
}

TEST(CpuArithmeticTest, SignedAndUnsignedSetLessThanUseDifferentOrdering) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, -1),
        rv32::addi(Reg::t1, Reg::zero, 1),
        rv32::slt(Reg::a1, Reg::t0, Reg::t1),
        rv32::sltu(Reg::a2, Reg::t1, Reg::t0),
        rv32::add(Reg::a0, Reg::a1, Reg::a2),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 2u, guest_addr(20));
}

TEST(CpuArithmeticTest, SltiWithPositiveAndNegativeImmediateValues) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, -5),
        rv32::slti(Reg::a0, Reg::t0, -10),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 0u, guest_addr(8));
}

TEST(CpuArithmeticTest, SltiWithNegativeRegisterPositiveImmediate) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, -5),
        rv32::slti(Reg::a0, Reg::t0, 0),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 1u, guest_addr(8));
}

TEST(CpuArithmeticTest, SltiuAlwaysUsesUnsignedComparison) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, -1),
        rv32::sltiu(Reg::a0, Reg::t0, 0x7fff),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 0u, guest_addr(8));
}

TEST(CpuArithmeticTest, SltiuWithSmallUnsignedComparison) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, 5),
        rv32::sltiu(Reg::a0, Reg::t0, 10),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 1u, guest_addr(8));
}

TEST(CpuArithmeticTest, XoriSignExtendsThenXors) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, 0x0ff),
        rv32::xori(Reg::a0, Reg::t0, -1),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 0xffff'ff00u, guest_addr(8));
}

TEST(CpuArithmeticTest, OriSetsBits) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, 0x0f0),
        rv32::ori(Reg::a0, Reg::t0, 0x00f),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 0x0ffu, guest_addr(8));
}

TEST(CpuArithmeticTest, AndiClearsBits) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, 0x0ff),
        rv32::andi(Reg::a0, Reg::t0, 0x0f0),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 0x0f0u, guest_addr(8));
}

TEST(CpuArithmeticTest, SlliLeftShiftMultiplePositions) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, 0x123),
        rv32::slli(Reg::a0, Reg::t0, 8),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 0x12300u, guest_addr(8));
}

TEST(CpuArithmeticTest, SrlShiftByFiveBitsOfRegister) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::lui(Reg::t0, 0x8000'0000u),
        rv32::addi(Reg::t1, Reg::zero, 1),
        rv32::srl(Reg::a0, Reg::t0, Reg::t1),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 0x4000'0000u, guest_addr(12));
}

TEST(CpuArithmeticTest, ChainedSraiPreservesSign) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::lui(Reg::t0, 0x8000'0000u),
        rv32::srai(Reg::a0, Reg::t0, 16),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 0xffff'8000u, guest_addr(8));
}

TEST(CpuArithmeticTest, SltWithEqualRegistersReturnsZero) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, 42),
        rv32::slt(Reg::a0, Reg::t0, Reg::t0),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 0u, guest_addr(8));
}

TEST(CpuArithmeticTest, SltuWithEqualRegistersReturnsZero) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, 42),
        rv32::sltu(Reg::a0, Reg::t0, Reg::t0),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 0u, guest_addr(8));
}

TEST(CpuArithmeticTest, SltWithZeroSourceReturnsZero) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::slt(Reg::a0, Reg::zero, Reg::zero),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 0u, guest_addr(4));
}

TEST(CpuArithmeticTest, RegisterShiftAmountsUseOnlyLowFiveBitsAcrossAllVariants) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, 1),
        rv32::addi(Reg::t1, Reg::zero, 0x3f),
        rv32::sll(Reg::a1, Reg::t0, Reg::t1),
        rv32::lui(Reg::t2, 0x8000'0000u),
        rv32::srl(Reg::a2, Reg::t2, Reg::t1),
        rv32::sra(Reg::a3, Reg::t2, Reg::t1),
        rv32::add(Reg::a0, Reg::a1, Reg::a2),
        rv32::add(Reg::a0, Reg::a0, Reg::a3),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(80), 0x8000'0000u, guest_addr(32));
    expect_gpr(cpu, rv32::reg_bits(Reg::a1), 0x8000'0000u);
    expect_gpr(cpu, rv32::reg_bits(Reg::a2), 0x0000'0001u);
    expect_gpr(cpu, rv32::reg_bits(Reg::a3), 0xffff'ffffu);
}

TEST(CpuArithmeticTest, SetLessThanCoversMinIntMaxIntAndUnsignedOrdering) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::lui(Reg::t0, 0x8000'0000u),
        rv32::addi(Reg::t1, Reg::zero, -1),
        rv32::slt(Reg::a1, Reg::t0, Reg::t1),
        rv32::slt(Reg::a2, Reg::t1, Reg::t0),
        rv32::sltu(Reg::a3, Reg::t0, Reg::t1),
        rv32::sltu(Reg::a4, Reg::t1, Reg::t0),
        rv32::add(Reg::a0, Reg::a1, Reg::a2),
        rv32::add(Reg::a0, Reg::a0, Reg::a3),
        rv32::add(Reg::a0, Reg::a0, Reg::a4),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(80), 2u, guest_addr(36));
    expect_gpr(cpu, rv32::reg_bits(Reg::a1), 1u);
    expect_gpr(cpu, rv32::reg_bits(Reg::a2), 0u);
    expect_gpr(cpu, rv32::reg_bits(Reg::a3), 1u);
    expect_gpr(cpu, rv32::reg_bits(Reg::a4), 0u);
}

TEST(CpuArithmeticTest, ImmediateLogicalOpsUseSignExtendedTwelveBitOperands) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, 0x55),
        rv32::ori(Reg::a1, Reg::t0, -0x100),
        rv32::andi(Reg::a2, Reg::a1, 0x0ff),
        rv32::xori(Reg::a3, Reg::a2, -1),
        rv32::add(Reg::a0, Reg::a1, Reg::a2),
        rv32::add(Reg::a0, Reg::a0, Reg::a3),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(64), 0xffff'ff54u, guest_addr(24));
    expect_gpr(cpu, rv32::reg_bits(Reg::a1), 0xffff'ff55u);
    expect_gpr(cpu, rv32::reg_bits(Reg::a2), 0x55u);
    expect_gpr(cpu, rv32::reg_bits(Reg::a3), 0xffff'ffaau);
}

TEST(CpuArithmeticTest, AddSubWrapAroundAtSignedBoundaries) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::lui(Reg::t0, 0x8000'0000u),
        rv32::addi(Reg::t0, Reg::t0, -1),
        rv32::addi(Reg::t1, Reg::zero, 1),
        rv32::add(Reg::a1, Reg::t0, Reg::t1),
        rv32::sub(Reg::a2, Reg::a1, Reg::t1),
        rv32::xor_(Reg::a0, Reg::a1, Reg::a2),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(64), 0xffff'ffffu, guest_addr(24));
    expect_gpr(cpu, rv32::reg_bits(Reg::a1), 0x8000'0000u);
    expect_gpr(cpu, rv32::reg_bits(Reg::a2), 0x7fff'ffffu);
}

TEST(CpuArithmeticTest, WritesToZeroRegisterFromAllIntegerAluPathsRemainIgnored) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, 0x7f),
        rv32::addi(Reg::t1, Reg::zero, 0x03),
        rv32::add(Reg::zero, Reg::t0, Reg::t1),
        rv32::sub(Reg::zero, Reg::t0, Reg::t1),
        rv32::or_(Reg::zero, Reg::t0, Reg::t1),
        rv32::and_(Reg::zero, Reg::t0, Reg::t1),
        rv32::xor_(Reg::zero, Reg::t0, Reg::t1),
        rv32::sll(Reg::zero, Reg::t0, Reg::t1),
        rv32::srl(Reg::zero, Reg::t0, Reg::t1),
        rv32::sra(Reg::zero, Reg::t0, Reg::t1),
        rv32::slt(Reg::zero, Reg::t0, Reg::t1),
        rv32::sltu(Reg::zero, Reg::t0, Reg::t1),
        rv32::addi(Reg::a0, Reg::zero, 0),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(128), 0u, guest_addr(52));
    expect_gpr(cpu, rv32::reg_bits(Reg::zero), 0u);
}

}  // namespace
}  // namespace npc::test
