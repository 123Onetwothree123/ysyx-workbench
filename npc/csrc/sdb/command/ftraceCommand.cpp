module npc.sdb.command.ftraceCommand;
import npc.Output;
import npc.config;
import npc.trace.ftrace;
import npc.SimulationContext;

std::string_view ftraceCommand::name() const noexcept
{
    return "ftrace";
}
SDBCommandUsageList ftraceCommand::usage() const noexcept
{
    static const SDBCommandUsage entries[]{
        {"[status]", "看一下ftrace现在开没开、记了多少历史、ELF加载没"},
        {"on", "打开ftrace，后面遇到call或者是ret就会打印出来"},
        {"off", "关掉ftrace，后面的call或者是ret就不管了"},
        {"now|stack", "看看现在还在调用栈里的函数"},
        {"history", "把之前记下来的call或ret历史打印出来"},
        {"history on", "开始保存ftrace历史"},
        {"history off", "不保存ftrace历史了，但实时打印还在"},
        {"reset", "把当前调用栈和ftrace历史都清掉"},
    };
    return entries;
}
SDBCommandResult ftraceCommand::execute(SDBCommandContext &context, std::string_view args)
{
    auto &out{context.GetSimulation().Out()};
    if constexpr (npc::HasFTrace)
    {
    if (args.empty() || args == "status")
    {
        context.GetSimulation().Trace().PrintStatus();
        return SDBCommandResult::Continue;
    }
    if (args == "on")
    {
        context.GetSimulation().Trace().Enable();
        out.Info("ftrace: 已开启");
        return SDBCommandResult::Continue;
    }
    if (args == "off")
    {
        context.GetSimulation().Trace().Disable();
        out.Info("ftrace: 已关闭");
        return SDBCommandResult::Continue;
    }
    if (args == "now" || args == "stack")
    {
        context.GetSimulation().Trace().PrintCurrentStack();
        return SDBCommandResult::Continue;
    }
    if (args == "history")
    {
        context.GetSimulation().Trace().PrintHistory();
        return SDBCommandResult::Continue;
    }
    if (args == "history on")
    {
        context.GetSimulation().Trace().SetRecordHistory(true);
        out.Info("ftrace: history 已开启");
        return SDBCommandResult::Continue;
    }
    if (args == "history off")
    {
        context.GetSimulation().Trace().SetRecordHistory(false);
        out.Info("ftrace: history 已关闭");
        return SDBCommandResult::Continue;
    }
    if (args == "reset")
    {
        context.GetSimulation().Trace().Reset();
        out.Info("ftrace: 已清空调用栈和历史记录");
        return SDBCommandResult::Continue;
    }
    out.Info("用法：ftrace on | off | status | now | history | history on | history off | reset");
    }
    return SDBCommandResult::Continue;
}
