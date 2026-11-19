module npc.sdb.command.qCommand;
import npc.SimulationContext;
import npc.Output;

std::string_view qCommand::name() const noexcept
{
    return "q";
}
SDBCommandUsageList qCommand::usage() const noexcept
{
    static const SDBCommandUsage entries[]{
        {"", "退出 sdb"}
    };
    return entries;
}
SDBCommandResult qCommand::execute(SDBCommandContext &context, std::string_view args)
{
    auto &out{context.GetSimulation().Out()};
    if (!args.empty())
    {
        out.Info("用法：q");
        return SDBCommandResult::Continue;
    }
    return SDBCommandResult::Quit;
}
