#include "command/historyCommand.hpp"
#include "command/SDBCommandRegistry.hpp"
#include "command/SDBCommandUtils.hpp"
#include <charconv>
#include <print>
#ifdef CONFIG_SDB
#include <readline/history.h>
#endif
historyCommand::historyCommand(const SDBCommandRegistry &InputRegistry)
    : Registry(InputRegistry)
{
}
std::string_view historyCommand::Name() const noexcept
{
    return "history";
}
SDBCommandUsageList historyCommand::Usage() const noexcept
{
    static constexpr SDBCommandUsage Entries[]{
        {"[N]", "显示最近 N 条命令历史"},
    };
    return Entries;
}
void historyCommand::PrintGNUHistory(std::size_t n, const SDBCommandRegistry &Registry)
{
#ifdef CONFIG_SDB
    if (history_length == 0)
    {
        std::println("没有历史记录");
        return;
    }
    auto total{static_cast<std::size_t>(history_length)};
    if (n == 0 || n > total)
    {
        n = total;
    }
    std::println("命令历史（显示最近 {0} 条，共 {1} 条）：", n, total);
    auto start{history_length - static_cast<int>(n)};
    auto hist_list{history_list()};
    for (auto i{start}; i < history_length; i++)
    {
        auto line{hist_list[i]->line};
        // 提取命令名（第一个单词）
        auto CmdLine{std::string_view{line}};
        auto space{CmdLine.find(' ')};
        auto CmdName{(space == std::string::npos) ? CmdLine : CmdLine.substr(0, space)};
        auto cmd{Registry.FindCommand(CmdName)};
        if (cmd != nullptr)
        {
            std::println("{0:5}  {1}", i + history_base, line);
        }
        else
        {
            std::println("{0:5}  {1} [未知命令]", i + history_base, line);
        }
    }
#else
    static_cast<void>(n);
    static_cast<void>(Registry);
    std::println("SDB 未开启，无历史记录");
#endif
}
SDBCommandResult historyCommand::Execute(SDBCommandContext &Context, std::string_view Args)
{
    static_cast<void>(Context);
    auto n{std::size_t{0}}; // 0 表示显示全部
    Args = SDBTrimLeft(Args);
    if (!Args.empty())
    {
        auto num{std::ptrdiff_t{0}};
        const auto result{std::from_chars(Args.data(), Args.data() + Args.size(), num, 10)};
        if (result.ec != std::errc() || num <= 0)
        {
            std::println("无效参数 '{0}'。用法是 history [N]", Args);
            return SDBCommandResult::Continue;
        }
        n = static_cast<std::size_t>(num);
    }
    PrintGNUHistory(n, Registry);
    return SDBCommandResult::Continue;
}
