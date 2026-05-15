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
    ASSERT_TRUE(result.halted);
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
    ASSERT_TRUE(result.halted);
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
    ASSERT_TRUE(result.halted);
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
        rv32::csrrw(Reg::a0, Reg::zero, kMcause),
        rv32::ebreak(),
    }, handler_addr);
    cpu.reset();

    const auto result = cpu.run(64);
    ASSERT_TRUE(result.halted);
}

}  // namespace
}  // namespace npc::test
