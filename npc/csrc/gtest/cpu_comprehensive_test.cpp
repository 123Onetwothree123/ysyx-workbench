#include <cstdint>

#include <gtest/gtest.h>

#include "cpu_test_utils.hpp"
#include "rv32_encoding.hpp"

namespace npc::test {
namespace {

using rv32::Reg;

constexpr std::uint32_t kMstatus{0x300u};
constexpr std::uint32_t kMtvec{0x305u};
constexpr std::uint32_t kMepc{0x341u};
constexpr std::uint32_t kMcause{0x342u};
constexpr std::uint32_t kMcycle{0xB00u};
constexpr std::uint32_t kMcycleh{0xB80u};

TEST(CpuComprehensiveCsrTest, CsrrwAndCsrrsReturnOldValueAndUpdateSupportedCsrs) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, 0x0f0),
        rv32::csrrw(Reg::a1, Reg::t0, kMstatus),
        rv32::addi(Reg::t1, Reg::zero, 0x00f),
        rv32::csrrs(Reg::a2, Reg::t1, kMstatus),
        rv32::csrrs(Reg::a3, Reg::zero, kMstatus),
        rv32::csrrw(Reg::a4, Reg::zero, kMstatus),
        rv32::csrrs(Reg::a5, Reg::zero, kMstatus),
        rv32::add(Reg::a0, Reg::a1, Reg::a2),
        rv32::add(Reg::a0, Reg::a0, Reg::a3),
        rv32::add(Reg::a0, Reg::a0, Reg::a4),
        rv32::add(Reg::a0, Reg::a0, Reg::a5),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(80), 0x02eeu, guest_addr(44));
    expect_gpr(cpu, rv32::reg_bits(Reg::a1), 0u);
    expect_gpr(cpu, rv32::reg_bits(Reg::a2), 0x0f0u);
    expect_gpr(cpu, rv32::reg_bits(Reg::a3), 0x0ffu);
    expect_gpr(cpu, rv32::reg_bits(Reg::a4), 0x0ffu);
    expect_gpr(cpu, rv32::reg_bits(Reg::a5), 0u);
}

TEST(CpuComprehensiveCsrTest, CsrrsWithZeroSourceOnlyReadsWithoutMutatingCsr) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, 0x7f),
        rv32::csrrw(Reg::zero, Reg::t0, kMcause),
        rv32::csrrs(Reg::a1, Reg::zero, kMcause),
        rv32::csrrs(Reg::a2, Reg::zero, kMcause),
        rv32::csrrs(Reg::a3, Reg::zero, kMcause),
        rv32::add(Reg::a0, Reg::a1, Reg::a2),
        rv32::add(Reg::a0, Reg::a0, Reg::a3),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(64), 0x17du, guest_addr(28));
    expect_gpr(cpu, rv32::reg_bits(Reg::a1), 0x7fu);
    expect_gpr(cpu, rv32::reg_bits(Reg::a2), 0x7fu);
    expect_gpr(cpu, rv32::reg_bits(Reg::a3), 0x7fu);
}

TEST(CpuComprehensiveCsrTest, McycleLowAndHighCanBeWrittenAndReadIndependently) {
    CpuHarness cpu;
    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, 0),
        rv32::csrrw(Reg::zero, Reg::t0, kMcycle),
        rv32::addi(Reg::t1, Reg::zero, 0x12),
        rv32::csrrw(Reg::zero, Reg::t1, kMcycleh),
        rv32::csrrs(Reg::a1, Reg::zero, kMcycleh),
        rv32::csrrs(Reg::a2, Reg::zero, kMcycle),
        rv32::addi(Reg::a0, Reg::a1, 0),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(64), 0x12u, guest_addr(28));
    expect_gpr(cpu, rv32::reg_bits(Reg::a1), 0x12u);
    EXPECT_GE(cpu.debug_read_gpr(rv32::reg_bits(Reg::a2)), 1u);
}

TEST(CpuComprehensiveExceptionTest, EcallWritesPreciseMepcMcauseAndMstatusThenMretRestoresMieAndClearsMpp) {
    CpuHarness cpu;
    const auto handler_addr{guest_addr(0x80)};
    const auto resume_addr{guest_addr(0x30)};

    cpu.load_program({
        rv32::addi(Reg::t0, Reg::zero, 0x8),
        rv32::csrrw(Reg::zero, Reg::t0, kMstatus),
        rv32::auipc(Reg::t1, 0),
        rv32::addi(Reg::t1, Reg::t1, 0x78),
        rv32::csrrw(Reg::zero, Reg::t1, kMtvec),
        rv32::nop(),
        rv32::nop(),
        rv32::ecall(),
        rv32::addi(Reg::a0, Reg::zero, 0x11),
        rv32::ebreak(),
        rv32::nop(),
        rv32::nop(),
        rv32::csrrs(Reg::a3, Reg::zero, kMstatus),
        rv32::addi(Reg::a0, Reg::a3, 0),
        rv32::ebreak(),
    });

    cpu.load_program({
        rv32::csrrs(Reg::a1, Reg::zero, kMepc),
        rv32::csrrs(Reg::a2, Reg::zero, kMcause),
        rv32::auipc(Reg::t2, 0),
        rv32::addi(Reg::t2, Reg::t2, static_cast<std::int32_t>(resume_addr - (handler_addr + 8))),
        rv32::csrrw(Reg::zero, Reg::t2, kMepc),
        rv32::mret(),
    }, handler_addr);
    cpu.reset();

    expect_halt(cpu.run(128), 0x88u, guest_addr(56));
    expect_gpr(cpu, rv32::reg_bits(Reg::a1), guest_addr(28));
    expect_gpr(cpu, rv32::reg_bits(Reg::a2), 11u);
    expect_gpr(cpu, rv32::reg_bits(Reg::a3), 0x88u);
}

