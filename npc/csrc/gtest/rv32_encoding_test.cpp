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

TEST(Rv32EncodingTest, LoadHalfwordVariantsRoundTripCorrectly) {
    constexpr auto lh_inst = rv32::lh(Reg::a0, Reg::t0, 4);
    constexpr auto lhu_inst = rv32::lhu(Reg::a0, Reg::t0, -2);

    EXPECT_TRUE(rv32::instruction_has_opcode(lh_inst, rv32::Opcode::load));
    EXPECT_EQ(rv32::bits(lh_inst, 14, 12), 0b001u);
    EXPECT_EQ(rv32::bits(lh_inst, 11, 7), rv32::reg_bits(Reg::a0));
    EXPECT_EQ(decode_i_imm(lh_inst), 4);

    EXPECT_TRUE(rv32::instruction_has_opcode(lhu_inst, rv32::Opcode::load));
    EXPECT_EQ(rv32::bits(lhu_inst, 14, 12), 0b101u);
    EXPECT_EQ(rv32::bits(lhu_inst, 11, 7), rv32::reg_bits(Reg::a0));
    EXPECT_EQ(decode_i_imm(lhu_inst), -2);
}

TEST(Rv32EncodingTest, StoreHalfwordEncodedAsSType) {
    constexpr auto inst = rv32::sh(Reg::t1, Reg::t0, -4);

    EXPECT_TRUE(rv32::instruction_has_opcode(inst, rv32::Opcode::store));
    EXPECT_EQ(rv32::bits(inst, 14, 12), 0b001u);
    EXPECT_EQ(rv32::bits(inst, 24, 20), rv32::reg_bits(Reg::t1));
    EXPECT_EQ(rv32::bits(inst, 19, 15), rv32::reg_bits(Reg::t0));
    EXPECT_EQ(decode_s_imm(inst), -4);
}

TEST(Rv32EncodingTest, BranchSubOpcodeFieldsCorrect) {
    EXPECT_EQ(rv32::bits(rv32::bne(Reg::t0, Reg::t1, 8), 14, 12), 0b001u);
    EXPECT_EQ(rv32::bits(rv32::blt(Reg::t0, Reg::t1, 8), 14, 12), 0b100u);
    EXPECT_EQ(rv32::bits(rv32::bge(Reg::t0, Reg::t1, 8), 14, 12), 0b101u);
    EXPECT_EQ(rv32::bits(rv32::bltu(Reg::t0, Reg::t1, 8), 14, 12), 0b110u);
    EXPECT_EQ(rv32::bits(rv32::bgeu(Reg::t0, Reg::t1, 8), 14, 12), 0b111u);
}

TEST(Rv32EncodingTest, ImmediateALUOpcodeFieldsCorrect) {
    EXPECT_EQ(rv32::bits(rv32::slti(Reg::a0, Reg::t0, 0), 14, 12), 0b010u);
    EXPECT_EQ(rv32::bits(rv32::sltiu(Reg::a0, Reg::t0, 0), 14, 12), 0b011u);
    EXPECT_EQ(rv32::bits(rv32::xori(Reg::a0, Reg::t0, 0), 14, 12), 0b100u);
    EXPECT_EQ(rv32::bits(rv32::ori(Reg::a0, Reg::t0, 0), 14, 12), 0b110u);
    EXPECT_EQ(rv32::bits(rv32::andi(Reg::a0, Reg::t0, 0), 14, 12), 0b111u);
    EXPECT_EQ(rv32::bits(rv32::slli(Reg::a0, Reg::t0, 0), 14, 12), 0b001u);
}

TEST(Rv32EncodingTest, ShiftImmediateFunct7FieldsDistinguishLogicalAndArithmetic) {
    EXPECT_EQ(rv32::bits(rv32::slli(Reg::a0, Reg::t0, 0), 31, 25), 0u);
    EXPECT_EQ(rv32::bits(rv32::srli(Reg::a0, Reg::t0, 0), 31, 25), 0u);
    EXPECT_EQ(rv32::bits(rv32::srai(Reg::a0, Reg::t0, 0), 31, 25), 0b0100000u);
}

