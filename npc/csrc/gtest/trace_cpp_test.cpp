#include <cstdint>
#include <string>
#include <string_view>

#include <gtest/gtest.h>

#include "FtraceEvent.hpp"
#include "FtraceFrame.hpp"
#include "ReadelfFunction.hpp"
#include "RecordInstruction.hpp"
#include "ftrace.hpp"
#include "rv32_encoding.hpp"

namespace npc::test {
namespace {

using rv32::Reg;

TEST(TraceCppTest, RecordInstructionStoresMutableInstructionSnapshot) {
    RecordInstruction record{0x8000'0000u, 0x0010'0073u, 4};

    EXPECT_EQ(record.GetPC(), 0x8000'0000u);
    EXPECT_EQ(record.GetInstruction(), 0x0010'0073u);
    EXPECT_EQ(record.GetLen(), 4);

    record.SetPC(0x8000'0004u);
    record.SetInstruction(0x0000'0013u);
    record.SetLen(2);

    EXPECT_EQ(record.GetPC(), 0x8000'0004u);
    EXPECT_EQ(record.GetInstruction(), 0x0000'0013u);
    EXPECT_EQ(record.GetLen(), 2);
}

TEST(TraceCppTest, ReadelfFunctionUsesHalfOpenAddressRange) {
    constexpr ReadelfFunction function{.name = "main", .start = 0x1000, .end = 0x1040};

    EXPECT_EQ(function.size(), 0x40u);
    EXPECT_TRUE(function.contains(0x1000));
    EXPECT_TRUE(function.contains(0x103f));
    EXPECT_FALSE(function.contains(0x1040));
    EXPECT_FALSE(function.contains(0x0fff));
}

TEST(TraceCppTest, FtraceFrameAndEventKeepCallMetadata) {
    constexpr std::string_view name = "worker";
    const FtraceFrame frame{0x1000, 0x1004, 0x2000, name};
    const FtraceEvent event{FtraceEventType::Call, 0x1000, 0x2000, name, 1};

    EXPECT_EQ(frame.GetCallPC(), 0x1000u);
    EXPECT_EQ(frame.GetReturnPC(), 0x1004u);
    EXPECT_EQ(frame.GetFunctionAddress(), 0x2000u);
    EXPECT_EQ(frame.GetFunctionName(), name);
    EXPECT_EQ(event.GetType(), FtraceEventType::Call);
    EXPECT_EQ(event.GetCurrentPC(), 0x1000u);
    EXPECT_EQ(event.GetTargetPC(), 0x2000u);
    EXPECT_EQ(event.GetFunctionName(), name);
    EXPECT_EQ(event.GetDepth(), 1u);
}

TEST(TraceCppTest, DisabledFtraceIgnoresInstructionEvents) {
    Ftrace trace;
    trace.Disable();

    trace.OnInstruction(0x1000, rv32::jal(Reg::ra, 0x20), 0x1020);

    EXPECT_FALSE(trace.IsEnabled());
    EXPECT_EQ(trace.Depth(), 0u);
    EXPECT_EQ(trace.HistorySize(), 0u);
}

TEST(TraceCppTest, EnabledFtraceTracksManualCallAndReturn) {
    Ftrace trace;
    trace.Enable();

    ::testing::internal::CaptureStdout();
    trace.OnCall(0x1000, 0x2000);
    trace.OnReturn(0x200c, 0x1004);
    const auto output = ::testing::internal::GetCapturedStdout();

    EXPECT_TRUE(trace.IsEnabled());
    EXPECT_EQ(trace.Depth(), 0u);
    ASSERT_EQ(trace.HistorySize(), 2u);
    EXPECT_EQ(trace.History()[0].GetType(), FtraceEventType::Call);
    EXPECT_EQ(trace.History()[1].GetType(), FtraceEventType::Return);
    EXPECT_NE(output.find("call"), std::string::npos);
    EXPECT_NE(output.find("ret"), std::string::npos);
}

TEST(TraceCppTest, FtraceCanDisableHistoryWhileKeepingLiveStack) {
    Ftrace trace;
    trace.Enable();
    trace.SetRecordHistory(false);

    ::testing::internal::CaptureStdout();
    trace.OnCall(0x1000, 0x2000);
    const auto output = ::testing::internal::GetCapturedStdout();

    EXPECT_EQ(trace.Depth(), 1u);
    ASSERT_NE(trace.TopFrame(), nullptr);
    EXPECT_EQ(trace.TopFrame()->GetCallPC(), 0x1000u);
    EXPECT_EQ(trace.HistorySize(), 0u);
    EXPECT_FALSE(trace.RecordHistory());
    EXPECT_NE(output.find("call"), std::string::npos);
}

TEST(TraceCppTest, NestedManualCallsReturnInStackOrder) {
    Ftrace trace;
    trace.Enable();

    ::testing::internal::CaptureStdout();
    trace.OnCall(0x1000, 0x2000);
    trace.OnCall(0x2008, 0x3000);
    trace.OnReturn(0x300c, 0x200c);
    const auto output = ::testing::internal::GetCapturedStdout();

    EXPECT_EQ(trace.Depth(), 1u);
    ASSERT_NE(trace.TopFrame(), nullptr);
    EXPECT_EQ(trace.TopFrame()->GetFunctionAddress(), 0x2000u);
    ASSERT_EQ(trace.HistorySize(), 3u);
    EXPECT_EQ(trace.History()[0].GetDepth(), 1u);
    EXPECT_EQ(trace.History()[1].GetDepth(), 2u);
    EXPECT_EQ(trace.History()[2].GetType(), FtraceEventType::Return);
    EXPECT_EQ(trace.History()[2].GetDepth(), 1u);
    EXPECT_NE(output.find("call"), std::string::npos);
    EXPECT_NE(output.find("ret"), std::string::npos);
}

TEST(TraceCppTest, OnInstructionRecognizesCallReturnAndIgnoresPlainJump) {
    Ftrace trace;
    trace.Enable();

    ::testing::internal::CaptureStdout();
    trace.OnInstruction(0x1000, rv32::jal(Reg::zero, 0x40), 0x1040);
    trace.OnInstruction(0x1004, rv32::jal(Reg::ra, 0x20), 0x1024);
    trace.OnInstruction(0x1024, rv32::ret(), 0x1008);
    const auto output = ::testing::internal::GetCapturedStdout();

    EXPECT_EQ(trace.Depth(), 0u);
    ASSERT_EQ(trace.HistorySize(), 2u);
    EXPECT_EQ(trace.History()[0].GetType(), FtraceEventType::Call);
    EXPECT_EQ(trace.History()[0].GetCurrentPC(), 0x1004u);
    EXPECT_EQ(trace.History()[1].GetType(), FtraceEventType::Return);
    EXPECT_NE(output.find("call"), std::string::npos);
    EXPECT_NE(output.find("ret"), std::string::npos);
}

TEST(TraceCppTest, OnInstructionRecognizesJalrCallUsingAlternateLinkRegister) {
    Ftrace trace;
    trace.Enable();

    ::testing::internal::CaptureStdout();
    trace.OnInstruction(0x2000, rv32::jalr(Reg::t0, Reg::a0, 12), 0x3000);
    const auto output = ::testing::internal::GetCapturedStdout();

    EXPECT_EQ(trace.Depth(), 1u);
    ASSERT_EQ(trace.HistorySize(), 1u);
    EXPECT_EQ(trace.History()[0].GetType(), FtraceEventType::Call);
    EXPECT_EQ(trace.History()[0].GetCurrentPC(), 0x2000u);
    EXPECT_EQ(trace.History()[0].GetTargetPC(), 0x3000u);
    EXPECT_EQ(trace.History()[0].GetDepth(), 1u);
    EXPECT_NE(output.find("call"), std::string::npos);
}

TEST(TraceCppTest, ResetClearsRuntimeTraceButKeepsSwitches) {
    Ftrace trace;
    trace.Enable();
    trace.SetRecordHistory(true);

    ::testing::internal::CaptureStdout();
    trace.OnCall(0x1000, 0x2000);
    static_cast<void>(::testing::internal::GetCapturedStdout());
    ASSERT_EQ(trace.Depth(), 1u);
    ASSERT_EQ(trace.HistorySize(), 1u);

    trace.Reset();

    EXPECT_TRUE(trace.IsEnabled());
    EXPECT_TRUE(trace.RecordHistory());
    EXPECT_EQ(trace.Depth(), 0u);
    EXPECT_EQ(trace.HistorySize(), 0u);
}

TEST(TraceCppTest, DefaultFtraceStateIsDisabled) {
    Ftrace trace;
    EXPECT_FALSE(trace.IsEnabled());
    EXPECT_EQ(trace.Depth(), 0u);
    EXPECT_EQ(trace.HistorySize(), 0u);
}

TEST(TraceCppTest, EnableThenDisableTogglesState) {
    Ftrace trace;
    trace.Enable();
    EXPECT_TRUE(trace.IsEnabled());
    trace.Disable();
    EXPECT_FALSE(trace.IsEnabled());
}

TEST(TraceCppTest, FunctionCountReturnsZeroWhenNoElf) {
    Ftrace trace;
    EXPECT_EQ(trace.FunctionCount(), 0u);
}

TEST(TraceCppTest, TopFrameIsNullWhenStackIsEmpty) {
    Ftrace trace;
    EXPECT_EQ(trace.TopFrame(), nullptr);
}

TEST(TraceCppTest, HistoryIsEmptyWhenNoEvents) {
    Ftrace trace;
    EXPECT_TRUE(trace.History().empty());
}

TEST(TraceCppTest, OnInstructionNonCallNonReturnIsNoop) {
    Ftrace trace;
    trace.Enable();

    ::testing::internal::CaptureStdout();
    trace.OnInstruction(0x1000, rv32::addi(Reg::a0, Reg::zero, 42), 0x1004);
    const auto output = ::testing::internal::GetCapturedStdout();

    EXPECT_EQ(trace.Depth(), 0u);
    EXPECT_EQ(trace.HistorySize(), 0u);
    EXPECT_EQ(output.find("call"), std::string::npos);
    EXPECT_EQ(output.find("ret"), std::string::npos);
}

TEST(TraceCppTest, PrintCurrentStackOnEmptyStackDoesNotCrash) {
    Ftrace trace;
    trace.Enable();
    trace.SetRecordHistory(false);

    ::testing::internal::CaptureStdout();
    trace.PrintCurrentStack();
    static_cast<void>(::testing::internal::GetCapturedStdout());

    SUCCEED();
}

TEST(TraceCppTest, PrintHistoryOnEmptyHistoryDoesNotCrash) {
    Ftrace trace;
    trace.Enable();
    trace.SetRecordHistory(false);

    ::testing::internal::CaptureStdout();
    trace.PrintHistory();
    static_cast<void>(::testing::internal::GetCapturedStdout());

    SUCCEED();
}

TEST(TraceCppTest, PrintStatusShowsEnabledDisabledState) {
    Ftrace trace;
    trace.Enable();

    ::testing::internal::CaptureStdout();
    trace.PrintStatus();
    const auto output = ::testing::internal::GetCapturedStdout();

    EXPECT_NE(output.find("true"), std::string::npos);
}

TEST(TraceCppTest, ManualDeepNestedCallsTrackDepthCorrectly) {
    Ftrace trace;
    trace.Enable();

    ::testing::internal::CaptureStdout();
    for (int i = 0; i < 5; ++i) {
        trace.OnCall(0x1000u + static_cast<std::uint64_t>(i) * 16u,
                     0x2000u + static_cast<std::uint64_t>(i) * 32u);
    }
    const auto output = ::testing::internal::GetCapturedStdout();

    EXPECT_EQ(trace.Depth(), 5u);
    ASSERT_EQ(trace.HistorySize(), 5u);
    for (std::size_t i = 0; i < 5; ++i) {
        EXPECT_EQ(trace.History()[i].GetDepth(), i + 1);
    }
}

TEST(TraceCppTest, DeepReturnSequenceUnwindsStackProperly) {
    Ftrace trace;
    trace.Enable();

    ::testing::internal::CaptureStdout();
    trace.OnCall(0x1000, 0x2000);
    trace.OnCall(0x2008, 0x3000);
    trace.OnCall(0x3008, 0x4000);
    trace.OnReturn(0x400c, 0x300c);
    trace.OnReturn(0x300c, 0x200c);
    trace.OnReturn(0x200c, 0x1004);
    const auto output = ::testing::internal::GetCapturedStdout();

    EXPECT_EQ(trace.Depth(), 0u);
    EXPECT_EQ(trace.HistorySize(), 6u);
}

TEST(TraceCppTest, DisabledFtraceDoesNotBuildStack) {
    Ftrace trace;
    trace.Disable();

    trace.OnCall(0x1000, 0x2000);
    trace.OnCall(0x2008, 0x3000);

    EXPECT_EQ(trace.Depth(), 0u);
    EXPECT_EQ(trace.HistorySize(), 0u);
}

TEST(TraceCppTest, HistoryRecordingCanBeTurnedOff) {
    Ftrace trace;
    trace.Enable();
    trace.SetRecordHistory(false);

    ::testing::internal::CaptureStdout();
    trace.OnCall(0x1000, 0x2000);
    trace.OnCall(0x2008, 0x3000);
    trace.OnReturn(0x300c, 0x200c);
    trace.OnReturn(0x200c, 0x1004);
    const auto output = ::testing::internal::GetCapturedStdout();

    EXPECT_EQ(trace.Depth(), 0u);
    EXPECT_EQ(trace.HistorySize(), 0u);
}

TEST(TraceCppTest, FtraceEventDefaultConstructor) {
    FtraceEvent event{};
    EXPECT_EQ(event.GetType(), FtraceEventType::Call);
    EXPECT_EQ(event.GetCurrentPC(), 0u);
    EXPECT_EQ(event.GetTargetPC(), 0u);
}

TEST(TraceCppTest, FtraceFrameDefaultConstructor) {
    FtraceFrame frame{};
    EXPECT_EQ(frame.GetCallPC(), 0u);
    EXPECT_EQ(frame.GetReturnPC(), 0u);
    EXPECT_EQ(frame.GetFunctionAddress(), 0u);
    EXPECT_TRUE(frame.GetFunctionName().empty());
}

TEST(TraceCppTest, RecordInstructionDefaultConstructor) {
    RecordInstruction record{};
    EXPECT_EQ(record.GetPC(), 0u);
    EXPECT_EQ(record.GetInstruction(), 0u);
    EXPECT_EQ(record.GetLen(), 0);
}

TEST(TraceCppTest, ReadelfFunctionSizeForEmptyRange) {
    constexpr ReadelfFunction function{.name = "empty", .start = 0x1000, .end = 0x1000};
    EXPECT_EQ(function.size(), 0u);
    EXPECT_FALSE(function.contains(0x1000));
}

}  // namespace
}  // namespace npc::test
