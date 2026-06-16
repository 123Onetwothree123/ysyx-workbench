#include "dCommand.hpp"
#include "../SDBCommandUtils.hpp"
#include <cstddef>
#include <print>
#include "WatchpointPool.hpp"
std::string_view dCommand::name() const noexcept
{
    return "d";
}
SDBCommandUsageList dCommand::usage() const noexcept
{
    static const SDBCommandUsage entries[]{
        {"<NO>", "删除编号为 NO 的监视点"},
    };
    return entries;
}
SDBCommandResult dCommand::execute(SDBCommandContext &context, std::string_view args)
{
    static_cast<void>(context);
    args = SDBTrimLeft(args);
    if (args.empty())
    {
        std::println("d命令的参数是空的");
        return SDBCommandResult::Continue;
    }
    auto NO{std::size_t{0}};
    const auto result{std::from_chars(args.data(), args.data() + args.size(), NO, 10)};
    if (result.ec != std::errc())
    {
        std::println("无效的监视点编号'{}'，from_chars解析错误", args);
        return SDBCommandResult::Continue;
    }
    auto remainder{std::string_view{result.ptr, static_cast<std::size_t>(args.data() + args.size() - result.ptr)}};
    remainder = SDBTrimLeft(remainder);
    if (!remainder.empty())
    {
        std::println("参数中有尾部垃圾'{}'", remainder);
        return SDBCommandResult::Continue;
    }
    const auto MAX_WP{GetGlobalWatchpointPool().GetMaxWatchpoints()};
    if (NO >= MAX_WP)
    {
        std::println("错误：监视点编号{}超出最大允许范围（0-{}）。", NO, MAX_WP - 1);
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