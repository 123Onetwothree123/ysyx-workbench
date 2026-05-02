#include <cstdint>
#include <string>
#include <string_view>

#include <gtest/gtest.h>

#include "SDBMemory.hpp"
#include "command/SDBCommandUtils.hpp"
#include "command/Watchpoint.hpp"
#include "command/WatchpointPool.hpp"
#include "cpu_test_utils.hpp"
#include "memory.hpp"
#include "rv32_encoding.hpp"

namespace npc::test {
namespace {

using rv32::Reg;

TEST(SDBCommandUtilsTest, TrimSplitAndExpressionSyntaxHandleShellLikeInput) {
    EXPECT_EQ(SDBTrimLeft(" \t  si 10"), "si 10");
    EXPECT_EQ(SDBTrimRight("p $a0 \t "), "p $a0");
    EXPECT_EQ(SDBTrimLeft(" \t"), "");
    EXPECT_EQ(SDBTrimRight(" \t"), "");

    const auto [name, args] = SDBSplitCommandLine(" \t x  4 0x80000000 ");
    EXPECT_EQ(name, "x");
    EXPECT_EQ(args, "4 0x80000000 ");

    const auto [single_name, single_args] = SDBSplitCommandLine("q");
    EXPECT_EQ(single_name, "q");
    EXPECT_TRUE(single_args.empty());

    EXPECT_TRUE(SDBValidateExpressionSyntax("($a0 + read32(0x80000000))"));
    EXPECT_FALSE(SDBValidateExpressionSyntax(""));
    EXPECT_FALSE(SDBValidateExpressionSyntax("($a0 + 1"));
    EXPECT_FALSE(SDBValidateExpressionSyntax("$a0 + 1)"));
}

TEST(SDBCommandUtilsTest, SplitHandlesEmptyAndWhitespaceOnlyInput) {
    const auto [empty_name, empty_args] = SDBSplitCommandLine("");
    EXPECT_TRUE(empty_name.empty());
    EXPECT_TRUE(empty_args.empty());

    const auto [blank_name, blank_args] = SDBSplitCommandLine(" \t ");
    EXPECT_TRUE(blank_name.empty());
    EXPECT_TRUE(blank_args.empty());
}

TEST(SDBMemoryTest, SafeReadsUseLittleEndianPhysicalMemoryAndRejectBadRanges) {
    const auto addr = guest_addr(0x300);
    const auto host = guest_to_host(addr);
    pmem[host + 0] = 0x11;
    pmem[host + 1] = 0x22;
    pmem[host + 2] = 0x33;
    pmem[host + 3] = 0x44;

    EXPECT_EQ(NPCMemoryReadSafe(addr + 0, 1), 0x11u);
    EXPECT_EQ(NPCMemoryReadSafe(addr + 1, 1), 0x22u);
    EXPECT_EQ(NPCMemoryReadSafe(addr + 0, 2), 0x2211u);
    EXPECT_EQ(NPCMemoryReadSafe(addr + 0, 4), 0x4433'2211u);

    EXPECT_FALSE(NPCMemoryReadSafe(addr, 3).has_value());
    EXPECT_FALSE(NPCMemoryReadSafe(CONFIG_MBASE - 1, 1).has_value());
    EXPECT_FALSE(NPCMemoryReadSafe(CONFIG_MBASE + PMEM_SIZE - 1, 2).has_value());
}

TEST(SDBMemoryTest, SafeReadsAllowExactEndOfMemoryButRejectPastEnd) {
    const auto last_word_addr = CONFIG_MBASE + PMEM_SIZE - 4;
    const auto last_word_host = guest_to_host(last_word_addr);
    pmem[last_word_host + 0] = 0x78;
    pmem[last_word_host + 1] = 0x56;
    pmem[last_word_host + 2] = 0x34;
    pmem[last_word_host + 3] = 0x12;

    EXPECT_EQ(NPCMemoryReadSafe(last_word_addr, 4), 0x1234'5678u);
    EXPECT_EQ(NPCMemoryReadSafe(CONFIG_MBASE + PMEM_SIZE - 1, 1), 0x12u);
    EXPECT_FALSE(NPCMemoryReadSafe(CONFIG_MBASE + PMEM_SIZE, 1).has_value());
}

TEST(SDBWatchpointTest, WatchpointStoresAndMutatesAllUserVisibleFields) {
    Watchpoint wp{7, true, guest_addr(0x20), true};
    wp.SetExpression("$a0 + 4");
    wp.SetOldValue(0x1234);

    EXPECT_EQ(wp.GetNO(), 7u);
    EXPECT_TRUE(wp.IsEnabled());
    EXPECT_TRUE(wp.HasValidPC());
    EXPECT_EQ(wp.GetPC(), guest_addr(0x20));
    EXPECT_EQ(wp.GetExpression(), "$a0 + 4");
    EXPECT_EQ(wp.GetOldValue(), 0x1234u);

    wp.SetEnabled(false);
    wp.SetHasPC(false);
    wp.SetPC(0);
    EXPECT_FALSE(wp.IsEnabled());
    EXPECT_FALSE(wp.HasValidPC());
    EXPECT_EQ(wp.GetPC(), 0u);
}

TEST(SDBWatchpointTest, PoolAllocatesDeletesAndReusesNumberedSlots) {
    WatchpointPool pool{2};

    auto *first = pool.CreateWatchpoint("$a0", 1);
    auto *second = pool.CreateWatchpoint("$a1", 2);
    ASSERT_NE(first, nullptr);
    ASSERT_NE(second, nullptr);
    EXPECT_EQ(first->GetNO(), 0u);
    EXPECT_EQ(second->GetNO(), 1u);
    EXPECT_TRUE(first->IsEnabled());
    EXPECT_TRUE(second->IsEnabled());

    ::testing::internal::CaptureStdout();
    EXPECT_EQ(pool.CreateWatchpoint("$a2", 3), nullptr);
    static_cast<void>(::testing::internal::GetCapturedStdout());

    ASSERT_TRUE(pool.DeleteWatchpoint(0));
    EXPECT_FALSE(pool.GetWatchpoint(0)->IsEnabled());

    auto *reused = pool.CreateWatchpoint("$a3", 4);
    ASSERT_NE(reused, nullptr);
    EXPECT_EQ(reused->GetNO(), 0u);
    EXPECT_EQ(reused->GetExpression(), "$a3");
    EXPECT_EQ(reused->GetOldValue(), 4u);
}

TEST(SDBWatchpointTest, DeleteRejectsInvalidAndAlreadyFreeSlotsWithoutMutatingUsedOnes) {
    WatchpointPool pool{2};
    auto *watchpoint = pool.CreateWatchpoint("$a0", 1);
    ASSERT_NE(watchpoint, nullptr);

    ::testing::internal::CaptureStdout();
    EXPECT_FALSE(pool.DeleteWatchpoint(2));
    EXPECT_FALSE(pool.DeleteWatchpoint(1));
    static_cast<void>(::testing::internal::GetCapturedStdout());

    EXPECT_TRUE(watchpoint->IsEnabled());
    EXPECT_EQ(watchpoint->GetExpression(), "$a0");
    EXPECT_EQ(watchpoint->GetOldValue(), 1u);
}

TEST(SDBWatchpointTest, CheckAllReadsDpiRegistersAndRecordsTriggerPc) {
    CpuHarness cpu;
    cpu.reset();
    cpu.debug_write_pc(guest_addr(0x40));
    cpu.debug_write_gpr(rv32::reg_bits(Reg::t0), 0x10u);

    WatchpointPool pool{2};
    auto *watchpoint = pool.CreateWatchpoint("$t0", 0x10u);
    ASSERT_NE(watchpoint, nullptr);

    ::testing::internal::CaptureStdout();
    EXPECT_FALSE(pool.CheckAll());
    static_cast<void>(::testing::internal::GetCapturedStdout());
    EXPECT_FALSE(watchpoint->HasValidPC());

    cpu.debug_write_gpr(rv32::reg_bits(Reg::t0), 0x20u);

    ::testing::internal::CaptureStdout();
    EXPECT_TRUE(pool.CheckAll());
    static_cast<void>(::testing::internal::GetCapturedStdout());
    EXPECT_EQ(watchpoint->GetOldValue(), 0x20u);
    EXPECT_TRUE(watchpoint->HasValidPC());
    EXPECT_EQ(watchpoint->GetPC(), guest_addr(0x40));
}

TEST(SDBDpiTest, CpuHarnessDebugPathReadsAndWritesPcAndGprsThroughVerilatedDpi) {
    CpuHarness cpu;
    cpu.reset();

    EXPECT_EQ(cpu.debug_read_pc(), guest_addr(0));

    cpu.debug_write_gpr(5, 0x1234'5678u);
    EXPECT_EQ(cpu.debug_read_gpr(5), 0x1234'5678u);

    cpu.debug_write_gpr(37, 0x2468'ace0u);
    EXPECT_EQ(cpu.debug_read_gpr(5), 0x2468'ace0u);

    cpu.debug_write_gpr(0, 0xffff'ffffu);
    EXPECT_EQ(cpu.debug_read_gpr(0), 0u);

    cpu.debug_write_pc(guest_addr(0x80));
    EXPECT_EQ(cpu.debug_read_pc(), guest_addr(0x80));
}

}  // namespace
}  // namespace npc::test
