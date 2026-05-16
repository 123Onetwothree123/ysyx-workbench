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
    const auto width{high - low + 1u};
    const auto mask{(std::uint32_t{1} << width) - 1u};
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
    const auto uimm{static_cast<std::uint32_t>(imm) & 0xfffu};
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
    const auto uimm{static_cast<std::uint32_t>(imm) & 0x1fffu};
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
    const auto uimm{static_cast<std::uint32_t>(imm) & 0x1fffffu};
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

[[nodiscard]] constexpr std::uint32_t lh(const Reg rd, const Reg rs1, const std::int32_t imm) {
    return encode_i(imm, rs1, 0b001, rd, Opcode::load);
}

[[nodiscard]] constexpr std::uint32_t lw(const Reg rd, const Reg rs1, const std::int32_t imm) {
    return encode_i(imm, rs1, 0b010, rd, Opcode::load);
}

[[nodiscard]] constexpr std::uint32_t lbu(const Reg rd, const Reg rs1, const std::int32_t imm) {
    return encode_i(imm, rs1, 0b100, rd, Opcode::load);
}

[[nodiscard]] constexpr std::uint32_t lhu(const Reg rd, const Reg rs1, const std::int32_t imm) {
    return encode_i(imm, rs1, 0b101, rd, Opcode::load);
}

[[nodiscard]] constexpr std::uint32_t sb(const Reg rs2, const Reg rs1, const std::int32_t imm) {
    return encode_s(imm, rs2, rs1, 0b000, Opcode::store);
}

[[nodiscard]] constexpr std::uint32_t sh(const Reg rs2, const Reg rs1, const std::int32_t imm) {
    return encode_s(imm, rs2, rs1, 0b001, Opcode::store);
}

[[nodiscard]] constexpr std::uint32_t sw(const Reg rs2, const Reg rs1, const std::int32_t imm) {
    return encode_s(imm, rs2, rs1, 0b010, Opcode::store);
}

[[nodiscard]] constexpr std::uint32_t beq(const Reg rs1, const Reg rs2, const std::int32_t imm) {
    return encode_b(imm, rs2, rs1, 0b000);
}

[[nodiscard]] constexpr std::uint32_t bne(const Reg rs1, const Reg rs2, const std::int32_t imm) {
    return encode_b(imm, rs2, rs1, 0b001);
}

[[nodiscard]] constexpr std::uint32_t blt(const Reg rs1, const Reg rs2, const std::int32_t imm) {
    return encode_b(imm, rs2, rs1, 0b100);
}

[[nodiscard]] constexpr std::uint32_t bge(const Reg rs1, const Reg rs2, const std::int32_t imm) {
    return encode_b(imm, rs2, rs1, 0b101);
}

[[nodiscard]] constexpr std::uint32_t bltu(const Reg rs1, const Reg rs2, const std::int32_t imm) {
    return encode_b(imm, rs2, rs1, 0b110);
}

[[nodiscard]] constexpr std::uint32_t bgeu(const Reg rs1, const Reg rs2, const std::int32_t imm) {
    return encode_b(imm, rs2, rs1, 0b111);
}

[[nodiscard]] constexpr std::uint32_t slti(const Reg rd, const Reg rs1, const std::int32_t imm) {
    return encode_i(imm, rs1, 0b010, rd, Opcode::immediate);
}

[[nodiscard]] constexpr std::uint32_t sltiu(const Reg rd, const Reg rs1, const std::int32_t imm) {
    return encode_i(imm, rs1, 0b011, rd, Opcode::immediate);
}

[[nodiscard]] constexpr std::uint32_t xori(const Reg rd, const Reg rs1, const std::int32_t imm) {
    return encode_i(imm, rs1, 0b100, rd, Opcode::immediate);
}

[[nodiscard]] constexpr std::uint32_t ori(const Reg rd, const Reg rs1, const std::int32_t imm) {
    return encode_i(imm, rs1, 0b110, rd, Opcode::immediate);
}

