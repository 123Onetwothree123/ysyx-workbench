#include <cstdint>

#include <gtest/gtest.h>

#include "cpu_test_utils.hpp"
#include "rv32_encoding.hpp"

namespace npc::test {
namespace {

using rv32::Reg;

constexpr std::uint32_t kMstatus = 0x300u;
constexpr std::uint32_t kMepc = 0x341u;
constexpr std::uint32_t kMcause = 0x342u;
constexpr std::uint32_t kMtvec = 0x305u;
constexpr std::uint32_t kMvendorid = 0xF11u;
constexpr std::uint32_t kMarchid = 0xF12u;

TEST(CpuCsrTest, CsrrwAtomicallySwapRegisterAndCSR) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, 0x1234),
        rv32::csrrw(Reg::a0, Reg::t0, kMstatus),
        rv32::ebreak(),
    });
    cpu.reset();

    const auto result = cpu.run(32);
    ASSERT_TRUE(result.halted);
}

TEST(CpuCsrTest, CsrrsReadsAndSetsBitsInMstatus) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, 0x7),
        rv32::csrrs(Reg::a0, Reg::t0, kMstatus),
        rv32::ebreak(),
    });
    cpu.reset();

    const auto result = cpu.run(32);
    ASSERT_TRUE(result.halted);
}

TEST(CpuCsrTest, CsrrsWithX0SourceDoesNotWriteCSR) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, 0x1234),
        rv32::csrrw(Reg::zero, Reg::t0, kMstatus),
        rv32::csrrs(Reg::a0, Reg::zero, kMstatus),
        rv32::addi(Reg::a0, Reg::zero, 55),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 55u, guest_addr(16));
}

TEST(CpuCsrTest, CsrrsWritesMepcWithOrOperation) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, 0x1000),
        rv32::csrrw(Reg::t1, Reg::t0, kMepc),
        rv32::nop(),
        rv32::nop(),
        rv32::addi(Reg::t2, Reg::zero, 0x0200),
        rv32::csrrs(Reg::a0, Reg::t2, kMepc),
        rv32::ebreak(),
    });
    cpu.reset();

    const auto result = cpu.run(64);
    ASSERT_TRUE(result.halted);
}

TEST(CpuCsrTest, ReadOnlyMvendoridReturnsMagicValue) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::csrrs(Reg::a0, Reg::zero, kMvendorid),
        rv32::ebreak(),
    });
    cpu.reset();

    const auto result = cpu.run(32);
    ASSERT_TRUE(result.halted);
    EXPECT_EQ(result.halt_code, 0x79737978u);
}

TEST(CpuCsrTest, ReadOnlyMarchidReturnsConstant) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::csrrs(Reg::a0, Reg::zero, kMarchid),
        rv32::ebreak(),
    });
    cpu.reset();

    const auto result = cpu.run(32);
    ASSERT_TRUE(result.halted);
    EXPECT_EQ(result.halt_code, 0x018d3017u);
}

TEST(CpuCsrTest, AccessInvalidCsrAddressReturnsZero) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::csrrs(Reg::a0, Reg::zero, 0xDEAD),
        rv32::ebreak(),
    });
    cpu.reset();

    const auto result = cpu.run(32);
    ASSERT_TRUE(result.halted);
    EXPECT_EQ(result.halt_code, 0u);
}

TEST(CpuCsrTest, MtvecWriteWithSmallValueThenReadBack) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, 0x100),
        rv32::csrrw(Reg::t1, Reg::t0, kMtvec),
        rv32::nop(),
        rv32::nop(),
        rv32::csrrw(Reg::a0, Reg::zero, kMtvec),
        rv32::ebreak(),
    });
    cpu.reset();

    const auto result = cpu.run(32);
    ASSERT_TRUE(result.halted);
    EXPECT_EQ(result.halt_code, 0x100u);
}

TEST(CpuCsrTest, McauseWriteWithNopsThenReadBack) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, 0x2d),
        rv32::csrrw(Reg::zero, Reg::t0, kMcause),
        rv32::nop(),
        rv32::nop(),
        rv32::nop(),
        rv32::csrrw(Reg::a0, Reg::zero, kMcause),
        rv32::ebreak(),
    });
    cpu.reset();

    const auto result = cpu.run(32);
    ASSERT_TRUE(result.halted);
    EXPECT_EQ(result.halt_code, 0x2du);
}

TEST(CpuCsrTest, CsrrsMcauseOrOperationWithNops) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, 0x0f0),
        rv32::csrrw(Reg::zero, Reg::t0, kMcause),
        rv32::nop(),
        rv32::nop(),
        rv32::nop(),
        rv32::nop(),
        rv32::nop(),
        rv32::addi(Reg::t1, Reg::zero, 0x00f),
        rv32::csrrs(Reg::a0, Reg::t1, kMcause),
        rv32::ebreak(),
    });
    cpu.reset();

    const auto result = cpu.run(64);
    ASSERT_TRUE(result.halted);
}

TEST(CpuCsrTest, CsrrwiReadsCsrAndWritesImmediate) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::csrrwi(Reg::a0, kMstatus, 7),
        rv32::ebreak(),
    });
    cpu.reset();

    const auto result = cpu.run(32);
    ASSERT_TRUE(result.halted);
}

TEST(CpuCsrTest, CsrrWToZeroDestDoesNotWriteA0) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, 0x55),
        rv32::csrrw(Reg::zero, Reg::t0, kMstatus),
        rv32::addi(Reg::a0, Reg::zero, 0),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 0u, guest_addr(12));
}

TEST(CpuCsrTest, CsrAccessMepcDoesNotCrash) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, 0x100),
        rv32::csrrw(Reg::a1, Reg::t0, kMepc),
        rv32::addi(Reg::a0, Reg::zero, 0),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 0u, guest_addr(12));
}

}  // namespace
}  // namespace npc::test
