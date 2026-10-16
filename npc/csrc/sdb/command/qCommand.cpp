module npc.sdb.command.qCommand;

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
    static_cast<void>(context);
    if (!args.empty())
    {
        std::println("用法：q");
        return SDBCommandResult::Continue;
    }
    return SDBCommandResult::Quit;
}