TEST(Rv32EncodingTest, ALURegisterFunct3AndFunct7FieldsCorrect) {
    EXPECT_EQ(rv32::bits(rv32::xor_(Reg::a0, Reg::t0, Reg::t1), 14, 12), 0b100u);
    EXPECT_EQ(rv32::bits(rv32::xor_(Reg::a0, Reg::t0, Reg::t1), 31, 25), 0u);
    EXPECT_EQ(rv32::bits(rv32::or_(Reg::a0, Reg::t0, Reg::t1), 14, 12), 0b110u);
    EXPECT_EQ(rv32::bits(rv32::and_(Reg::a0, Reg::t0, Reg::t1), 14, 12), 0b111u);
    EXPECT_EQ(rv32::bits(rv32::srl(Reg::a0, Reg::t0, Reg::t1), 14, 12), 0b101u);
    EXPECT_EQ(rv32::bits(rv32::srl(Reg::a0, Reg::t0, Reg::t1), 31, 25), 0u);
    EXPECT_EQ(rv32::bits(rv32::sra(Reg::a0, Reg::t0, Reg::t1), 14, 12), 0b101u);
    EXPECT_EQ(rv32::bits(rv32::sra(Reg::a0, Reg::t0, Reg::t1), 31, 25), 0b0100000u);
    EXPECT_EQ(rv32::bits(rv32::sub(Reg::a0, Reg::t0, Reg::t1), 14, 12), 0b000u);
    EXPECT_EQ(rv32::bits(rv32::sub(Reg::a0, Reg::t0, Reg::t1), 31, 25), 0b0100000u);
}

TEST(Rv32EncodingTest, CSREncodingUsesSystemOpcodeWithCorrectFunct3) {
    const auto csr_mstatus = 0x300u;

    EXPECT_TRUE(rv32::instruction_has_opcode(rv32::csrrw(Reg::a0, Reg::t0, csr_mstatus), rv32::Opcode::system));
    EXPECT_EQ(rv32::bits(rv32::csrrw(Reg::a0, Reg::t0, csr_mstatus), 14, 12), 0b001u);
    EXPECT_EQ(rv32::bits(rv32::csrrs(Reg::a0, Reg::t0, csr_mstatus), 14, 12), 0b010u);
    EXPECT_EQ(rv32::bits(rv32::csrrc(Reg::a0, Reg::t0, csr_mstatus), 14, 12), 0b011u);
    EXPECT_EQ(rv32::bits(rv32::csrrwi(Reg::a0, csr_mstatus, 5), 14, 12), 0b101u);
    EXPECT_EQ(rv32::bits(rv32::csrrsi(Reg::a0, csr_mstatus, 5), 14, 12), 0b110u);
    EXPECT_EQ(rv32::bits(rv32::csrrci(Reg::a0, csr_mstatus, 5), 14, 12), 0b111u);

    EXPECT_EQ(rv32::bits(rv32::csrrw(Reg::a0, Reg::t0, csr_mstatus), 31, 20), csr_mstatus);
    EXPECT_EQ(rv32::bits(rv32::csrrwi(Reg::a0, csr_mstatus, 5), 19, 15), 5u);
}

TEST(Rv32EncodingTest, SystemInstructionsProduceCorrectEncodings) {
    EXPECT_EQ(rv32::ecall(), 0x0000'0073u);
    EXPECT_EQ(rv32::mret(), 0x3020'0073u);
    EXPECT_TRUE(rv32::instruction_has_opcode(rv32::ecall(), rv32::Opcode::system));
    EXPECT_TRUE(rv32::instruction_has_opcode(rv32::mret(), rv32::Opcode::system));
}

TEST(Rv32EncodingTest, ShamtMaskedToFiveBitsInShiftImmediates) {
    EXPECT_EQ(rv32::slli(Reg::a0, Reg::t0, 3), rv32::slli(Reg::a0, Reg::t0, 3 + 32));
    EXPECT_EQ(rv32::srli(Reg::a0, Reg::t0, 5), rv32::srli(Reg::a0, Reg::t0, 5 + 64));
    EXPECT_EQ(rv32::srai(Reg::a0, Reg::t0, 7), rv32::srai(Reg::a0, Reg::t0, 7 + 32));
}

TEST(Rv32EncodingTest, STBFunct3FieldIsZero) {
    EXPECT_EQ(rv32::bits(rv32::sb(Reg::t1, Reg::t0, 0), 14, 12), 0b000u);
}

TEST(Rv32EncodingTest, ProgramHelperCreatesArrayOfMachineWords) {
    constexpr auto prog = rv32::program(
        rv32::addi(Reg::a0, Reg::zero, 1),
        rv32::add(Reg::a0, Reg::a0, Reg::a0),
        rv32::ebreak()
    );

    static_assert(prog.size() == 3);
    EXPECT_EQ(prog[0], rv32::addi(Reg::a0, Reg::zero, 1));
    EXPECT_EQ(prog[1], rv32::add(Reg::a0, Reg::a0, Reg::a0));
    EXPECT_EQ(prog[2], rv32::ebreak());
}

}  // namespace
}  // namespace npc::test
