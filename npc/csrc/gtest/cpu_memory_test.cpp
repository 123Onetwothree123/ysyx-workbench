#include <array>

#include <gtest/gtest.h>

#include "cpu_test_utils.hpp"
#include "rv32_encoding.hpp"

namespace npc::test {
namespace {

using rv32::Reg;

TEST(CpuMemoryTest, LwReadsAlignedWord) {
    CpuHarness cpu;
    const auto data_addr = guest_addr(0x100);
    cpu.write_word(data_addr, 0x78563412u);
    cpu.load_program(std::to_array<std::uint32_t>({
        rv32::auipc(Reg::t0, 0),
        rv32::addi(Reg::t0, Reg::t0, 0x100),
        rv32::lw(Reg::a0, Reg::t0, 0),
        rv32::ebreak(),
    }));
    cpu.reset();

    expect_halt(cpu.run(), 0x78563412u, guest_addr(12));
}

TEST(CpuMemoryTest, LbuReadsEveryByteLaneAndZeroExtends) {
    struct TestCase {
        int offset;
        std::uint32_t expected;
    };

    constexpr auto kCases = std::to_array<TestCase>({
        {.offset = 0, .expected = 0xaau},
        {.offset = 1, .expected = 0x55u},
        {.offset = 2, .expected = 0x7fu},
        {.offset = 3, .expected = 0x80u},
    });

    for (const auto &test_case : kCases) {
        SCOPED_TRACE(test_case.offset);

        CpuHarness cpu;
        cpu.write_word(guest_addr(0x100), 0x807f55aau);
        cpu.load_program(std::to_array<std::uint32_t>({
            rv32::auipc(Reg::t0, 0),
            rv32::addi(Reg::t0, Reg::t0, 0x100),
            rv32::lbu(Reg::a0, Reg::t0, test_case.offset),
            rv32::ebreak(),
        }));
        cpu.reset();

        expect_halt(cpu.run(), test_case.expected, guest_addr(12));
    }
}

TEST(CpuMemoryTest, SbWritesRequestedByteLaneWithoutTouchingNeighbors) {
    struct TestCase {
        int offset;
        std::uint32_t expected_word;
    };

    constexpr auto kCases = std::to_array<TestCase>({
        {.offset = 0, .expected_word = 0x112233aau},
        {.offset = 1, .expected_word = 0x1122aa44u},
        {.offset = 2, .expected_word = 0x11aa3344u},
        {.offset = 3, .expected_word = 0xaa223344u},
    });

    for (const auto &test_case : kCases) {
        SCOPED_TRACE(test_case.offset);

        CpuHarness cpu;
        const auto data_addr = guest_addr(0x100);
        cpu.write_word(data_addr, 0x11223344u);
        cpu.load_program(std::to_array<std::uint32_t>({
            rv32::auipc(Reg::t0, 0),
            rv32::addi(Reg::t0, Reg::t0, 0x100),
            rv32::addi(Reg::t1, Reg::zero, 0xaa),
            rv32::sb(Reg::t1, Reg::t0, test_case.offset),
            rv32::lbu(Reg::a0, Reg::t0, test_case.offset),
            rv32::ebreak(),
        }));
        cpu.reset();

        expect_halt(cpu.run(), 0xaau, guest_addr(20));
        EXPECT_EQ(cpu.read_word(data_addr), test_case.expected_word);
    }
}

TEST(CpuMemoryTest, SwWritesFullWordAndCanBeReadBack) {
    CpuHarness cpu;
    const auto data_addr = guest_addr(0x100);
    cpu.write_word(data_addr, 0u);
    cpu.load_program(std::to_array<std::uint32_t>({
        rv32::auipc(Reg::t0, 0),
        rv32::addi(Reg::t0, Reg::t0, 0x100),
        rv32::lui(Reg::t1, 0x12345000u),
        rv32::addi(Reg::t1, Reg::t1, 0x678),
        rv32::sw(Reg::t1, Reg::t0, 0),
        rv32::lw(Reg::a0, Reg::t0, 0),
        rv32::ebreak(),
    }));
    cpu.reset();

    expect_halt(cpu.run(), 0x12345678u, guest_addr(24));
    EXPECT_EQ(cpu.read_word(data_addr), 0x12345678u);
}

TEST(CpuMemoryTest, LoadUsesSignedOffsetFromBaseRegister) {
    CpuHarness cpu;
    cpu.write_word(guest_addr(0x100), 0x78563412u);
    cpu.load_program(std::to_array<std::uint32_t>({
        rv32::auipc(Reg::t0, 0),
        rv32::addi(Reg::t0, Reg::t0, 0x104),
        rv32::lw(Reg::a0, Reg::t0, -4),
        rv32::ebreak(),
    }));
    cpu.reset();

    expect_halt(cpu.run(), 0x78563412u, guest_addr(12));
}

TEST(CpuMemoryTest, StoreUsesSignedOffsetFromBaseRegister) {
    CpuHarness cpu;
    const auto base_addr = guest_addr(0x100);
    cpu.write_word(base_addr, 0x01020304u);
    cpu.load_program(std::to_array<std::uint32_t>({
        rv32::auipc(Reg::t0, 0),
        rv32::addi(Reg::t0, Reg::t0, 0x104),
        rv32::addi(Reg::t1, Reg::zero, 0x55),
        rv32::sb(Reg::t1, Reg::t0, -1),
        rv32::lbu(Reg::a0, Reg::t0, -1),
        rv32::ebreak(),
    }));
    cpu.reset();

    expect_halt(cpu.run(), 0x55u, guest_addr(20));
    EXPECT_EQ(cpu.read_word(base_addr), 0x55020304u);
}

}  // namespace
}  // namespace npc::test
