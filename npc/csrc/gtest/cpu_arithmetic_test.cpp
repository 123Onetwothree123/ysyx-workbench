#include <array>

#include <gtest/gtest.h>

#include "cpu_test_utils.hpp"
#include "rv32_encoding.hpp"

namespace npc::test {
namespace {

using rv32::Reg;

TEST(CpuArithmeticTest, AddiWritesPositiveImmediateToA0) {
    CpuHarness cpu;
    cpu.load_program(std::to_array<std::uint32_t>({
        rv32::addi(Reg::a0, Reg::zero, 42),
        rv32::ebreak(),
    }));
    cpu.reset();

    expect_halt(cpu.run(), 42u, guest_addr(4));
}

TEST(CpuArithmeticTest, AddiSignExtendsNegativeImmediate) {
    CpuHarness cpu;
    cpu.load_program(std::to_array<std::uint32_t>({
        rv32::addi(Reg::a0, Reg::zero, -1),
        rv32::ebreak(),
    }));
    cpu.reset();

    expect_halt(cpu.run(), 0xffffffffu, guest_addr(4));
}

TEST(CpuArithmeticTest, AddUsesLatestRegisterValues) {
    CpuHarness cpu;
    cpu.load_program(std::to_array<std::uint32_t>({
        rv32::addi(Reg::t0, Reg::zero, -4),
        rv32::addi(Reg::t1, Reg::zero, 9),
        rv32::add(Reg::a0, Reg::t0, Reg::t1),
        rv32::ebreak(),
    }));
    cpu.reset();

    expect_halt(cpu.run(), 5u, guest_addr(12));
}

TEST(CpuArithmeticTest, LuiAndAddiBuildConstant) {
    CpuHarness cpu;
    cpu.load_program(std::to_array<std::uint32_t>({
        rv32::lui(Reg::a0, 0x12345000u),
        rv32::addi(Reg::a0, Reg::a0, 0x678),
        rv32::ebreak(),
    }));
    cpu.reset();

    expect_halt(cpu.run(), 0x12345678u, guest_addr(8));
}

TEST(CpuArithmeticTest, AuipcUsesInstructionPcAsBase) {
    CpuHarness cpu;
    cpu.load_program(std::to_array<std::uint32_t>({
        rv32::jal(Reg::zero, 8),
        rv32::addi(Reg::a0, Reg::zero, 1),
        rv32::auipc(Reg::a0, 0),
        rv32::ebreak(),
    }));
    cpu.reset();

    expect_halt(cpu.run(), guest_addr(8), guest_addr(12));
}

TEST(CpuArithmeticTest, AuipcAddsUpperImmediateToProgramCounter) {
    CpuHarness cpu;
    cpu.load_program(std::to_array<std::uint32_t>({
        rv32::auipc(Reg::a0, 0x1000),
        rv32::ebreak(),
    }));
    cpu.reset();

    expect_halt(cpu.run(), guest_addr(0x1000), guest_addr(4));
}

TEST(CpuArithmeticTest, ZeroRegisterIgnoresWrites) {
    CpuHarness cpu;
    cpu.load_program(std::to_array<std::uint32_t>({
        rv32::addi(Reg::zero, Reg::zero, 99),
        rv32::addi(Reg::a0, Reg::zero, 0),
        rv32::ebreak(),
    }));
    cpu.reset();

    expect_halt(cpu.run(), 0u, guest_addr(8));
}

}  // namespace
}  // namespace npc::test
