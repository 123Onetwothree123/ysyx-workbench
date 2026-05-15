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
        rv32::addi(Reg::t0, Reg::zero, 0x123),
        rv32::csrrw(Reg::a1, Reg::t0, kMstatus),
        rv32::csrrs(Reg::a2, Reg::zero, kMstatus),
        rv32::add(Reg::a0, Reg::a1, Reg::a2),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(32), 0x123u, guest_addr(16));
    expect_gpr(cpu, rv32::reg_bits(Reg::a1), 0u);
    expect_gpr(cpu, rv32::reg_bits(Reg::a2), 0x123u);
}

TEST(CpuCsrTest, CsrrsReadsAndSetsBitsInMstatus) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, 0x7),
        rv32::csrrs(Reg::a1, Reg::t0, kMstatus),
        rv32::csrrs(Reg::a2, Reg::zero, kMstatus),
        rv32::add(Reg::a0, Reg::a1, Reg::a2),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(32), 0x7u, guest_addr(16));
    expect_gpr(cpu, rv32::reg_bits(Reg::a1), 0u);
    expect_gpr(cpu, rv32::reg_bits(Reg::a2), 0x7u);
}

TEST(CpuCsrTest, CsrrsWithX0SourceDoesNotWriteCSR) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, 0x34),
        rv32::csrrw(Reg::zero, Reg::t0, kMstatus),
        rv32::csrrs(Reg::a1, Reg::zero, kMstatus),
        rv32::addi(Reg::t1, Reg::zero, 0x01),
        rv32::csrrs(Reg::a2, Reg::t1, kMstatus),
        rv32::csrrs(Reg::a3, Reg::zero, kMstatus),
        rv32::add(Reg::a0, Reg::a1, Reg::a2),
        rv32::add(Reg::a0, Reg::a0, Reg::a3),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(64), 0x9du, guest_addr(32));
    expect_gpr(cpu, rv32::reg_bits(Reg::a1), 0x34u);
    expect_gpr(cpu, rv32::reg_bits(Reg::a2), 0x34u);
    expect_gpr(cpu, rv32::reg_bits(Reg::a3), 0x35u);
}

TEST(CpuCsrTest, CsrrsWritesMepcWithOrOperation) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, 0x100),
        rv32::csrrw(Reg::zero, Reg::t0, kMepc),
        rv32::addi(Reg::t2, Reg::zero, 0x020),
        rv32::csrrs(Reg::a1, Reg::t2, kMepc),
        rv32::csrrs(Reg::a2, Reg::zero, kMepc),
        rv32::add(Reg::a0, Reg::a1, Reg::a2),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(64), 0x220u, guest_addr(24));
    expect_gpr(cpu, rv32::reg_bits(Reg::a1), 0x100u);
    expect_gpr(cpu, rv32::reg_bits(Reg::a2), 0x120u);
}

TEST(CpuCsrTest, ReadOnlyMvendoridReturnsMagicValue) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::csrrs(Reg::a0, Reg::zero, kMvendorid),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(32), 0x79737978u, guest_addr(4));
}

TEST(CpuCsrTest, ReadOnlyMarchidReturnsConstant) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::csrrs(Reg::a0, Reg::zero, kMarchid),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(32), 0x018d3017u, guest_addr(4));
}

TEST(CpuCsrTest, AccessInvalidCsrAddressReturnsZero) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::csrrs(Reg::a0, Reg::zero, 0xDEAD),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(32), 0u, guest_addr(4));
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

    expect_halt(cpu.run(32), 0x100u, guest_addr(20));
    expect_gpr(cpu, rv32::reg_bits(Reg::t1), 0u);
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

    expect_halt(cpu.run(32), 0x2du, guest_addr(24));
}

TEST(CpuCsrTest, CsrrsMcauseOrOperationWithNops) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, 0x0f0),
        rv32::csrrw(Reg::zero, Reg::t0, kMcause),
        rv32::nop(),
        rv32::nop(),
        rv32::addi(Reg::t1, Reg::zero, 0x00f),
        rv32::csrrs(Reg::a1, Reg::t1, kMcause),
        rv32::csrrs(Reg::a2, Reg::zero, kMcause),
        rv32::add(Reg::a0, Reg::a1, Reg::a2),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(64), 0x1efu, guest_addr(32));
    expect_gpr(cpu, rv32::reg_bits(Reg::a1), 0x0f0u);
    expect_gpr(cpu, rv32::reg_bits(Reg::a2), 0x0ffu);
}

TEST(CpuCsrTest, UnsupportedCsrrwiFallsThroughWithoutBlockingFollowingInstructions) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::csrrwi(Reg::a1, kMstatus, 7),
        rv32::addi(Reg::a0, Reg::zero, 123),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(32), 123u, guest_addr(8));
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
        rv32::csrrs(Reg::a2, Reg::zero, kMepc),
        rv32::add(Reg::a0, Reg::a1, Reg::a2),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(), 0x100u, guest_addr(16));
    expect_gpr(cpu, rv32::reg_bits(Reg::a1), 0u);
    expect_gpr(cpu, rv32::reg_bits(Reg::a2), 0x100u);
}

TEST(CpuCsrTest, CsrrsWithZeroDestinationStillUpdatesWritableCsr) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, 0x4),
        rv32::csrrs(Reg::zero, Reg::t0, kMstatus),
        rv32::csrrs(Reg::a0, Reg::zero, kMstatus),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(32), 0x4u, guest_addr(12));
}

}  // namespace
}  // namespace npc::test
