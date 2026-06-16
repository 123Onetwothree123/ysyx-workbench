#include "helpCommand.hpp"
#include "../SDBCommandRegistry.hpp"
#include "SDBCommandUtils.hpp"
#include <print>
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
    static_cast<void>(context); // 不要上下文
    args = SDBTrimLeft(args);
    if (args.empty())
    {
        std::println("help的子命令都是空的，他妈的跑个毛线啊");
        registry.PrintHelp();
        return SDBCommandResult::Continue;
    }
    const auto *command{registry.FindCommand(args)};
    if (command == nullptr)
    {
        std::println("鬼知道是什么命令：{0}", args);
        return SDBCommandResult::Continue;
    }
    registry.PrintHelp(*command);
    return SDBCommandResult::Continue;
}
