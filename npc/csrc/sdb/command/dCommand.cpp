#include "dCommand.hpp"
#include "command/SDBCommandUtils.hpp"
#include "command/WatchpointPool.hpp"
#include <charconv>
#include <print>

std::string_view dCommand::Name() const noexcept
{
    return "d";
}
SDBCommandUsageList dCommand::Usage() const noexcept
{
    static constexpr SDBCommandUsage Entries[]{
        {"<NO>", "删除编号为 NO 的监视点"},
    };
    return Entries;
}
SDBCommandResult dCommand::Execute(SDBCommandContext &Context, std::string_view Args)
{
    (void)Context;
    Args = SDBTrimLeft(Args);
    if (Args.empty())
    {
        std::println("用法：d <NO>");
        return SDBCommandResult::Continue;
    }
    std::size_t NO{0};
    const auto Result{std::from_chars(Args.data(), Args.data() + Args.size(), NO, 10)};
    if (Result.ec != std::errc())
    {
        std::println("错误：无效的监视点编号 '{}'。ID必须是数字。", Args);
        return SDBCommandResult::Continue;
    }
    // 检查尾部垃圾字符（如 "d 1abc"）
    std::string_view Remainder{Result.ptr, static_cast<std::size_t>(Args.data() + Args.size() - Result.ptr)};
    Remainder = SDBTrimLeft(Remainder);
    if (!Remainder.empty())
    {
        std::println("错误：参数中有尾部垃圾 '{}'。用法：d <NO>", Remainder);
        return SDBCommandResult::Continue;
    }
    const auto MaxWP = GetGlobalWatchpointPool().GetMaxWatchpoints();
    if (NO >= MaxWP)
    {
        std::println("错误：监视点编号 {} 超出最大允许范围（0-{}）。", NO, MaxWP - 1);
        return SDBCommandResult::Continue;
    }
    if (GetGlobalWatchpointPool().DeleteWatchpoint(NO))
    {
        std::println("监视点{}已删除", NO);
    }
    else
    {
        std::println("删除监视点{}失败", NO);
    }
    return SDBCommandResult::Continue;
}
