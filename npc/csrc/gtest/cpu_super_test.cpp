#include <array>
#include <cstdint>
#include <random>
#include <vector>

#include <gtest/gtest.h>

#include "cpu_test_utils.hpp"
#include "rv32_encoding.hpp"

namespace npc::test {
namespace {

using rv32::Reg;

constexpr std::uint32_t kDataOffset = 0x400;
constexpr std::size_t kDataBytes = 64;
constexpr int kRandomInstructionCount = 96;

constexpr std::array kWorkingRegs{
    Reg::a1, Reg::a2, Reg::a3, Reg::a4, Reg::a5, Reg::a6, Reg::a7,
    Reg::t1, Reg::t2, Reg::t3, Reg::t4, Reg::t5, Reg::s0, Reg::s1,
};

constexpr std::array kSourceRegs{
    Reg::zero, Reg::a1, Reg::a2, Reg::a3, Reg::a4, Reg::a5, Reg::a6, Reg::a7,
    Reg::t1, Reg::t2, Reg::t3, Reg::t4, Reg::t5, Reg::s0, Reg::s1,
};

struct GeneratedProgram {
    std::vector<std::uint32_t> words;
    std::array<std::uint8_t, kDataBytes> initial_data{};
    std::array<std::uint8_t, kDataBytes> expected_data{};
    std::uint32_t expected_signature{};
};

[[nodiscard]] std::uint32_t &reg_ref(std::array<std::uint32_t, 32> &regs, const Reg reg) {
    return regs[rv32::reg_bits(reg)];
}

void write_reg(std::array<std::uint32_t, 32> &regs, const Reg reg, const std::uint32_t value) {
    if (reg != Reg::zero) {
        reg_ref(regs, reg) = value;
    }
    regs[0] = 0;
}

[[nodiscard]] std::uint32_t read_word(const std::array<std::uint8_t, kDataBytes> &data, const std::size_t offset) {
    return (static_cast<std::uint32_t>(data[offset + 0]) << 0) |
           (static_cast<std::uint32_t>(data[offset + 1]) << 8) |
           (static_cast<std::uint32_t>(data[offset + 2]) << 16) |
           (static_cast<std::uint32_t>(data[offset + 3]) << 24);
}

void write_word(std::array<std::uint8_t, kDataBytes> &data, const std::size_t offset, const std::uint32_t value) {
    data[offset + 0] = static_cast<std::uint8_t>((value >> 0) & 0xffu);
    data[offset + 1] = static_cast<std::uint8_t>((value >> 8) & 0xffu);
    data[offset + 2] = static_cast<std::uint8_t>((value >> 16) & 0xffu);
    data[offset + 3] = static_cast<std::uint8_t>((value >> 24) & 0xffu);
}

template <typename T, std::size_t N>
[[nodiscard]] T pick(std::mt19937 &rng, const std::array<T, N> &items) {
    std::uniform_int_distribution<std::size_t> dist(0, items.size() - 1);
    return items[dist(rng)];
}

[[nodiscard]] std::uint32_t current_guest_pc(const std::vector<std::uint32_t> &program) {
    return guest_addr(static_cast<std::uint32_t>(program.size() * sizeof(std::uint32_t)));
}

GeneratedProgram generate_program(const std::uint32_t seed) {
    std::mt19937 rng{seed};
    std::uniform_int_distribution<int> op_dist(0, 15);
    std::uniform_int_distribution<int> imm_dist(-2048, 2047);
    std::uniform_int_distribution<std::uint32_t> word_dist(0, 0xffff'ffffu);
    std::uniform_int_distribution<std::uint32_t> shamt_dist(0, 31);
    std::uniform_int_distribution<std::size_t> byte_offset_dist(0, kDataBytes - 1);
    std::uniform_int_distribution<std::size_t> word_offset_dist(0, (kDataBytes / 4) - 1);

    GeneratedProgram generated;
    std::array<std::uint32_t, 32> regs{};
    auto expected_data = generated.initial_data;

    for (auto &byte : generated.initial_data) {
        byte = static_cast<std::uint8_t>(word_dist(rng) & 0xffu);
    }
    expected_data = generated.initial_data;

    generated.words.push_back(rv32::auipc(Reg::t0, 0));
    write_reg(regs, Reg::t0, guest_addr(0));
    generated.words.push_back(rv32::addi(Reg::t0, Reg::t0, kDataOffset));
    write_reg(regs, Reg::t0, guest_addr(kDataOffset));

    for (const auto reg : kWorkingRegs) {
        const auto imm = imm_dist(rng);
        generated.words.push_back(rv32::addi(reg, Reg::zero, imm));
        write_reg(regs, reg, static_cast<std::uint32_t>(imm));
    }

    for (int i = 0; i < kRandomInstructionCount; ++i) {
        const auto rd = pick(rng, kWorkingRegs);
        const auto rs1 = pick(rng, kSourceRegs);
        const auto rs2 = pick(rng, kSourceRegs);

        switch (op_dist(rng)) {
        case 0: {
            const auto imm = imm_dist(rng);
            generated.words.push_back(rv32::addi(rd, rs1, imm));
            write_reg(regs, rd, reg_ref(regs, rs1) + static_cast<std::uint32_t>(imm));
            break;
        }
        case 1:
            generated.words.push_back(rv32::add(rd, rs1, rs2));
            write_reg(regs, rd, reg_ref(regs, rs1) + reg_ref(regs, rs2));
            break;
        case 2: {
            const auto upper = word_dist(rng) & 0xffff'f000u;
            generated.words.push_back(rv32::lui(rd, upper));
            write_reg(regs, rd, upper);
            break;
        }
        case 3: {
            const auto upper = word_dist(rng) & 0x000f'f000u;
            const auto pc = current_guest_pc(generated.words);
            generated.words.push_back(rv32::auipc(rd, upper));
            write_reg(regs, rd, pc + upper);
            break;
        }
        case 4: {
            const auto offset = word_offset_dist(rng) * 4u;
            generated.words.push_back(rv32::lw(rd, Reg::t0, static_cast<std::int32_t>(offset)));
            write_reg(regs, rd, read_word(expected_data, offset));
            break;
        }
        case 5: {
            const auto offset = byte_offset_dist(rng);
            generated.words.push_back(rv32::lbu(rd, Reg::t0, static_cast<std::int32_t>(offset)));
            write_reg(regs, rd, expected_data[offset]);
            break;
        }
        case 6: {
            const auto offset = word_offset_dist(rng) * 4u;
            generated.words.push_back(rv32::sw(rs1, Reg::t0, static_cast<std::int32_t>(offset)));
            write_word(expected_data, offset, reg_ref(regs, rs1));
            break;
        }
        case 7: {
            const auto offset = byte_offset_dist(rng);
            generated.words.push_back(rv32::sb(rs1, Reg::t0, static_cast<std::int32_t>(offset)));
            expected_data[offset] = static_cast<std::uint8_t>(reg_ref(regs, rs1) & 0xffu);
            break;
        }
        case 8:
            generated.words.push_back(rv32::sub(rd, rs1, rs2));
            write_reg(regs, rd, reg_ref(regs, rs1) - reg_ref(regs, rs2));
            break;
        case 9:
            generated.words.push_back(rv32::xor_(rd, rs1, rs2));
            write_reg(regs, rd, reg_ref(regs, rs1) ^ reg_ref(regs, rs2));
            break;
        case 10:
            generated.words.push_back(rv32::or_(rd, rs1, rs2));
            write_reg(regs, rd, reg_ref(regs, rs1) | reg_ref(regs, rs2));
            break;
        case 11:
            generated.words.push_back(rv32::and_(rd, rs1, rs2));
            write_reg(regs, rd, reg_ref(regs, rs1) & reg_ref(regs, rs2));
            break;
        case 12: {
            const auto imm = imm_dist(rng);
            generated.words.push_back(rv32::slti(rd, rs1, imm));
            write_reg(regs, rd, static_cast<std::int32_t>(reg_ref(regs, rs1)) < imm ? 1u : 0u);
            break;
        }
        case 13:
            generated.words.push_back(rv32::slt(rd, rs1, rs2));
            write_reg(regs, rd,
                      static_cast<std::int32_t>(reg_ref(regs, rs1)) < static_cast<std::int32_t>(reg_ref(regs, rs2)) ? 1u : 0u);
            break;
        case 14:
            generated.words.push_back(rv32::sltu(rd, rs1, rs2));
            write_reg(regs, rd, reg_ref(regs, rs1) < reg_ref(regs, rs2) ? 1u : 0u);
            break;
        default: {
            const auto shamt = shamt_dist(rng) & 0x1fu;
            generated.words.push_back(rv32::slli(rd, rs1, shamt));
            write_reg(regs, rd, reg_ref(regs, rs1) << shamt);
            break;
        }
        }
    }

    generated.words.push_back(rv32::addi(Reg::a0, Reg::zero, 0));
    write_reg(regs, Reg::a0, 0);
    std::uint32_t signature = 0;

    for (const auto reg : kWorkingRegs) {
        generated.words.push_back(rv32::add(Reg::a0, Reg::a0, reg));
        signature += reg_ref(regs, reg);
        write_reg(regs, Reg::a0, signature);
    }

    for (std::size_t offset = 0; offset < kDataBytes; offset += 4) {
        generated.words.push_back(rv32::lw(Reg::t6, Reg::t0, static_cast<std::int32_t>(offset)));
        write_reg(regs, Reg::t6, read_word(expected_data, offset));
        generated.words.push_back(rv32::add(Reg::a0, Reg::a0, Reg::t6));
        signature += reg_ref(regs, Reg::t6);
        write_reg(regs, Reg::a0, signature);
    }

    generated.expected_signature = signature;
    generated.expected_data = expected_data;
    generated.words.push_back(rv32::ebreak());
    return generated;
}

class CpuRandomReferenceTest : public ::testing::TestWithParam<std::uint32_t> {};

TEST_P(CpuRandomReferenceTest, RandomImplementedInstructionStreamsMatchReferenceModel) {
    const auto generated = generate_program(GetParam());
    ASSERT_LT(generated.words.size() * sizeof(std::uint32_t), kDataOffset);

    CpuHarness cpu;
    for (std::size_t i = 0; i < generated.initial_data.size(); ++i) {
        cpu.write_byte(guest_addr(kDataOffset + static_cast<std::uint32_t>(i)), generated.initial_data[i]);
    }
    cpu.load_program(generated.words);
    cpu.reset();

    const auto result = cpu.run(generated.words.size() + 32);
    expect_halt(result, generated.expected_signature,
                guest_addr(static_cast<std::uint32_t>((generated.words.size() - 1) * sizeof(std::uint32_t))));

    for (std::size_t offset = 0; offset < kDataBytes; offset += 4) {
        EXPECT_EQ(cpu.read_word(guest_addr(kDataOffset + static_cast<std::uint32_t>(offset))),
                  read_word(generated.expected_data, offset))
            << "offset=" << offset;
    }
}

INSTANTIATE_TEST_SUITE_P(
    DeterministicSeeds,
    CpuRandomReferenceTest,
    ::testing::Values(
        0x0000'0001u,
        0x1357'2468u,
        0x1bad'b002u,
        0x3141'5926u,
        0x4e50'4355u,
        0x5eed'0001u,
        0x8000'0000u,
        0xa5a5'5a5au,
        0xc001'd00du,
        0xffff'ffffu,
        0x0d00'deadu,
        0x1234'abcdu,
        0x5555'5555u,
        0xaaaa'aaaau,
        0x1010'1010u,
        0xf0f0'f0f0u,
        0x1111'2222u,
        0x3333'4444u,
        0x7fff'ffffu,
        0x3cc0'ffeeu
    )
);

TEST(CpuSuperTest, StoreByteStormTouchesEveryByteLaneAcrossManyWords) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::auipc(Reg::t0, 0),
        rv32::addi(Reg::t0, Reg::t0, kDataOffset),
        rv32::addi(Reg::t1, Reg::zero, 0x11),
        rv32::sb(Reg::t1, Reg::t0, 0),
        rv32::addi(Reg::t1, Reg::t1, 0x11),
        rv32::sb(Reg::t1, Reg::t0, 1),
        rv32::addi(Reg::t1, Reg::t1, 0x11),
        rv32::sb(Reg::t1, Reg::t0, 2),
        rv32::addi(Reg::t1, Reg::t1, 0x11),
        rv32::sb(Reg::t1, Reg::t0, 3),
        rv32::lw(Reg::a0, Reg::t0, 0),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 0x4433'2211u, guest_addr(44));
    EXPECT_EQ(cpu.read_word(guest_addr(kDataOffset)), 0x4433'2211u);
}

}  // namespace
}  // namespace npc::test
