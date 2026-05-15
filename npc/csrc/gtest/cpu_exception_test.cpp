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

TEST(CpuExceptionTest, EcallRedirectsToMtvecHandlerAfterNops) {
    CpuHarness cpu;
    const auto handler_addr = guest_addr(0x80);
    cpu.load_program({
        rv32::auipc(Reg::t0, 0),
        rv32::addi(Reg::t0, Reg::t0, 0x80),
        rv32::csrrw(Reg::zero, Reg::t0, kMtvec),
        rv32::nop(),
        rv32::nop(),
        rv32::nop(),
        rv32::nop(),
        rv32::nop(),
        rv32::ecall(),
        rv32::addi(Reg::a0, Reg::zero, 0),
        rv32::ebreak(),
    });
    cpu.load_program({
        rv32::addi(Reg::a0, Reg::zero, 99),
        rv32::ebreak(),
    }, handler_addr);
    cpu.reset();

    const auto result = cpu.run(64);
    expect_halt(result, 99u, handler_addr + 4);
}

TEST(CpuExceptionTest, EcallSetsMcauseInHandler) {
    CpuHarness cpu;
    const auto handler_addr = guest_addr(0x80);
    cpu.load_program({
        rv32::auipc(Reg::t0, 0),
        rv32::addi(Reg::t0, Reg::t0, 0x80),
        rv32::csrrw(Reg::zero, Reg::t0, kMtvec),
        rv32::nop(),
        rv32::nop(),
        rv32::nop(),
        rv32::nop(),
        rv32::nop(),
        rv32::ecall(),
        rv32::addi(Reg::a0, Reg::zero, 0),
        rv32::ebreak(),
    });
    cpu.load_program({
        rv32::csrrw(Reg::a0, Reg::zero, kMcause),
        rv32::ebreak(),
    }, handler_addr);
    cpu.reset();

    const auto result = cpu.run(64);
    expect_halt(result, 11u, handler_addr + 4);
}

TEST(CpuExceptionTest, MretWithCsrrwSetMepcRedirectsToTarget) {
    CpuHarness cpu;
    const auto target_addr = guest_addr(0x40);

    cpu.load_program({
        rv32::auipc(Reg::t0, 0),
        rv32::addi(Reg::t0, Reg::t0, 0x40),
        rv32::csrrw(Reg::zero, Reg::t0, kMepc),
        rv32::nop(),
        rv32::nop(),
        rv32::nop(),
        rv32::nop(),
        rv32::nop(),
        rv32::mret(),
        rv32::addi(Reg::a0, Reg::zero, 0),
        rv32::ebreak(),
    });
    cpu.load_program({
        rv32::addi(Reg::a0, Reg::zero, 77),
        rv32::ebreak(),
    }, target_addr);
    cpu.reset();

    const auto result = cpu.run(64);
    expect_halt(result, 77u, target_addr + 4);
}

TEST(CpuExceptionTest, EcallSavesPcAndHandlerReadsMcause) {
    CpuHarness cpu;
    const auto handler_addr = guest_addr(0x80);

    cpu.load_program({
        rv32::auipc(Reg::t0, 0),
        rv32::addi(Reg::t0, Reg::t0, 0x80),
        rv32::csrrw(Reg::zero, Reg::t0, kMtvec),
        rv32::nop(),
        rv32::nop(),
        rv32::nop(),
        rv32::nop(),
        rv32::nop(),
        rv32::addi(Reg::t1, Reg::zero, 42),
        rv32::ecall(),
        rv32::ebreak(),
    });
    cpu.load_program({
        rv32::csrrs(Reg::a1, Reg::zero, kMepc),
        rv32::csrrs(Reg::a2, Reg::zero, kMcause),
        rv32::add(Reg::a0, Reg::a1, Reg::a2),
        rv32::ebreak(),
    }, handler_addr);
    cpu.reset();

    const auto result = cpu.run(64);
    expect_halt(result, guest_addr(36) + 11u, handler_addr + 12);
    expect_gpr(cpu, rv32::reg_bits(Reg::a1), guest_addr(36));
    expect_gpr(cpu, rv32::reg_bits(Reg::a2), 11u);
}

TEST(CpuExceptionTest, EcallMretRoundTripCanResumeAtProgrammedMepc) {
    CpuHarness cpu;
    const auto handler_addr = guest_addr(0x80);
    const auto resume_addr = guest_addr(0x38);

    cpu.load_program({
        rv32::auipc(Reg::t0, 0),
        rv32::addi(Reg::t0, Reg::t0, 0x80),
        rv32::csrrw(Reg::zero, Reg::t0, kMtvec),
        rv32::addi(Reg::a0, Reg::zero, 1),
        rv32::ecall(),
        rv32::addi(Reg::a0, Reg::zero, 0x22),
        rv32::ebreak(),
        rv32::nop(),
        rv32::nop(),
        rv32::nop(),
        rv32::nop(),
        rv32::nop(),
        rv32::nop(),
        rv32::nop(),
        rv32::addi(Reg::a0, Reg::a0, 0x33),
        rv32::ebreak(),
    });
    cpu.load_program({
        rv32::csrrs(Reg::a1, Reg::zero, kMepc),
        rv32::auipc(Reg::t1, 0),
        rv32::addi(Reg::t1, Reg::t1, static_cast<std::int32_t>(resume_addr - (handler_addr + 4))),
        rv32::csrrw(Reg::zero, Reg::t1, kMepc),
        rv32::mret(),
    }, handler_addr);
    cpu.reset();

    expect_halt(cpu.run(128), 0x34u, resume_addr + 4);
    expect_gpr(cpu, rv32::reg_bits(Reg::a1), guest_addr(16));
}

}  // namespace
}  // namespace npc::test
