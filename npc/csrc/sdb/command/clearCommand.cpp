module npc.sdb.command.clearCommand;

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
    static_cast<void>(context);
    if (!args.empty())
    {
        std::println("用法：clear");
        return SDBCommandResult::Continue;
    }
    std::print("\033[H\033[J");
    std::cout.flush();
    return SDBCommandResult::Continue;
}
