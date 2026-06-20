module npc.sdb.command.historyCommand;
import npc.readline;
import npc.sdb.SDBCommandRegistry;
import npc.sdb.SDBCommandUtils;

historyCommand::historyCommand(const SDBCommandRegistry &InputRegistry)
    : Registry(InputRegistry)
{
}
std::string_view historyCommand::name() const noexcept
{
    return "history";
}
SDBCommandUsageList historyCommand::usage() const noexcept
{
    static const SDBCommandUsage entries[]{
        {"[N]", "显示最近 N 条命令历史"},
    };
    return entries;
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
    std::println("命令历史（显示最近 {} 条，共 {} 条）：", n, total);
    auto start{history_length - static_cast<int>(n)};
    auto hist_list{history_list()};
    for (auto i{start}; i < history_length; i++)
    {
        auto line{hist_list[i]->line};
        auto CmdLine{std::string_view{line}};
        auto space{CmdLine.find(' ')};
        auto CmdName{(space == std::string::npos) ? CmdLine : CmdLine.substr(0, space)};
        auto cmd{Registry.FindCommand(CmdName)};
        if (cmd != nullptr)
        {
            std::println("{:5}  {}", i + history_base, line);
        }
        else
        {
            std::println("{:5}  {} [未知命令]", i + history_base, line);
        }
    }
#else
    static_cast<void>(n);
    static_cast<void>(Registry);
    std::println("SDB 未开启，无历史记录");
#endif
}
SDBCommandResult historyCommand::execute(SDBCommandContext &context, std::string_view args)
{
    static_cast<void>(context);
    auto n{std::size_t{0}};
    args = SDBTrimLeft(args);
    if (!args.empty())
    {
        auto num{static_cast<std::ptrdiff_t>(0)};
        const auto result{std::from_chars(args.data(), args.data() + args.size(), num, 10)};
        if (result.ec != std::errc() || num <= 0)
        {
            std::println("无效参数 '{}'。用法是 history [N]", args);
            return SDBCommandResult::Continue;
        }
        n = static_cast<std::size_t>(num);
    }
    PrintGNUHistory(n, Registry);
    return SDBCommandResult::Continue;
}
