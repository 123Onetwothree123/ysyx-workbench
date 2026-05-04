#include "command/helpCommand.hpp"

#include "command/SDBCommandRegistry.hpp"
#include "command/SDBCommandUtils.hpp"

#include <print>

helpCommand::helpCommand(const SDBCommandRegistry &InputRegistry)
    : Registry(InputRegistry)
{
}
std::string_view helpCommand::Name() const noexcept
{
    return "help";
}

SDBCommandUsageList helpCommand::Usage() const noexcept
{
    static constexpr SDBCommandUsage Entries[]{
        {"[COMMAND]", "打印命令帮助"},
    };
    return Entries;
}

SDBCommandResult helpCommand::Execute(SDBCommandContext &Context, std::string_view Args)
{
    (void)Context;
    Args = SDBTrimLeft(Args);
    if (Args.empty())
    {
        Registry.PrintHelp();
        return SDBCommandResult::Continue;
    }

    const SDBCommand *Command = Registry.FindCommand(Args);
    if (Command == nullptr)
    {
        std::println("未知命令：{}", Args);
        return SDBCommandResult::Continue;
    }

    Registry.PrintHelp(*Command);
    return SDBCommandResult::Continue;
}
