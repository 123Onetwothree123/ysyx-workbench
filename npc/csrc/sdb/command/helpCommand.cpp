module npc.sdb.command.helpCommand;
import npc.SimulationContext;
import npc.Output;
import npc.sdb.SDBCommandRegistry;
import npc.sdb.SDBCommandUtils;

helpCommand::helpCommand(const SDBCommandRegistry &InputRegistry) : registry(InputRegistry)
{
}
std::string_view helpCommand::name() const noexcept
{
    return "help";
}
SDBCommandUsageList helpCommand::usage() const noexcept
{
    static const SDBCommandUsage entries[]{
        {"[COMMAND]", "打印命令帮助"}
    };
    return entries;
}
SDBCommandResult helpCommand::execute(SDBCommandContext &context, std::string_view args)
{
    auto &out{context.GetSimulation().Out()};
    args = SDBTrimLeft(args);
    if (args.empty())
    {
        out.Info("help的子命令都是空的，他妈的跑个毛线啊");
        registry.PrintHelp();
        return SDBCommandResult::Continue;
    }
    const auto *command{registry.FindCommand(args)};
    if (command == nullptr)
    {
        out.Error("鬼知道是什么命令：{0}", args);
        return SDBCommandResult::Continue;
    }
    registry.PrintHelp(*command);
    return SDBCommandResult::Continue;
}
