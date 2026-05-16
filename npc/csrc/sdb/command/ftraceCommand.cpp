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
        {"[status]", "看一下ftrace现在开没开、记了多少历史、ELF加载没"},
        {"on", "打开ftrace，后面遇到call或者是ret就会打印出来"},
        {"off", "关掉ftrace，后面的call或者是ret就不管了"},
        {"now|stack", "看看现在还在调用栈里的函数"},
        {"history", "把之前记下来的call或ret历史打印出来"},
        {"history on", "开始保存ftrace历史"},
        {"history off", "不保存ftrace历史了，但实时打印还在"},
        {"reset", "把当前调用栈和ftrace历史都清掉"},
    };
    return Entries;
}
SDBCommandResult ftraceCommand::Execute(SDBCommandContext &Context, std::string_view Args)
{
    static_cast<void>(Context);
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
    static_cast<void>(Args);
    std::println("FTRACE没有开启，请使用 make CONFIG_FTRACE=y");
#endif
    return SDBCommandResult::Continue;
}
