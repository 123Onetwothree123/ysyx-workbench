#include <cstdint>

#include <gtest/gtest.h>

#include "DifftestCPUState.hpp"

namespace {

TEST(DifftestCPUStateTest, DefaultStateHasZeroRegisters) {
    DifftestCPUState state;
    for (std::size_t i = 0; i < 32; ++i) {
        EXPECT_EQ(state.GetGPR(i), 0u) << "GPR " << i;
    }
    EXPECT_EQ(state.GetPC(), 0u);
}

TEST(DifftestCPUStateTest, SetAndGetGprWithinValidRange) {
    DifftestCPUState state;
    state.SetGPR(0, 0xdeadbeefu);
    state.SetGPR(10, 0x12345678u);
    state.SetGPR(31, 0xffffffffu);

    EXPECT_EQ(state.GetGPR(0), 0xdeadbeefu);
    EXPECT_EQ(state.GetGPR(10), 0x12345678u);
    EXPECT_EQ(state.GetGPR(31), 0xffffffffu);
}

TEST(DifftestCPUStateTest, GetGprWithinValidRangeWorks) {
    DifftestCPUState state;
    state.SetGPR(5, 0xaaaabbbbu);

    EXPECT_EQ(state.GetGPR(0), 0u);
    EXPECT_EQ(state.GetGPR(5), 0xaaaabbbbu);
    EXPECT_EQ(state.GetGPR(31), 0u);
}

TEST(DifftestCPUStateTest, SetGprWithinValidRangeWorks) {
    DifftestCPUState state;
    state.SetGPR(5, 42u);
    state.SetGPR(10, 99u);

    EXPECT_EQ(state.GetGPR(5), 42u);
    EXPECT_EQ(state.GetGPR(10), 99u);
}

TEST(DifftestCPUStateTest, SetAndGetPC) {
    DifftestCPUState state;
    state.SetPC(0x80000000u);
    EXPECT_EQ(state.GetPC(), 0x80000000u);

    state.SetPC(0x80001000u);
    EXPECT_EQ(state.GetPC(), 0x80001000u);
}

TEST(DifftestCPUStateTest, SameStatePassesCheck) {
    DifftestCPUState ref;
    ref.SetGPR(1, 0x100u);
    ref.SetGPR(2, 0x200u);
    ref.SetPC(0x80000000u);

    DifftestCPUState dut;
    dut.SetGPR(1, 0x100u);
    dut.SetGPR(2, 0x200u);
    dut.SetPC(0x80000000u);

    EXPECT_TRUE(ref.CheckRegs(dut));
}

TEST(DifftestCPUStateTest, DifferentGprFailsCheck) {
    DifftestCPUState ref;
    ref.SetGPR(1, 0x100u);
    ref.SetGPR(2, 0x200u);

    DifftestCPUState dut;
    dut.SetGPR(1, 0x100u);
    dut.SetGPR(2, 0x300u);

    EXPECT_FALSE(ref.CheckRegs(dut));
}

TEST(DifftestCPUStateTest, DifferentPcFailsCheck) {
    DifftestCPUState ref;
    ref.SetPC(0x80000000u);

    DifftestCPUState dut;
    dut.SetPC(0x80001000u);

    EXPECT_FALSE(ref.CheckRegs(dut));
}

TEST(DifftestCPUStateTest, DirectionConstantsAreCorrect) {
    EXPECT_EQ(DifftestCPUState::GetDirectionToDUT(), 0);
    EXPECT_EQ(DifftestCPUState::GetDirectionToRef(), 1);
}

TEST(DifftestCPUStateTest, AllRegistersIndependent) {
    DifftestCPUState state;
    for (std::size_t i = 0; i < 32; ++i) {
        state.SetGPR(i, 0x100u + static_cast<std::uint32_t>(i));
    }

    for (std::size_t i = 0; i < 32; ++i) {
        EXPECT_EQ(state.GetGPR(i), 0x100u + static_cast<std::uint32_t>(i)) << "GPR " << i;
    }
}

}  // namespace
