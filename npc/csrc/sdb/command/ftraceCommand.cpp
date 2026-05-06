#include "command/ftraceCommand.hpp"
#include "ftrace.hpp"
#include <print>
std::string_view ftraceCommand::Name() const noexcept
{
    return "ftrace";
}
SDBCommandUsageList ftraceCommand::Usage() const noexcept
{
    static constexpr SDBCommandUsage Entries[]{
        {"...", "控制或查看函数调用跟踪"},
    };
    return Entries;
}
SDBCommandResult ftraceCommand::Execute(SDBCommandContext &Context, std::string_view Args)
{
    (void)Context;
#ifdef CONFIG_FTRACE
    if (Args.empty() || Args == "status")
    {
        GlobalFtrace.PrintStatus();
        return SDBCommandResult::Continue;
    }
    if (Args == "on")
    {
        GlobalFtrace.Enable();
        std::println("ftrace: 已开启");
        return SDBCommandResult::Continue;
    }
    if (Args == "off")
    {
        GlobalFtrace.Disable();
        std::println("ftrace: 已关闭");
        return SDBCommandResult::Continue;
    }
    if (Args == "now" || Args == "stack")
    {
        GlobalFtrace.PrintCurrentStack();
        return SDBCommandResult::Continue;
    }
    if (Args == "history")
    {
        GlobalFtrace.PrintHistory();
        return SDBCommandResult::Continue;
    }
    if (Args == "history on")
    {
        GlobalFtrace.SetRecordHistory(true);
        std::println("ftrace: history 已开启");
        return SDBCommandResult::Continue;
    }
    if (Args == "history off")
    {
        GlobalFtrace.SetRecordHistory(false);
        std::println("ftrace: history 已关闭");
        return SDBCommandResult::Continue;
    }
    if (Args == "reset")
    {
        GlobalFtrace.Reset();
        std::println("ftrace: 已清空调用栈和历史记录");
        return SDBCommandResult::Continue;
    }
    std::println("用法：ftrace on | off | status | now | history | history on | history off | reset");
#else
    (void)Args;
    std::println("FTRACE没有开启，请使用 make CONFIG_FTRACE=y");
#endif
    return SDBCommandResult::Continue;
}
