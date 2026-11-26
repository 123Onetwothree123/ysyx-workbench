module npc.sdb.command.clearCommand;
import npc.SimulationContext;
import npc.Output;
import npc.sdb.SDBCommandUtils;

std::string_view clearCommand::name() const noexcept
{
    return "clear";
}
SDBCommandUsageList clearCommand::usage() const noexcept
{
    static const SDBCommandUsage entries[]{
        {"", "清除终端屏幕"},
    };
    return entries;
}
SDBCommandResult clearCommand::execute(SDBCommandContext &context, std::string_view args)
{
    auto &out{context.GetSimulation().Out()};
    if (SDBRejectUnexpectedArguments(args, "用法：clear", out))
    {
        return SDBCommandResult::Continue;
    }
    out.WriteRaw(OutputLevel::Info, "\033[H\033[J");
    return SDBCommandResult::Continue;
}
