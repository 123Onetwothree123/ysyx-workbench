#include <cstdint>

#include <gtest/gtest.h>

#include "test_runtime.hpp"

extern "C" int pmem_read(int raddr);
extern "C" void pmem_write(int waddr, int wdata, char wmask);

namespace npc::test {
namespace {

TEST(TestRuntimeMemoryTest, WordAndByteAccessUseLittleEndianLayout) {
    CpuHarness cpu;
    const auto addr = guest_addr(0x40);

    cpu.write_word(addr, 0x7856'3412u);
    EXPECT_EQ(cpu.read_byte(addr + 0), 0x12u);
    EXPECT_EQ(cpu.read_byte(addr + 1), 0x34u);
    EXPECT_EQ(cpu.read_byte(addr + 2), 0x56u);
    EXPECT_EQ(cpu.read_byte(addr + 3), 0x78u);

    cpu.write_byte(addr + 1, 0xaau);
    EXPECT_EQ(cpu.read_word(addr), 0x7856'aa12u);
}

TEST(TestRuntimeMemoryTest, HalfwordHelpersAccessTwoLittleEndianBytes) {
    CpuHarness cpu;
    const auto addr = guest_addr(0x44);

    cpu.write_word(addr, 0u);
    cpu.write_half(addr + 1, 0xbeefu);

    EXPECT_EQ(cpu.read_half(addr + 1), 0xbeefu);
    EXPECT_EQ(cpu.read_word(addr), 0x00be'ef00u);
}

TEST(TestRuntimeMemoryTest, DPIReadAlignsToContainingWord) {
    CpuHarness cpu;
    const auto addr = guest_addr(0x80);
    cpu.write_word(addr, 0x1122'3344u);

    EXPECT_EQ(static_cast<std::uint32_t>(pmem_read(static_cast<int>(addr + 0))), 0x1122'3344u);
    EXPECT_EQ(static_cast<std::uint32_t>(pmem_read(static_cast<int>(addr + 3))), 0x1122'3344u);
}

TEST(TestRuntimeMemoryTest, DPIWriteMaskUpdatesOnlySelectedByteLanes) {
    CpuHarness cpu;
    const auto addr = guest_addr(0x84);
    cpu.write_word(addr, 0x1122'3344u);

    pmem_write(static_cast<int>(addr), static_cast<int>(0xaabb'ccddu), static_cast<char>(0b0101));

    EXPECT_EQ(cpu.read_word(addr), 0x11bb'33ddu);
}

struct DpiWriteLaneCase {
    std::uint8_t mask;
    std::uint32_t data;
    std::uint32_t expected;
};

class DpiWriteLaneTest : public ::testing::TestWithParam<DpiWriteLaneCase> {};

TEST_P(DpiWriteLaneTest, SingleLaneMasksUpdateOnlyTheRequestedByte) {
    const auto [mask, data, expected] = GetParam();

    CpuHarness cpu;
    const auto addr = guest_addr(0x90);
    cpu.write_word(addr, 0x1122'3344u);

    pmem_write(static_cast<int>(addr), static_cast<int>(data), static_cast<char>(mask));

    EXPECT_EQ(cpu.read_word(addr), expected);
}

INSTANTIATE_TEST_SUITE_P(
    EveryLane,
    DpiWriteLaneTest,
    ::testing::Values(
        DpiWriteLaneCase{.mask = 0b0001, .data = 0x0000'00aau, .expected = 0x1122'33aau},
        DpiWriteLaneCase{.mask = 0b0010, .data = 0x0000'bb00u, .expected = 0x1122'bb44u},
        DpiWriteLaneCase{.mask = 0b0100, .data = 0x00cc'0000u, .expected = 0x11cc'3344u},
        DpiWriteLaneCase{.mask = 0b1000, .data = 0xdd00'0000u, .expected = 0xdd22'3344u}
    )
);

TEST(TestRuntimeMemoryTest, DPIWriteWithZeroMaskLeavesMemoryUntouched) {
    CpuHarness cpu;
    const auto addr = guest_addr(0x88);
    cpu.write_word(addr, 0x5566'7788u);

    pmem_write(static_cast<int>(addr), static_cast<int>(0xffff'ffffu), static_cast<char>(0));

    EXPECT_EQ(cpu.read_word(addr), 0x5566'7788u);
}

TEST(TestRuntimeMemoryTest, DPIWriteAlignsAddressBeforeApplyingByteMask) {
    CpuHarness cpu;
    const auto addr = guest_addr(0x8c);
    cpu.write_word(addr, 0x1122'3344u);

    pmem_write(static_cast<int>(addr + 3), static_cast<int>(0xaa00'0000u), static_cast<char>(0b1000));

    EXPECT_EQ(cpu.read_word(addr), 0xaa22'3344u);
}

TEST(TestRuntimeMemoryTest, LoadProgramCanWriteAtCustomBase) {
    CpuHarness cpu;
    const auto base = guest_addr(0x200);

    cpu.load_program({0x1111'1111u, 0x2222'2222u, 0x3333'3333u}, base);

    EXPECT_EQ(cpu.read_word(base + 0), 0x1111'1111u);
    EXPECT_EQ(cpu.read_word(base + 4), 0x2222'2222u);
    EXPECT_EQ(cpu.read_word(base + 8), 0x3333'3333u);
}

TEST(TestRuntimeMemoryTest, FreshHarnessClearsPreviousMemoryAndHaltState) {
    {
        CpuHarness cpu;
        cpu.write_word(guest_addr(0x20), 0xffff'ffffu);
        cpu.load_program({0x0010'0073u});
        cpu.reset();
        ASSERT_TRUE(cpu.run(4).halted);
    }

    CpuHarness fresh_cpu;
    EXPECT_EQ(fresh_cpu.read_word(guest_addr(0x20)), 0u);
    fresh_cpu.reset();
    EXPECT_FALSE(fresh_cpu.run(1).halted);
}

}  // namespace
}  // namespace npc::test
