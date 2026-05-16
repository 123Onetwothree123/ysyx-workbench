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
    std::println("NEMU那里后面移植到命令里面的表达式自动测试命令，这里移除了，改到gtest里面去了，跑test的时候，调用gtest文件夹里面的表达式测试");
    // 本来想要做一个前缀模糊搜索的，但是没有做出来，做不出来
    static_cast<void>(Context);
    Args = SDBTrimLeft(Args);
    if (Args.empty())
    {
        std::println("help的子命令都是空的，跑个毛线");
        Registry.PrintHelp();
        return SDBCommandResult::Continue;
    }
    const SDBCommand *Command{Registry.FindCommand(Args)};
    if (Command == nullptr)
    {
        std::println("未知命令：{}", Args);
        return SDBCommandResult::Continue;
    }
    Registry.PrintHelp(*Command);
    return SDBCommandResult::Continue;
}
