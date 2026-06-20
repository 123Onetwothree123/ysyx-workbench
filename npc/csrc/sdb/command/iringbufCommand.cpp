module npc.sdb.command.iringbufCommand;
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
    static_cast<void>(context);
    if (!args.empty())
    {
        std::println("用法：iringbuf");
        return SDBCommandResult::Continue;
    }
    PrintIringbuf(0);
    return SDBCommandResult::Continue;
}
