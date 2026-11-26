module npc.sdb.command.wCommand;
import npc.Output;
import npc.sdb.SDBCommandUtils;
import npc.DUT;
import npc.sdb.command.WatchpointPool;
import npc.SimulationContext;

std::string_view wCommand::name() const noexcept
{
    return "w";
}
SDBCommandUsageList wCommand::usage() const noexcept
{
    static const SDBCommandUsage entries[]{
        {"EXPR", "设置表达式监视点，当表达式的值变化时暂停执行"},
    };
    return entries;
}
SDBCommandResult wCommand::execute(SDBCommandContext &context, std::string_view args)
{
    auto &out{context.GetSimulation().Out()};
    args = SDBTrimLeft(args);
    const auto result{SDBEvaluateExpression(args, context.GetDUT(), "没写参数", "括号不匹配")};
    if (result)
    {
        auto *wp{context.GetSimulation().Watchpoints().CreateWatchpoint(std::string(args), *result)};
        if (wp)
        {
            out.Info("监视点 {}: {}", wp->GetNO(), args);
        }
        else
        {
            out.Error("监视点数量满了");
        }
    }
    else
    {
        out.Error("{}", result.error());
    }
    return SDBCommandResult::Continue;
}