[[nodiscard]] constexpr std::uint32_t andi(const Reg rd, const Reg rs1, const std::int32_t imm) {
    return encode_i(imm, rs1, 0b111, rd, Opcode::immediate);
}

[[nodiscard]] constexpr std::uint32_t slli(const Reg rd, const Reg rs1, const std::uint32_t shamt) {
    return encode_i(static_cast<std::int32_t>(shamt & 0x1fu), rs1, 0b001, rd, Opcode::immediate);
}

[[nodiscard]] constexpr std::uint32_t srli(const Reg rd, const Reg rs1, const std::uint32_t shamt) {
    return encode_i(static_cast<std::int32_t>(shamt & 0x1fu), rs1, 0b101, rd, Opcode::immediate);
}

[[nodiscard]] constexpr std::uint32_t srai(const Reg rd, const Reg rs1, const std::uint32_t shamt) {
    return encode_i(static_cast<std::int32_t>((0b0100000u << 5) | (shamt & 0x1fu)),
                    rs1, 0b101, rd, Opcode::immediate);
}

[[nodiscard]] constexpr std::uint32_t xor_(const Reg rd, const Reg rs1, const Reg rs2) {
    return encode_r(0b0000000, rs2, rs1, 0b100, rd, Opcode::reg);
}

[[nodiscard]] constexpr std::uint32_t or_(const Reg rd, const Reg rs1, const Reg rs2) {
    return encode_r(0b0000000, rs2, rs1, 0b110, rd, Opcode::reg);
}

[[nodiscard]] constexpr std::uint32_t and_(const Reg rd, const Reg rs1, const Reg rs2) {
    return encode_r(0b0000000, rs2, rs1, 0b111, rd, Opcode::reg);
}

[[nodiscard]] constexpr std::uint32_t sll(const Reg rd, const Reg rs1, const Reg rs2) {
    return encode_r(0b0000000, rs2, rs1, 0b001, rd, Opcode::reg);
}

[[nodiscard]] constexpr std::uint32_t srl(const Reg rd, const Reg rs1, const Reg rs2) {
    return encode_r(0b0000000, rs2, rs1, 0b101, rd, Opcode::reg);
}

[[nodiscard]] constexpr std::uint32_t sub(const Reg rd, const Reg rs1, const Reg rs2) {
    return encode_r(0b0100000, rs2, rs1, 0b000, rd, Opcode::reg);
}

[[nodiscard]] constexpr std::uint32_t sra(const Reg rd, const Reg rs1, const Reg rs2) {
    return encode_r(0b0100000, rs2, rs1, 0b101, rd, Opcode::reg);
}

[[nodiscard]] constexpr std::uint32_t slt(const Reg rd, const Reg rs1, const Reg rs2) {
    return encode_r(0b0000000, rs2, rs1, 0b010, rd, Opcode::reg);
}

[[nodiscard]] constexpr std::uint32_t sltu(const Reg rd, const Reg rs1, const Reg rs2) {
    return encode_r(0b0000000, rs2, rs1, 0b011, rd, Opcode::reg);
}

[[nodiscard]] constexpr std::uint32_t csrrw(const Reg rd, const Reg rs1, const std::uint32_t csr_addr) {
    return encode_i(static_cast<std::int32_t>(csr_addr & 0xfffu), rs1, 0b001, rd, Opcode::system);
}

[[nodiscard]] constexpr std::uint32_t csrrs(const Reg rd, const Reg rs1, const std::uint32_t csr_addr) {
    return encode_i(static_cast<std::int32_t>(csr_addr & 0xfffu), rs1, 0b010, rd, Opcode::system);
}

[[nodiscard]] constexpr std::uint32_t csrrc(const Reg rd, const Reg rs1, const std::uint32_t csr_addr) {
    return encode_i(static_cast<std::int32_t>(csr_addr & 0xfffu), rs1, 0b011, rd, Opcode::system);
}

[[nodiscard]] constexpr std::uint32_t csrrwi(const Reg rd, const std::uint32_t csr_addr, const std::uint32_t uimm) {
    return encode_i(static_cast<std::int32_t>(csr_addr & 0xfffu), static_cast<Reg>(uimm & 0x1fu),
                    0b101, rd, Opcode::system);
}

