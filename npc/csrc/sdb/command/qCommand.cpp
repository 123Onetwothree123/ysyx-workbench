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
    static_cast<void>(args);
    return SDBCommandResult::Quit;
}
