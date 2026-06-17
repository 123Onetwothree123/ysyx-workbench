#include "clearCommand.hpp"
#include <print>
std::string_view clearCommand::name() const noexcept
{
    return "clear";
}
SDBCommandUsageList clearCommand::usage() const noexcept
{
    static const SDBCommandUsage entries[]{
        {"", "清除终端屏幕"},
    };
    return entries;
}
SDBCommandResult clearCommand::execute(SDBCommandContext &context, std::string_view args)
{
    static_cast<void>(context);
    static_cast<void>(args);
    std::print("\033[H\033[J");
    std::fflush(stdout);
    return SDBCommandResult::Continue;
}