TEST(CpuComprehensiveMemoryTest, StoreHalfwordLowerAndUpperLanesPreciselyUpdateMemoryAndLoads) {
    CpuHarness cpu;
    const auto data_addr{guest_addr(0x100)};
    cpu.write_word(data_addr, 0x1122'3344u);
    cpu.load_program({
        rv32::auipc(Reg::t0, 0),
        rv32::addi(Reg::t0, Reg::t0, 0x100),
        rv32::addi(Reg::t1, Reg::zero, 0x055),
        rv32::sh(Reg::t1, Reg::t0, 0),
        rv32::addi(Reg::t1, Reg::zero, 0x066),
        rv32::sh(Reg::t1, Reg::t0, 2),
        rv32::lhu(Reg::a1, Reg::t0, 0),
        rv32::lhu(Reg::a2, Reg::t0, 2),
        rv32::lw(Reg::a3, Reg::t0, 0),
        rv32::add(Reg::a0, Reg::a1, Reg::a2),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(80), 0x0bbu, guest_addr(40));
    expect_gpr(cpu, rv32::reg_bits(Reg::a1), 0x55u);
    expect_gpr(cpu, rv32::reg_bits(Reg::a2), 0x66u);
    expect_gpr(cpu, rv32::reg_bits(Reg::a3), 0x0066'0055u);
    expect_memory_word(cpu, data_addr, 0x0066'0055u);
    expect_memory_half(cpu, data_addr + 0, 0x0055u);
    expect_memory_half(cpu, data_addr + 2, 0x0066u);
}

TEST(CpuComprehensiveMemoryTest, MisalignedHalfwordAndWordAccessesUseContainingAlignedWordDeterministically) {
    CpuHarness cpu;
    const auto data_addr{guest_addr(0x100)};
    cpu.write_word(data_addr, 0x4433'2211u);
    cpu.write_word(data_addr + 4, 0x8877'6655u);
    cpu.load_program({
        rv32::auipc(Reg::t0, 0),
        rv32::addi(Reg::t0, Reg::t0, 0x100),
        rv32::lh(Reg::a1, Reg::t0, 1),
        rv32::lw(Reg::a2, Reg::t0, 2),
        rv32::add(Reg::a0, Reg::a1, Reg::a2),
        rv32::ebreak(),
    });
    cpu.reset();

    expect_halt(cpu.run(48), 0x4433'2211u + 0x2211u, guest_addr(20));
    expect_gpr(cpu, rv32::reg_bits(Reg::a1), 0x2211u);
    expect_gpr(cpu, rv32::reg_bits(Reg::a2), 0x4433'2211u);
}

TEST(CpuComprehensiveIntegrationTest, EndToEndProgramCoversAluMemoryControlFlowCsrAndWritebackSources) {
    CpuHarness cpu;
    const auto data_addr{guest_addr(0x180)};
    const auto subroutine_addr{guest_addr(0x60)};
    cpu.write_word(data_addr, 0x0102'0304u);

    cpu.load_program({
        rv32::auipc(Reg::t0, 0),
        rv32::addi(Reg::t0, Reg::t0, 0x180),
        rv32::lw(Reg::t1, Reg::t0, 0),
        rv32::addi(Reg::t2, Reg::zero, 0x7f),
        rv32::sb(Reg::t2, Reg::t0, 1),
        rv32::lh(Reg::a1, Reg::t0, 0),
        rv32::lbu(Reg::a2, Reg::t0, 3),
        rv32::slli(Reg::a2, Reg::a2, 8),
        rv32::or_(Reg::a1, Reg::a1, Reg::a2),
        rv32::addi(Reg::t3, Reg::zero, 0x3),
        rv32::csrrw(Reg::a3, Reg::t3, kMstatus),
        rv32::addi(Reg::t4, Reg::zero, 0x4),
        rv32::csrrs(Reg::a4, Reg::t4, kMstatus),
        rv32::csrrs(Reg::a5, Reg::zero, kMstatus),
        rv32::auipc(Reg::ra, 0),
        rv32::addi(Reg::ra, Reg::ra, static_cast<std::int32_t>(subroutine_addr - guest_addr(56))),
        rv32::jalr(Reg::ra, Reg::ra, 0),
        rv32::addi(Reg::a0, Reg::a0, 0x10),
        rv32::ebreak(),
    });

    cpu.load_program({
        rv32::add(Reg::a0, Reg::a1, Reg::a4),
        rv32::add(Reg::a0, Reg::a0, Reg::a5),
        rv32::ret(),
    }, subroutine_addr);
    cpu.reset();

    expect_halt(cpu.run(128), 0x0000'7f1eu, guest_addr(72));
    expect_gpr(cpu, rv32::reg_bits(Reg::a1), 0x0000'7f04u);
    expect_gpr(cpu, rv32::reg_bits(Reg::a3), 0u);
    expect_gpr(cpu, rv32::reg_bits(Reg::a4), 0x3u);
    expect_gpr(cpu, rv32::reg_bits(Reg::a5), 0x7u);
    expect_memory_word(cpu, data_addr, 0x0102'7f04u);
}

}  // namespace
}  // namespace npc::test
