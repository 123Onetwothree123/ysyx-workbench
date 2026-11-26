module npc.sdb.command.iringbufCommand;
import npc.SimulationContext;
import npc.Output;
import npc.sdb.SDBCommandUtils;
import npc.trace.itrace;

std::string_view iringbufCommand::name() const noexcept
{
    return "iringbuf";
}
SDBCommandUsageList iringbufCommand::usage() const noexcept
{
    static const SDBCommandUsage entries[]{
        {"", "打印最近记录的指令环形缓冲区"},
    };
    return entries;
}
SDBCommandResult iringbufCommand::execute(SDBCommandContext &context, std::string_view args)
{
    auto &out{context.GetSimulation().Out()};
    if (SDBRejectUnexpectedArguments(args, "用法：iringbuf", out))
    {
        return SDBCommandResult::Continue;
    }
    PrintIringbuf(0, out);
    return SDBCommandResult::Continue;
}
