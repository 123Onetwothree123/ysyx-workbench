#include <cstdint>

#include <gtest/gtest.h>

#include "cpu_test_utils.hpp"
#include "rv32_encoding.hpp"

namespace npc::test {
namespace {

using rv32::Reg;

TEST(CpuMemoryTest, LwReadsAlignedWordWithLittleEndianLayout) {
    CpuHarness cpu;
    const auto data_addr = guest_addr(0x100);
    cpu.write_byte(data_addr + 0, 0x12);
    cpu.write_byte(data_addr + 1, 0x34);
    cpu.write_byte(data_addr + 2, 0x56);
    cpu.write_byte(data_addr + 3, 0x78);
    cpu.load_program({
        rv32::auipc(Reg::t0, 0),
        rv32::addi(Reg::t0, Reg::t0, 0x100),
        rv32::lw(Reg::a0, Reg::t0, 0),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 0x7856'3412u, guest_addr(12));
}

struct ByteLaneCase {
    int offset;
    std::uint32_t expected;
};

class CpuLbuLaneTest : public ::testing::TestWithParam<ByteLaneCase> {};

TEST_P(CpuLbuLaneTest, ReadsSelectedByteLaneAndZeroExtends) {
    const auto [offset, expected] = GetParam();

    CpuHarness cpu;
    cpu.write_word(guest_addr(0x100), 0x807f'55aau);
    cpu.load_program({
        rv32::auipc(Reg::t0, 0),
        rv32::addi(Reg::t0, Reg::t0, 0x100),
        rv32::lbu(Reg::a0, Reg::t0, offset),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), expected, guest_addr(12));
}

INSTANTIATE_TEST_SUITE_P(
    EveryLane,
    CpuLbuLaneTest,
    ::testing::Values(
        ByteLaneCase{.offset = 0, .expected = 0xaau},
        ByteLaneCase{.offset = 1, .expected = 0x55u},
        ByteLaneCase{.offset = 2, .expected = 0x7fu},
        ByteLaneCase{.offset = 3, .expected = 0x80u}
    )
);

TEST(CpuMemoryTest, LbuUsesSignedOffsetsAcrossAlignedWords) {
    CpuHarness cpu;
    const auto data_addr = guest_addr(0x100);
    cpu.write_word(data_addr + 0, 0x1122'3344u);
    cpu.write_word(data_addr + 4, 0xaabb'ccddu);
    cpu.load_program({
        rv32::auipc(Reg::t0, 0),
        rv32::addi(Reg::t0, Reg::t0, 0x102),
        rv32::lbu(Reg::t1, Reg::t0, -1),
        rv32::lbu(Reg::t2, Reg::t0, 2),
        rv32::add(Reg::a0, Reg::t1, Reg::t2),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 0x110u, guest_addr(20));
}

struct StoreByteLaneCase {
    int offset;
    std::uint32_t expected_word;
};

class CpuSbLaneTest : public ::testing::TestWithParam<StoreByteLaneCase> {};

TEST_P(CpuSbLaneTest, WritesRequestedByteLaneWithoutTouchingNeighbors) {
    const auto [offset, expected_word] = GetParam();

    CpuHarness cpu;
    const auto data_addr = guest_addr(0x100);
    cpu.write_word(data_addr, 0x1122'3344u);
    cpu.load_program({
        rv32::auipc(Reg::t0, 0),
        rv32::addi(Reg::t0, Reg::t0, 0x100),
        rv32::addi(Reg::t1, Reg::zero, -0x56),
        rv32::sb(Reg::t1, Reg::t0, offset),
        rv32::lbu(Reg::a0, Reg::t0, offset),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 0xaau, guest_addr(20));
    EXPECT_EQ(cpu.read_word(data_addr), expected_word);
}

INSTANTIATE_TEST_SUITE_P(
    EveryLane,
    CpuSbLaneTest,
    ::testing::Values(
        StoreByteLaneCase{.offset = 0, .expected_word = 0x1122'33aau},
        StoreByteLaneCase{.offset = 1, .expected_word = 0x1122'aa44u},
        StoreByteLaneCase{.offset = 2, .expected_word = 0x11aa'3344u},
        StoreByteLaneCase{.offset = 3, .expected_word = 0xaa22'3344u}
    )
);

TEST(CpuMemoryTest, SbCanCrossToNextAlignedWordWithoutTouchingPreviousWord) {
    CpuHarness cpu;
    const auto data_addr = guest_addr(0x100);
    cpu.write_word(data_addr + 0, 0x1122'3344u);
    cpu.write_word(data_addr + 4, 0xaabb'ccddu);
    cpu.load_program({
        rv32::auipc(Reg::t0, 0),
        rv32::addi(Reg::t0, Reg::t0, 0x103),
        rv32::addi(Reg::t1, Reg::zero, 0x7e),
        rv32::sb(Reg::t1, Reg::t0, 1),
        rv32::lbu(Reg::a0, Reg::t0, 1),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 0x7eu, guest_addr(20));
    EXPECT_EQ(cpu.read_word(data_addr + 0), 0x1122'3344u);
    EXPECT_EQ(cpu.read_word(data_addr + 4), 0xaabb'cc7eu);
}

TEST(CpuMemoryTest, SwWritesFullWordAndCanBeReadBack) {
    CpuHarness cpu;
    const auto data_addr = guest_addr(0x100);
    cpu.write_word(data_addr, 0u);
    cpu.load_program({
        rv32::auipc(Reg::t0, 0),
        rv32::addi(Reg::t0, Reg::t0, 0x100),
        rv32::lui(Reg::t1, 0x1234'5000u),
        rv32::addi(Reg::t1, Reg::t1, 0x678),
        rv32::sw(Reg::t1, Reg::t0, 0),
        rv32::lw(Reg::a0, Reg::t0, 0),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 0x1234'5678u, guest_addr(24));
    EXPECT_EQ(cpu.read_word(data_addr), 0x1234'5678u);
}

TEST(CpuMemoryTest, SwUsesSignedNegativeOffsetFromBaseRegister) {
    CpuHarness cpu;
    const auto data_addr = guest_addr(0x100);
    cpu.write_word(data_addr, 0u);
    cpu.load_program({
        rv32::auipc(Reg::t0, 0),
        rv32::addi(Reg::t0, Reg::t0, 0x104),
        rv32::lui(Reg::t1, 0x7654'3000u),
        rv32::addi(Reg::t1, Reg::t1, 0x210),
        rv32::sw(Reg::t1, Reg::t0, -4),
        rv32::lw(Reg::a0, Reg::t0, -4),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 0x7654'3210u, guest_addr(24));
    EXPECT_EQ(cpu.read_word(data_addr), 0x7654'3210u);
}

TEST(CpuMemoryTest, LoadAndStoreUseSignedOffsetsFromBaseRegister) {
    CpuHarness cpu;
    const auto data_addr = guest_addr(0x100);
    cpu.write_word(data_addr, 0x0102'0304u);
    cpu.load_program({
        rv32::auipc(Reg::t0, 0),
        rv32::addi(Reg::t0, Reg::t0, 0x104),
        rv32::addi(Reg::t1, Reg::zero, 0x55),
        rv32::sb(Reg::t1, Reg::t0, -1),
        rv32::lw(Reg::a0, Reg::t0, -4),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 0x5502'0304u, guest_addr(20));
    EXPECT_EQ(cpu.read_word(data_addr), 0x5502'0304u);
}

TEST(CpuMemoryTest, ProgramCanCopyWordThenPatchAByte) {
    CpuHarness cpu;
    const auto source_addr = guest_addr(0x100);
    const auto dest_addr = guest_addr(0x104);
    cpu.write_word(source_addr, 0xcafe'babeu);
    cpu.write_word(dest_addr, 0u);
    cpu.load_program({
        rv32::auipc(Reg::t0, 0),
        rv32::addi(Reg::t0, Reg::t0, 0x100),
        rv32::lw(Reg::t1, Reg::t0, 0),
        rv32::sw(Reg::t1, Reg::t0, 4),
        rv32::addi(Reg::t2, Reg::zero, 0x11),
        rv32::sb(Reg::t2, Reg::t0, 5),
        rv32::lw(Reg::a0, Reg::t0, 4),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 0xcafe'11beu, guest_addr(28));
    EXPECT_EQ(cpu.read_word(source_addr), 0xcafe'babeu);
    EXPECT_EQ(cpu.read_word(dest_addr), 0xcafe'11beu);
}

}  // namespace
}  // namespace npc::test
