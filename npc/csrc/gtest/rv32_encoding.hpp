#pragma once

#include <array>
#include <concepts>
#include <cstdint>
#include <span>
#include <utility>

namespace npc::test::rv32 {

enum class Reg : std::uint32_t {
    zero = 0,
    ra = 1,
    sp = 2,
    gp = 3,
    tp = 4,
    t0 = 5,
    t1 = 6,
    t2 = 7,
    s0 = 8,
    s1 = 9,
    a0 = 10,
    a1 = 11,
    a2 = 12,
    a3 = 13,
    a4 = 14,
    a5 = 15,
    a6 = 16,
    a7 = 17,
    s2 = 18,
    s3 = 19,
    s4 = 20,
    s5 = 21,
    s6 = 22,
    s7 = 23,
    s8 = 24,
    s9 = 25,
    s10 = 26,
    s11 = 27,
    t3 = 28,
    t4 = 29,
    t5 = 30,
    t6 = 31,
};

enum class Opcode : std::uint32_t {
    load = 0b0000011,
    immediate = 0b0010011,
    auipc = 0b0010111,
    store = 0b0100011,
    reg = 0b0110011,
    lui = 0b0110111,
    branch = 0b1100011,
    jalr = 0b1100111,
    jal = 0b1101111,
    system = 0b1110011,
};

[[nodiscard]] constexpr std::uint32_t reg_bits(const Reg reg) {
    return std::to_underlying(reg);
}

[[nodiscard]] constexpr std::uint32_t opcode_bits(const Opcode opcode) {
    return std::to_underlying(opcode);
}

[[nodiscard]] constexpr std::uint32_t bits(const std::uint32_t value, const unsigned high, const unsigned low) {
    const auto width = high - low + 1u;
    const auto mask = (std::uint32_t{1} << width) - 1u;
    return (value >> low) & mask;
}

[[nodiscard]] constexpr std::uint32_t encode_i(
    const std::int32_t imm,
    const Reg rs1,
    const std::uint32_t funct3,
    const Reg rd,
    const Opcode opcode
) {
    return ((static_cast<std::uint32_t>(imm) & 0xfffu) << 20) |
           (reg_bits(rs1) << 15) |
           ((funct3 & 0x7u) << 12) |
           (reg_bits(rd) << 7) |
           opcode_bits(opcode);
}

[[nodiscard]] constexpr std::uint32_t encode_r(
    const std::uint32_t funct7,
    const Reg rs2,
    const Reg rs1,
    const std::uint32_t funct3,
    const Reg rd,
    const Opcode opcode
) {
    return ((funct7 & 0x7fu) << 25) |
           (reg_bits(rs2) << 20) |
           (reg_bits(rs1) << 15) |
           ((funct3 & 0x7u) << 12) |
           (reg_bits(rd) << 7) |
           opcode_bits(opcode);
}

[[nodiscard]] constexpr std::uint32_t encode_s(
    const std::int32_t imm,
    const Reg rs2,
    const Reg rs1,
    const std::uint32_t funct3,
    const Opcode opcode
) {
    const auto uimm = static_cast<std::uint32_t>(imm) & 0xfffu;
    return (((uimm >> 5) & 0x7fu) << 25) |
           (reg_bits(rs2) << 20) |
           (reg_bits(rs1) << 15) |
           ((funct3 & 0x7u) << 12) |
           ((uimm & 0x1fu) << 7) |
           opcode_bits(opcode);
}

[[nodiscard]] constexpr std::uint32_t encode_b(
    const std::int32_t imm,
    const Reg rs2,
    const Reg rs1,
    const std::uint32_t funct3
) {
    const auto uimm = static_cast<std::uint32_t>(imm) & 0x1fffu;
    return (((uimm >> 12) & 0x1u) << 31) |
           (((uimm >> 5) & 0x3fu) << 25) |
           (reg_bits(rs2) << 20) |
           (reg_bits(rs1) << 15) |
           ((funct3 & 0x7u) << 12) |
           (((uimm >> 1) & 0xfu) << 8) |
           (((uimm >> 11) & 0x1u) << 7) |
           opcode_bits(Opcode::branch);
}

[[nodiscard]] constexpr std::uint32_t encode_u(
    const std::uint32_t imm,
    const Reg rd,
    const Opcode opcode
) {
    return (imm & 0xfffff000u) |
           (reg_bits(rd) << 7) |
           opcode_bits(opcode);
}

[[nodiscard]] constexpr std::uint32_t encode_j(
    const std::int32_t imm,
    const Reg rd,
    const Opcode opcode
) {
    const auto uimm = static_cast<std::uint32_t>(imm) & 0x1fffffu;
    return (((uimm >> 20) & 0x1u) << 31) |
           (((uimm >> 1) & 0x3ffu) << 21) |
           (((uimm >> 11) & 0x1u) << 20) |
           (((uimm >> 12) & 0xffu) << 12) |
           (reg_bits(rd) << 7) |
           opcode_bits(opcode);
}

[[nodiscard]] constexpr std::uint32_t addi(const Reg rd, const Reg rs1, const std::int32_t imm) {
    return encode_i(imm, rs1, 0b000, rd, Opcode::immediate);
}

[[nodiscard]] constexpr std::uint32_t add(const Reg rd, const Reg rs1, const Reg rs2) {
    return encode_r(0b0000000, rs2, rs1, 0b000, rd, Opcode::reg);
}

[[nodiscard]] constexpr std::uint32_t lb(const Reg rd, const Reg rs1, const std::int32_t imm) {
    return encode_i(imm, rs1, 0b000, rd, Opcode::load);
}

[[nodiscard]] constexpr std::uint32_t lw(const Reg rd, const Reg rs1, const std::int32_t imm) {
    return encode_i(imm, rs1, 0b010, rd, Opcode::load);
}

[[nodiscard]] constexpr std::uint32_t lbu(const Reg rd, const Reg rs1, const std::int32_t imm) {
    return encode_i(imm, rs1, 0b100, rd, Opcode::load);
}

[[nodiscard]] constexpr std::uint32_t sb(const Reg rs2, const Reg rs1, const std::int32_t imm) {
    return encode_s(imm, rs2, rs1, 0b000, Opcode::store);
}

[[nodiscard]] constexpr std::uint32_t sw(const Reg rs2, const Reg rs1, const std::int32_t imm) {
    return encode_s(imm, rs2, rs1, 0b010, Opcode::store);
}

[[nodiscard]] constexpr std::uint32_t beq(const Reg rs1, const Reg rs2, const std::int32_t imm) {
    return encode_b(imm, rs2, rs1, 0b000);
}

[[nodiscard]] constexpr std::uint32_t lui(const Reg rd, const std::uint32_t imm) {
    return encode_u(imm, rd, Opcode::lui);
}

[[nodiscard]] constexpr std::uint32_t auipc(const Reg rd, const std::uint32_t imm) {
    return encode_u(imm, rd, Opcode::auipc);
}

[[nodiscard]] constexpr std::uint32_t jal(const Reg rd, const std::int32_t imm) {
    return encode_j(imm, rd, Opcode::jal);
}

[[nodiscard]] constexpr std::uint32_t jalr(const Reg rd, const Reg rs1, const std::int32_t imm) {
    return encode_i(imm, rs1, 0b000, rd, Opcode::jalr);
}

[[nodiscard]] constexpr std::uint32_t ret() {
    return jalr(Reg::zero, Reg::ra, 0);
}

[[nodiscard]] constexpr std::uint32_t ebreak() {
    return 0x00100073u;
}

[[nodiscard]] constexpr std::uint32_t nop() {
    return addi(Reg::zero, Reg::zero, 0);
}

template <std::same_as<std::uint32_t>... Word>
[[nodiscard]] constexpr auto program(Word... words) {
    return std::to_array<std::uint32_t>({words...});
}

[[nodiscard]] constexpr bool instruction_has_opcode(const std::uint32_t instruction, const Opcode opcode) {
    return (instruction & 0x7fu) == opcode_bits(opcode);
}

static_assert(addi(Reg::a0, Reg::zero, 42) == 0x02a00513u);
static_assert(addi(Reg::a0, Reg::zero, -1) == 0xfff00513u);
static_assert(add(Reg::a0, Reg::t0, Reg::t1) == 0x00628533u);
static_assert(lw(Reg::a0, Reg::t0, -4) == 0xffc2a503u);
static_assert(sw(Reg::t1, Reg::t0, 12) == 0x0062a623u);
static_assert(jal(Reg::ra, 8) == 0x008000efu);
static_assert(jalr(Reg::zero, Reg::ra, 0) == 0x00008067u);

}  // namespace npc::test::rv32
