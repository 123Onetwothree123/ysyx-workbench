#include "SDBCommandRegistry.hpp"
#include "command/cCommand.hpp"
#include "command/dCommand.hpp"
#include "command/helpCommand.hpp"
#include "command/infoCommand.hpp"
#include "command/pCommand.hpp"
#include "command/qCommand.hpp"
#include "command/siCommand.hpp"
#include "command/wCommand.hpp"
#include "command/xCommand.hpp"
#include "command/clearCommand.hpp"
#ifdef CONFIG_ITRACE
#include "command/iringbufCommand.hpp"
#endif
#ifdef CONFIG_FTRACE
#include "command/ftraceCommand.hpp"
#endif
#include "SDBCommandUtils.hpp"
#include <algorithm>
#include <cstddef>
#include <iostream>
#include <memory>
#include <print>
#include <ranges>
#include <string>
#include <utility>
namespace
{
    [[nodiscard]] std::size_t SDBUsageSyntaxWidth(const SDBCommand &Command, const SDBCommandUsage &Usage) noexcept
    {
        auto Width{Command.name().size()};
        if (!Usage.GetArguments().empty())
        {
            Width += 1 + Usage.GetArguments().size();
        }
        return Width;
    }
    [[nodiscard]] std::size_t SDBMaxUsageSyntaxWidth(const SDBCommand &Command) noexcept
    {
        auto Width{std::size_t{0}};
        for (const auto &Usage : Command.usage())
        {
            Width = std::max(Width, SDBUsageSyntaxWidth(Command, Usage));
        }
        return Width;
    }
    void SDBPrintUsageLine(const SDBCommand &Command, const SDBCommandUsage &Usage, std::size_t SyntaxWidth)
    {
        auto Syntax{std::string{Command.name()}};
        if (!Usage.GetArguments().empty())
        {
            Syntax.push_back(' ');
            Syntax += Usage.GetArguments();
        }
        std::print("  {}", Syntax);
        if (Syntax.size() < SyntaxWidth)
        {
            std::print("{}", std::string(SyntaxWidth - Syntax.size(), ' '));
        }
        std::println("  {}", Usage.GetDescription());
    }
    void SDBPrintCommandUsage(const SDBCommand &Command, std::size_t SyntaxWidth)
    {
        for (const auto &Usage : Command.usage())
        {
            SDBPrintUsageLine(Command, Usage, SyntaxWidth);
        }
    }
}
SDBCommandRegistry::SDBCommandRegistry(DUT &dut)
    : Context(dut)
{
    RegisterBuiltins();
}
SDBCommandRegistry::~SDBCommandRegistry() = default;
SDBCommandResult SDBCommandRegistry::Execute(std::string_view line)
{
    const auto [Name, Args]{SDBSplitCommandLine(line)};
    if (Name.empty())
    {
        return SDBCommandResult::Continue;
    }
    const auto CommandIt{std::ranges::find_if(Commands, [Name](const std::unique_ptr<SDBCommand> &Command){return Command->name() == Name; })};
    if (CommandIt == Commands.end())
    {
        std::println(std::cerr, "未知命令: {}", line);
        return SDBCommandResult::Continue;
    }
    return (*CommandIt)->execute(Context, Args);
}
void SDBCommandRegistry::RegisterCommand(std::unique_ptr<SDBCommand> command)
{
    Commands.push_back(std::move(command));
}
void SDBCommandRegistry::AddHistory(std::string_view line)
{
    if (!line.empty())
    {
        History.emplace_back(line);
    }
}
const std::vector<std::string> &SDBCommandRegistry::GetHistory() const noexcept
{
    return History;
}
const SDBCommand *SDBCommandRegistry::FindCommand(std::string_view name) const
{
    const auto CommandIt{std::ranges::find_if(Commands, [name](const std::unique_ptr<SDBCommand> &Command)
                                              { return Command->name() == name; })};
    if (CommandIt == Commands.end())
    {
        return nullptr;
    }
    return CommandIt->get();
}
void SDBCommandRegistry::PrintHelp() const
{
    auto SyntaxWidth{std::size_t{0}};
    for (const auto &Command : Commands)
    {
        SyntaxWidth = std::max(SyntaxWidth, SDBMaxUsageSyntaxWidth(*Command));
    }
    std::println("可用命令：");
    for (const auto &Command : Commands)
    {
        SDBPrintCommandUsage(*Command, SyntaxWidth);
    }
}
void SDBCommandRegistry::PrintHelp(const SDBCommand &command) const
{
    SDBPrintCommandUsage(command, SDBMaxUsageSyntaxWidth(command));
}
void SDBCommandRegistry::RegisterBuiltins()
{
    RegisterCommand(std::make_unique<siCommand>());
    RegisterCommand(std::make_unique<cCommand>());
    RegisterCommand(std::make_unique<infoCommand>());
    RegisterCommand(std::make_unique<xCommand>());
    RegisterCommand(std::make_unique<pCommand>());
    RegisterCommand(std::make_unique<wCommand>());
    RegisterCommand(std::make_unique<dCommand>());
#ifdef CONFIG_ITRACE
    RegisterCommand(std::make_unique<iringbufCommand>());
#endif
#ifdef CONFIG_FTRACE
    RegisterCommand(std::make_unique<ftraceCommand>());
#endif
    RegisterCommand(std::make_unique<clearCommand>());
    RegisterCommand(std::make_unique<helpCommand>(*this));
    RegisterCommand(std::make_unique<qCommand>());
}