[[nodiscard]] constexpr std::uint32_t csrrsi(const Reg rd, const std::uint32_t csr_addr, const std::uint32_t uimm) {
    return encode_i(static_cast<std::int32_t>(csr_addr & 0xfffu), static_cast<Reg>(uimm & 0x1fu),
                    0b110, rd, Opcode::system);
}

[[nodiscard]] constexpr std::uint32_t csrrci(const Reg rd, const std::uint32_t csr_addr, const std::uint32_t uimm) {
    return encode_i(static_cast<std::int32_t>(csr_addr & 0xfffu), static_cast<Reg>(uimm & 0x1fu),
                    0b111, rd, Opcode::system);
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

[[nodiscard]] constexpr std::uint32_t ecall() {
    return 0x00000073u;
}

[[nodiscard]] constexpr std::uint32_t mret() {
    return 0x30200073u;
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
static_assert(lh(Reg::a0, Reg::t0, 4) == 0x00429503u);
static_assert(lhu(Reg::a0, Reg::t0, -2) == 0xffe2d503u);
static_assert(sh(Reg::t1, Reg::t0, 0) == 0x00629023u);
static_assert(bne(Reg::t0, Reg::t1, 8) == 0x00629463u);
static_assert(blt(Reg::t0, Reg::t1, -4) == 0xfe62cee3u);
static_assert(bge(Reg::t0, Reg::t1, 16) == 0x0062d863u);
static_assert(bltu(Reg::t0, Reg::t1, 0) == 0x0062e063u);
static_assert(bgeu(Reg::t0, Reg::t1, -8) == 0xfe62fce3u);
static_assert(slti(Reg::a0, Reg::t0, -1) == 0xfff2a513u);
static_assert(sltiu(Reg::a0, Reg::t0, 42) == 0x02a2b513u);
static_assert(xori(Reg::a0, Reg::t0, -1) == 0xfff2c513u);
static_assert(ori(Reg::a0, Reg::t0, 0x7ff) == 0x7ff2e513u);
static_assert(andi(Reg::a0, Reg::t0, 0x555) == 0x5552f513u);
static_assert(slli(Reg::a0, Reg::t0, 3) == 0x00329513u);
static_assert(srli(Reg::a0, Reg::t0, 5) == 0x0052d513u);
static_assert(srai(Reg::a0, Reg::t0, 2) == 0x4022d513u);
static_assert(xor_(Reg::a0, Reg::t0, Reg::t1) == 0x0062c533u);
static_assert(or_(Reg::a0, Reg::t0, Reg::t1) == 0x0062e533u);
static_assert(and_(Reg::a0, Reg::t0, Reg::t1) == 0x0062f533u);
static_assert(sll(Reg::a0, Reg::t0, Reg::t1) == 0x00629533u);
static_assert(srl(Reg::a0, Reg::t0, Reg::t1) == 0x0062d533u);
static_assert(sub(Reg::a0, Reg::t0, Reg::t1) == 0x40628533u);
static_assert(sra(Reg::a0, Reg::t0, Reg::t1) == 0x4062d533u);
static_assert(slt(Reg::a0, Reg::t0, Reg::t1) == 0x0062a533u);
static_assert(sltu(Reg::a0, Reg::t0, Reg::t1) == 0x0062b533u);
static_assert(ecall() == 0x00000073u);
static_assert(mret() == 0x30200073u);
static_assert(csrrw(Reg::a0, Reg::t0, 0x300) == 0x30029573u);
static_assert(csrrs(Reg::a0, Reg::t0, 0x300) == 0x3002a573u);
static_assert(csrrc(Reg::a0, Reg::t0, 0x300) == 0x3002b573u);
static_assert(csrrwi(Reg::a0, 0x300, 5) == 0x3002d573u);
static_assert(csrrsi(Reg::a0, 0x300, 5) == 0x3002e573u);
static_assert(csrrci(Reg::a0, 0x300, 5) == 0x3002f573u);

}  // namespace npc::test::rv32
