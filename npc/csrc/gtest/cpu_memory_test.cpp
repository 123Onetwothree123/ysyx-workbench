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

TEST(CpuMemoryTest, LbSignExtendsAllByteLanes) {
    struct LbLaneCase {
        int offset;
        std::uint32_t expected;
    };

    const std::array<LbLaneCase, 4> cases{{
        {0, 0xffff'ff80u},
        {1, 0xffff'ffffu},
        {2, 0x0000'0055u},
        {3, 0xffff'ffaau},
    }};

    for (const auto &[offset, expected] : cases) {
        CpuHarness cpu;
        cpu.write_word(guest_addr(0x100), 0xaa55'ff80u);
        cpu.load_program({
            rv32::auipc(Reg::t0, 0),
            rv32::addi(Reg::t0, Reg::t0, 0x100),
            rv32::lb(Reg::a0, Reg::t0, offset),
            rv32::ebreak(),
        });
        cpu.reset();

        expect_halt(cpu.run(), expected, guest_addr(12));
    }
}

TEST(CpuMemoryTest, LbSignExtendsPositiveByteLanes) {
    CpuHarness cpu;
    cpu.write_word(guest_addr(0x100), 0x007f'3c55u);
    cpu.load_program({
        rv32::auipc(Reg::t0, 0),
        rv32::addi(Reg::t0, Reg::t0, 0x100),
        rv32::lb(Reg::a0, Reg::t0, 0),
        rv32::lb(Reg::a1, Reg::t0, 1),
        rv32::lb(Reg::a2, Reg::t0, 2),
        rv32::lb(Reg::a3, Reg::t0, 3),
        rv32::add(Reg::a0, Reg::a0, Reg::a1),
        rv32::add(Reg::a0, Reg::a0, Reg::a2),
        rv32::add(Reg::a0, Reg::a0, Reg::a3),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 0x55u + 0x3cu + 0x7fu + 0x00u, guest_addr(36));
}

TEST(CpuMemoryTest, LhSignExtendsHalfword) {
    CpuHarness cpu;
    cpu.write_word(guest_addr(0x100), 0xffff'8000u);
    cpu.load_program({
        rv32::auipc(Reg::t0, 0),
        rv32::addi(Reg::t0, Reg::t0, 0x100),
        rv32::lh(Reg::a0, Reg::t0, 0),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 0xffff'8000u, guest_addr(12));
}

TEST(CpuMemoryTest, LhWithPositiveHalfwordDoesNotSignExtend) {
    CpuHarness cpu;
    cpu.write_word(guest_addr(0x100), 0x0000'7fffu);
    cpu.load_program({
        rv32::auipc(Reg::t0, 0),
        rv32::addi(Reg::t0, Reg::t0, 0x100),
        rv32::lh(Reg::a0, Reg::t0, 0),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 0x7fffu, guest_addr(12));
}

TEST(CpuMemoryTest, LhReadsUpperHalfwordWithSignExtend) {
    CpuHarness cpu;
    cpu.write_word(guest_addr(0x100), 0x8000'0000u);
    cpu.load_program({
        rv32::auipc(Reg::t0, 0),
        rv32::addi(Reg::t0, Reg::t0, 0x100),
        rv32::lh(Reg::a0, Reg::t0, 2),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 0xffff'8000u, guest_addr(12));
}

TEST(CpuMemoryTest, LhuZeroExtendsHalfword) {
    CpuHarness cpu;
    cpu.write_word(guest_addr(0x100), 0xabcd'8000u);
    cpu.load_program({
        rv32::auipc(Reg::t0, 0),
        rv32::addi(Reg::t0, Reg::t0, 0x100),
        rv32::lhu(Reg::a0, Reg::t0, 0),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 0x8000u, guest_addr(12));
}

TEST(CpuMemoryTest, LhuReadsUpperHalfwordWithZeroExtend) {
    CpuHarness cpu;
    cpu.write_word(guest_addr(0x100), 0xabcd'8000u);
    cpu.load_program({
        rv32::auipc(Reg::t0, 0),
        rv32::addi(Reg::t0, Reg::t0, 0x100),
        rv32::lhu(Reg::a0, Reg::t0, 2),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 0xabcdu, guest_addr(12));
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

TEST(CpuMemoryTest, ShInstructionExecutesWithoutHanging) {
    CpuHarness cpu;
    const auto data_addr = guest_addr(0x100);
    cpu.write_word(data_addr, 0x1122'3344u);
    cpu.load_program({
        rv32::auipc(Reg::t0, 0),
        rv32::addi(Reg::t0, Reg::t0, 0x100),
        rv32::lui(Reg::t1, 0xbeef'0000u),
        rv32::addi(Reg::t1, Reg::t1, 0x0),
        rv32::sh(Reg::t1, Reg::t0, 0),
        rv32::addi(Reg::a0, Reg::zero, 0),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 0u, guest_addr(24));
}

TEST(CpuMemoryTest, ShWithOffsetExecutesWithoutError) {
    CpuHarness cpu;
    const auto data_addr = guest_addr(0x100);
    cpu.write_word(data_addr, 0x1122'3344u);
    cpu.load_program({
        rv32::auipc(Reg::t0, 0),
        rv32::addi(Reg::t0, Reg::t0, 0x100),
        rv32::lui(Reg::t1, 0x789a'0000u),
        rv32::addi(Reg::t1, Reg::t1, 0x0),
        rv32::sh(Reg::t1, Reg::t0, 2),
        rv32::addi(Reg::a0, Reg::zero, 0),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 0u, guest_addr(24));
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

TEST(CpuMemoryTest, LoadSequenceCoversByteAndHalfwordWidths) {
    CpuHarness cpu;
    const auto data_addr = guest_addr(0x100);
    cpu.write_word(data_addr, 0x8000'017fu);
    cpu.load_program({
        rv32::auipc(Reg::t0, 0),
        rv32::addi(Reg::t0, Reg::t0, 0x100),
        rv32::lb(Reg::t1, Reg::t0, 0),
        rv32::lbu(Reg::t2, Reg::t0, 1),
        rv32::add(Reg::a0, Reg::t1, Reg::t2),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 0x0000'007fu + 0x0000'0001u, guest_addr(20));
}

TEST(CpuMemoryTest, StoreByteStormThenLoadBack) {
    CpuHarness cpu;
    const auto data_addr = guest_addr(0x100);
    cpu.write_word(data_addr, 0x1122'3344u);
    cpu.load_program({
        rv32::auipc(Reg::t0, 0),
        rv32::addi(Reg::t0, Reg::t0, 0x100),
        rv32::addi(Reg::t1, Reg::zero, 0xaa),
        rv32::sb(Reg::t1, Reg::t0, 0),
        rv32::addi(Reg::t1, Reg::zero, 0xbb),
        rv32::sb(Reg::t1, Reg::t0, 1),
        rv32::lw(Reg::a0, Reg::t0, 0),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 0x1122'bbaau, guest_addr(28));
    EXPECT_EQ(cpu.read_word(data_addr), 0x1122'bbaau);
}

TEST(CpuMemoryTest, ReadAfterWriteAllWidthsInterleaved) {
    CpuHarness cpu;
    const auto addr1 = guest_addr(0x200);
    cpu.write_word(addr1, 0u);
    cpu.load_program({
        rv32::auipc(Reg::t0, 0),
        rv32::addi(Reg::t0, Reg::t0, 0x200),
        rv32::addi(Reg::t1, Reg::zero, 0x12),
        rv32::sb(Reg::t1, Reg::t0, 0),
        rv32::addi(Reg::t1, Reg::t1, 0x22),
        rv32::sb(Reg::t1, Reg::t0, 1),
        rv32::addi(Reg::t1, Reg::t1, 0x34),
        rv32::sh(Reg::t1, Reg::t0, 2),
        rv32::lw(Reg::a0, Reg::t0, 0),
        rv32::ebreak(),
    });
    cpu.reset();

    const auto result = cpu.run(64);
    ASSERT_TRUE(result.halted);
}

}  // namespace
}  // namespace npc::test
