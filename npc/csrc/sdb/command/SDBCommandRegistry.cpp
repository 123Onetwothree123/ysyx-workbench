#include "command/SDBCommandRegistry.hpp"
#include "command/cCommand.hpp"
#include "command/clearCommand.hpp"
#include "command/dCommand.hpp"
#include "command/ftraceCommand.hpp"
#include "command/helpCommand.hpp"
#include "command/historyCommand.hpp"
#include "command/infoCommand.hpp"
#include "command/iringbufCommand.hpp"
#include "command/pCommand.hpp"
#include "command/qCommand.hpp"
#include "command/readelfCommand.hpp"
#include "command/setCommand.hpp"
#include "command/siCommand.hpp"
#include "command/wCommand.hpp"
#include "command/xCommand.hpp"
#include "command/SDBCommandUtils.hpp"
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
    auto Width{Command.Name().size()};
    if (!Usage.Arguments.empty())
    {
        Width += 1 + Usage.Arguments.size();
    }
    return Width;
}
[[nodiscard]] std::size_t SDBMaxUsageSyntaxWidth(const SDBCommand &Command) noexcept
{
    auto Width{std::size_t{0}};
    for (const auto &Usage : Command.Usage())
    {
        Width = std::max(Width, SDBUsageSyntaxWidth(Command, Usage));
    }
    return Width;
}
void SDBPrintUsageLine(const SDBCommand &Command, const SDBCommandUsage &Usage, std::size_t SyntaxWidth)
{
    auto Syntax{std::string{Command.Name()}};
    if (!Usage.Arguments.empty())
    {
        Syntax.push_back(' ');
        Syntax += Usage.Arguments;
    }
    std::print("  {}", Syntax);
    if (Syntax.size() < SyntaxWidth)
    {
        std::print("{}", std::string(SyntaxWidth - Syntax.size(), ' '));
    }
    std::println("  {}", Usage.Description);
}
void SDBPrintCommandUsage(const SDBCommand &Command, std::size_t SyntaxWidth)
{
    for (const auto &Usage : Command.Usage())
    {
        SDBPrintUsageLine(Command, Usage, SyntaxWidth);
    }
}
}
SDBCommandRegistry::SDBCommandRegistry(VRV32E32Reg &InputTop, std::size_t &InputCycles)
    : Context(InputTop, InputCycles)
{
    RegisterBuiltins();
}
SDBCommandRegistry::~SDBCommandRegistry() = default;
SDBCommandResult SDBCommandRegistry::Execute(std::string_view Line)
{
    const auto [Name, Args]{SDBSplitCommandLine(Line)};
    if (Name.empty())
    {
        return SDBCommandResult::Continue; // 空行就继续，不干什么
    }
    // 查找命令
    const auto CommandIt{std::ranges::find_if(Commands, [Name](const std::unique_ptr<SDBCommand> &Command)
                                              { return Command->Name() == Name; })};
    if (CommandIt == Commands.end())
    {
        std::println(std::cerr, "鬼知道输入的是什么指令，可能没有实现吧: {}", Line);
        return SDBCommandResult::Continue; // 没找到命令的话也继续
    }
    return (*CommandIt)->Execute(Context, Args);
}
void SDBCommandRegistry::RegisterCommand(std::unique_ptr<SDBCommand> Command)
{
    Commands.push_back(std::move(Command));
}
void SDBCommandRegistry::AddHistory(std::string_view Line)
{
    if (!Line.empty())
    {
        History.emplace_back(Line);
    }
}
const std::vector<std::string> &SDBCommandRegistry::GetHistory() const noexcept
{
    return History;
}
const SDBCommand *SDBCommandRegistry::FindCommand(std::string_view Name) const
{
    const auto CommandIt{std::ranges::find_if(Commands, [Name](const std::unique_ptr<SDBCommand> &Command)
                                                { return Command->Name() == Name; })};
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
void SDBCommandRegistry::PrintHelp(const SDBCommand &Command) const
{
    SDBPrintCommandUsage(Command, SDBMaxUsageSyntaxWidth(Command));
}
void SDBCommandRegistry::RegisterBuiltins()
{
    RegisterCommand(std::make_unique<siCommand>());
    RegisterCommand(std::make_unique<cCommand>());
    RegisterCommand(std::make_unique<infoCommand>());
    RegisterCommand(std::make_unique<xCommand>());
    RegisterCommand(std::make_unique<pCommand>());
    RegisterCommand(std::make_unique<setCommand>());
    RegisterCommand(std::make_unique<wCommand>());
    RegisterCommand(std::make_unique<dCommand>());
    RegisterCommand(std::make_unique<ftraceCommand>());
    RegisterCommand(std::make_unique<readelfCommand>());
    RegisterCommand(std::make_unique<iringbufCommand>());
    RegisterCommand(std::make_unique<helpCommand>(*this));
    RegisterCommand(std::make_unique<historyCommand>(*this));
    RegisterCommand(std::make_unique<clearCommand>());
    RegisterCommand(std::make_unique<qCommand>());
}
