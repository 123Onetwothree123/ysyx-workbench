#include <cstdint>

#include <gtest/gtest.h>

#include "rv32_encoding.hpp"

namespace npc::test {
namespace {

using rv32::Reg;

[[nodiscard]] constexpr std::int32_t sign_extend(const std::uint32_t value, const unsigned bit_count) {
    const auto sign_bit = std::uint32_t{1} << (bit_count - 1u);
    return static_cast<std::int32_t>((value ^ sign_bit) - sign_bit);
}

[[nodiscard]] constexpr std::int32_t decode_i_imm(const std::uint32_t instruction) {
    return sign_extend(rv32::bits(instruction, 31, 20), 12);
}

[[nodiscard]] constexpr std::int32_t decode_s_imm(const std::uint32_t instruction) {
    const auto high = rv32::bits(instruction, 31, 25);
    const auto low = rv32::bits(instruction, 11, 7);
    return sign_extend((high << 5) | low, 12);
}

[[nodiscard]] constexpr std::int32_t decode_b_imm(const std::uint32_t instruction) {
    const auto imm = (rv32::bits(instruction, 31, 31) << 12) |
                     (rv32::bits(instruction, 7, 7) << 11) |
                     (rv32::bits(instruction, 30, 25) << 5) |
                     (rv32::bits(instruction, 11, 8) << 1);
    return sign_extend(imm, 13);
}

[[nodiscard]] constexpr std::int32_t decode_j_imm(const std::uint32_t instruction) {
    const auto imm = (rv32::bits(instruction, 31, 31) << 20) |
                     (rv32::bits(instruction, 19, 12) << 12) |
                     (rv32::bits(instruction, 20, 20) << 11) |
                     (rv32::bits(instruction, 30, 21) << 1);
    return sign_extend(imm, 21);
}

TEST(Rv32EncodingTest, RegisterNumbersMatchRiscvAbi) {
    EXPECT_EQ(rv32::reg_bits(Reg::zero), 0u);
    EXPECT_EQ(rv32::reg_bits(Reg::ra), 1u);
    EXPECT_EQ(rv32::reg_bits(Reg::t0), 5u);
    EXPECT_EQ(rv32::reg_bits(Reg::a0), 10u);
    EXPECT_EQ(rv32::reg_bits(Reg::t6), 31u);
}

TEST(Rv32EncodingTest, ITypeFieldsRoundTrip) {
    constexpr auto inst = rv32::addi(Reg::a0, Reg::t0, -17);

    EXPECT_TRUE(rv32::instruction_has_opcode(inst, rv32::Opcode::immediate));
    EXPECT_EQ(rv32::bits(inst, 11, 7), rv32::reg_bits(Reg::a0));
    EXPECT_EQ(rv32::bits(inst, 19, 15), rv32::reg_bits(Reg::t0));
    EXPECT_EQ(rv32::bits(inst, 14, 12), 0u);
    EXPECT_EQ(decode_i_imm(inst), -17);
}

TEST(Rv32EncodingTest, RTypeFieldsRoundTrip) {
    constexpr auto inst = rv32::add(Reg::a0, Reg::t0, Reg::t1);

    EXPECT_TRUE(rv32::instruction_has_opcode(inst, rv32::Opcode::reg));
    EXPECT_EQ(rv32::bits(inst, 11, 7), rv32::reg_bits(Reg::a0));
    EXPECT_EQ(rv32::bits(inst, 19, 15), rv32::reg_bits(Reg::t0));
    EXPECT_EQ(rv32::bits(inst, 24, 20), rv32::reg_bits(Reg::t1));
    EXPECT_EQ(rv32::bits(inst, 14, 12), 0u);
    EXPECT_EQ(rv32::bits(inst, 31, 25), 0u);
}

TEST(Rv32EncodingTest, STypeFieldsRoundTrip) {
    constexpr auto inst = rv32::sw(Reg::t1, Reg::t0, -32);

    EXPECT_TRUE(rv32::instruction_has_opcode(inst, rv32::Opcode::store));
    EXPECT_EQ(rv32::bits(inst, 24, 20), rv32::reg_bits(Reg::t1));
    EXPECT_EQ(rv32::bits(inst, 19, 15), rv32::reg_bits(Reg::t0));
    EXPECT_EQ(rv32::bits(inst, 14, 12), 0b010u);
    EXPECT_EQ(decode_s_imm(inst), -32);
}

TEST(Rv32EncodingTest, BTypeAndJTypeImmediateBitsRoundTrip) {
    constexpr auto branch = rv32::beq(Reg::t0, Reg::t1, -16);
    constexpr auto jump = rv32::jal(Reg::ra, -2048);

    EXPECT_TRUE(rv32::instruction_has_opcode(branch, rv32::Opcode::branch));
    EXPECT_EQ(decode_b_imm(branch), -16);
    EXPECT_TRUE(rv32::instruction_has_opcode(jump, rv32::Opcode::jal));
    EXPECT_EQ(decode_j_imm(jump), -2048);
}

struct ImmediateRoundTripCase {
    std::int32_t immediate;
};

class Rv32IImmediateEncodingTest : public ::testing::TestWithParam<ImmediateRoundTripCase> {};

TEST_P(Rv32IImmediateEncodingTest, AddiImmediateEdgesRoundTripThroughInstructionBits) {
    const auto [immediate] = GetParam();
    const auto instruction = rv32::addi(Reg::a0, Reg::a1, immediate);

    EXPECT_EQ(decode_i_imm(instruction), immediate);
    EXPECT_EQ(rv32::bits(instruction, 19, 15), rv32::reg_bits(Reg::a1));
    EXPECT_EQ(rv32::bits(instruction, 11, 7), rv32::reg_bits(Reg::a0));
}

INSTANTIATE_TEST_SUITE_P(
    TwelveBitEdges,
    Rv32IImmediateEncodingTest,
    ::testing::Values(
        ImmediateRoundTripCase{.immediate = -2048},
        ImmediateRoundTripCase{.immediate = -1},
        ImmediateRoundTripCase{.immediate = 0},
        ImmediateRoundTripCase{.immediate = 1},
        ImmediateRoundTripCase{.immediate = 2047}
    )
);

class Rv32SImmediateEncodingTest : public ::testing::TestWithParam<ImmediateRoundTripCase> {};

TEST_P(Rv32SImmediateEncodingTest, StoreImmediateEdgesRoundTripThroughSplitFields) {
    const auto [immediate] = GetParam();
    const auto instruction = rv32::sw(Reg::t1, Reg::t0, immediate);

    EXPECT_EQ(decode_s_imm(instruction), immediate);
    EXPECT_EQ(rv32::bits(instruction, 24, 20), rv32::reg_bits(Reg::t1));
    EXPECT_EQ(rv32::bits(instruction, 19, 15), rv32::reg_bits(Reg::t0));
}

INSTANTIATE_TEST_SUITE_P(
    TwelveBitEdges,
    Rv32SImmediateEncodingTest,
    ::testing::Values(
        ImmediateRoundTripCase{.immediate = -2048},
        ImmediateRoundTripCase{.immediate = -1},
        ImmediateRoundTripCase{.immediate = 0},
        ImmediateRoundTripCase{.immediate = 1},
        ImmediateRoundTripCase{.immediate = 2047}
    )
);

class Rv32BImmediateEncodingTest : public ::testing::TestWithParam<ImmediateRoundTripCase> {};

TEST_P(Rv32BImmediateEncodingTest, BranchImmediateEdgesRoundTripThroughScatteredBits) {
    const auto [immediate] = GetParam();
    const auto instruction = rv32::beq(Reg::t0, Reg::t1, immediate);

    EXPECT_EQ(decode_b_imm(instruction), immediate);
    EXPECT_EQ(rv32::bits(instruction, 24, 20), rv32::reg_bits(Reg::t1));
    EXPECT_EQ(rv32::bits(instruction, 19, 15), rv32::reg_bits(Reg::t0));
}

INSTANTIATE_TEST_SUITE_P(
    ThirteenBitEvenEdges,
    Rv32BImmediateEncodingTest,
    ::testing::Values(
        ImmediateRoundTripCase{.immediate = -4096},
        ImmediateRoundTripCase{.immediate = -2},
        ImmediateRoundTripCase{.immediate = 0},
        ImmediateRoundTripCase{.immediate = 2},
        ImmediateRoundTripCase{.immediate = 4094}
    )
);

class Rv32JImmediateEncodingTest : public ::testing::TestWithParam<ImmediateRoundTripCase> {};

TEST_P(Rv32JImmediateEncodingTest, JumpImmediateEdgesRoundTripThroughScatteredBits) {
    const auto [immediate] = GetParam();
    const auto instruction = rv32::jal(Reg::ra, immediate);

    EXPECT_EQ(decode_j_imm(instruction), immediate);
    EXPECT_EQ(rv32::bits(instruction, 11, 7), rv32::reg_bits(Reg::ra));
}

INSTANTIATE_TEST_SUITE_P(
    TwentyOneBitEvenEdges,
    Rv32JImmediateEncodingTest,
    ::testing::Values(
        ImmediateRoundTripCase{.immediate = -1'048'576},
        ImmediateRoundTripCase{.immediate = -2},
        ImmediateRoundTripCase{.immediate = 0},
        ImmediateRoundTripCase{.immediate = 2},
        ImmediateRoundTripCase{.immediate = 1'048'574}
    )
);

TEST(Rv32EncodingTest, UTypeHelpersMaskOffLowImmediateBits) {
    constexpr auto lui = rv32::lui(Reg::a0, 0x1234'5abcu);
    constexpr auto auipc = rv32::auipc(Reg::a1, 0xffff'f7ffu);

    EXPECT_TRUE(rv32::instruction_has_opcode(lui, rv32::Opcode::lui));
    EXPECT_EQ(rv32::bits(lui, 31, 12) << 12, 0x1234'5000u);
    EXPECT_EQ(rv32::bits(lui, 11, 7), rv32::reg_bits(Reg::a0));
    EXPECT_TRUE(rv32::instruction_has_opcode(auipc, rv32::Opcode::auipc));
    EXPECT_EQ(rv32::bits(auipc, 31, 12) << 12, 0xffff'f000u);
    EXPECT_EQ(rv32::bits(auipc, 11, 7), rv32::reg_bits(Reg::a1));
}

TEST(Rv32EncodingTest, NamedHelpersProduceCanonicalMachineWords) {
    EXPECT_EQ(rv32::addi(Reg::a0, Reg::zero, -2048), 0x8000'0513u);
    EXPECT_EQ(rv32::add(Reg::a0, Reg::t0, Reg::t1), 0x0062'8533u);
    EXPECT_EQ(rv32::lui(Reg::a0, 0x1234'5000u), 0x1234'5537u);
    EXPECT_EQ(rv32::auipc(Reg::a0, 0x0000'1000u), 0x0000'1517u);
    EXPECT_EQ(rv32::lw(Reg::a0, Reg::t0, -4), 0xffc2'a503u);
    EXPECT_EQ(rv32::lbu(Reg::a0, Reg::t0, 3), 0x0032'c503u);
    EXPECT_EQ(rv32::sb(Reg::t1, Reg::t0, 3), 0x0062'81a3u);
    EXPECT_EQ(rv32::sw(Reg::t1, Reg::t0, 12), 0x0062'a623u);
    EXPECT_EQ(rv32::jalr(Reg::ra, Reg::t0, 16), 0x0102'80e7u);
    EXPECT_EQ(rv32::ebreak(), 0x0010'0073u);
    EXPECT_EQ(rv32::nop(), 0x0000'0013u);
    EXPECT_EQ(rv32::ret(), 0x0000'8067u);
}

}  // namespace
}  // namespace npc::test
