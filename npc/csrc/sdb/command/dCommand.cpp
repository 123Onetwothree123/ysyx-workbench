module npc.sdb.command.dCommand;
import npc.Output;
import npc.sdb.SDBCommandUtils;
import npc.sdb.command.WatchpointPool;
import npc.SimulationContext;

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
    auto &out{context.GetSimulation().Out()};
    args = SDBTrimLeft(args);
    if (args.empty())
    {
        out.Error("d命令的参数是空的");
        return SDBCommandResult::Continue;
    }
    const auto parsed{SDBParseIntegerArgument<std::size_t>(args)};
    if (!parsed)
    {
        out.Error("无效的监视点编号'{}'，from_chars解析错误", args);
        return SDBCommandResult::Continue;
    }
    if (!parsed->remainder.empty())
    {
        out.Error("参数中有尾部垃圾'{}'", parsed->remainder);
        return SDBCommandResult::Continue;
    }
    const auto NO{parsed->value};
    const auto MAX_WP{context.GetSimulation().Watchpoints().GetMaxWatchpoints()};
    if (NO >= MAX_WP)
    {
        out.Error("错误：监视点编号{}超出最大允许范围（0-{}）。", NO, MAX_WP - 1);
        return SDBCommandResult::Continue;
    }
    if (context.GetSimulation().Watchpoints().DeleteWatchpoint(NO))
    {
        out.Info("监视点{}已删除", NO);
    }
    else
    {
        out.Error("删除监视点{}失败", NO);
    }
    return SDBCommandResult::Continue;
}
